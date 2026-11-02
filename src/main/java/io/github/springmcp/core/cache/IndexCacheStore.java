package io.github.springmcp.core.cache;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.StringJoiner;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import io.github.springmcp.core.index.CuSummary;
import io.github.springmcp.core.index.FileFingerprint;
import io.github.springmcp.core.index.SourceIndex;
import io.github.springmcp.spi.api.IndexOptions;
import io.github.springmcp.spi.model.IndexMetadata;

/**
 * Stores one index per project in a user cache directory, so a fresh process does not have to parse
 * the whole repository again.
 *
 * <p>
 * Measured on spring-boot (8 413 files, 14 155 types): building takes 8.3 s, writing the cache 0.2 s,
 * parsing it back 0.25 s, and re-fingerprinting the tree 0.75 s — a cold start of ~1.1 s instead of
 * 8.3 s. End to end, a fresh process answering {@code list-controllers} drops from 10.4 s to 2.2 s.
 * Those figures come from a one-off measurement, not from the test suite; the README records them so
 * a later change that quietly makes the format expensive has a number to be compared against.
 * </p>
 *
 * <p>
 * Nothing here may ever break a tool call. Every failure path — missing file, wrong version, corrupt
 * JSON, unwritable directory — degrades to "no cache", and the caller builds normally.
 * </p>
 */
public final class IndexCacheStore {

    /** One megabyte cap on how much of a corrupt file is quoted back in a diagnostic. */
    private static final int MAX_REPORTED_BYTES = 200;

    /**
     * How much disk the whole cache may occupy before the least recently written projects are dropped.
     *
     * <p>
     * Without a bound the directory only grows: one entry per repository ever analysed, ~36 MB each,
     * never revisited. The real-world corpus alone (231 repositories) is about 8 GB, and it is not
     * freed by anything except remembering to delete the folder. The cap is on bytes rather than on
     * entry count because entry sizes differ by two orders of magnitude.
     * </p>
     */
    private static final long MAX_TOTAL_BYTES = 1L << 30;

    private static final String TMP_SUFFIX = ".tmp";

    /**
     * How long a {@code .tmp} file must sit untouched before it counts as debris.
     *
     * <p>
     * A leftover temp file is normally from a write that died — but it can also be a write that is
     * happening <em>right now</em> in another process analysing the same project. Deleting that one
     * makes the other process fail its atomic move and lose a cache write for no reason. A write takes
     * a fraction of a second, so anything still present after ten minutes is debris.
     * </p>
     */
    private static final long TMP_GRACE_MILLIS = 10 * 60 * 1000L;

    private final Path directory;

    /** Guarded by nothing: written on the build thread, read by diagnostics and tests. */
    private volatile String lastFailure;

    /** Trimming runs once per store instance: the first write pays for it, the rest do not. */
    private final AtomicBoolean pruned = new AtomicBoolean();

    private final ObjectMapperHolder holder = new ObjectMapperHolder();

    private IndexCacheStore(Path directory) {
        this.directory = directory;
    }

    /**
     * @return a store that never reads or writes anything
     */
    public static IndexCacheStore disabled() {
        return new IndexCacheStore(null);
    }

    /**
     * @param directory cache root, created on first write
     * @return a store rooted there
     */
    public static IndexCacheStore at(Path directory) {
        return new IndexCacheStore(directory == null ? null : directory.toAbsolutePath().normalize());
    }

    /**
     * Platform-appropriate user cache directory.
     *
     * <p>
     * Never inside the analysed repository: this tool only reads other people's checkouts, and writing
     * into them would both pollute the tree and lose the cache on the next {@code mvn clean}.
     * </p>
     *
     * @return {@code %LOCALAPPDATA%\spring-mcp\index}, {@code $XDG_CACHE_HOME/spring-mcp/index}, or
     * {@code ~/.cache/spring-mcp/index}
     */
    public static Path defaultDirectory() {
        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData != null && !localAppData.isBlank()) {
            return Path.of(localAppData, "spring-mcp", "index");
        }
        String xdg = System.getenv("XDG_CACHE_HOME");
        if (xdg != null && !xdg.isBlank()) {
            return Path.of(xdg, "spring-mcp", "index");
        }
        return Path.of(System.getProperty("user.home", "."), ".cache", "spring-mcp", "index");
    }

    /**
     * Why the last {@link #save} failed, or {@code null} if it succeeded.
     *
     * <p>
     * The write is best-effort — a read-only home directory must not break the tool — so the failure is
     * returned rather than thrown. Without keeping it, "the cache never helps" is undiagnosable from
     * outside, which is exactly how five Windows Server tests looked like unrelated IO bugs.
     * </p>
     *
     * @return the last failure description, or {@code null}
     */
    public String lastFailure() {
        return lastFailure;
    }

    /**
     * @return whether this store does anything at all
     */
    public boolean enabled() {
        return directory != null;
    }

    /**
     * @return the directory backing this store, or {@code null} when disabled
     */
    public Path directory() {
        return directory;
    }

    /**
     * Result of a load attempt.
     *
     * @param hit whether the cache was used
     * @param reason why it was not, {@code null} on a hit; meant for logs, not for callers to branch on
     * @param summaries the payload, empty on a miss
     * @param problems parse problems recorded at build time
     * @param warnings index warnings recorded at build time
     * @param builtAtEpochMillis when the cached build happened
     * @param reusable summaries that may be reused even though the cache is stale; empty when the
     * file failed a gate that affects meaning (format, build, root, options)
     */
    public record Load(boolean hit, String reason, List<CuSummary> summaries,
            List<IndexMetadata.ParseProblem> problems, List<String> warnings, long builtAtEpochMillis,
            List<CuSummary> reusable) {

        static Load miss(String reason) {
            return new Load(false, reason, List.of(), List.of(), List.of(), 0, List.of());
        }
    }

    /**
     * Tries to answer from disk.
     *
     * <p>
     * The caller passes the walk it has already done, because comparing fingerprints is the only way
     * to know the cache is current — and re-walking inside this method would pay that cost twice.
     * </p>
     *
     * @param root project root
     * @param options options of the requested build
     * @param walked files found right now
     * @return a hit with the stored summaries, or a miss carrying the reason
     */
    public Load load(Path root, IndexOptions options, List<FileFingerprint> walked) {
        if (!enabled()) {
            return Load.miss("cache disabled");
        }
        Path file = fileFor(root);
        if (!Files.isRegularFile(file)) {
            return Load.miss("no cache file");
        }
        CacheEnvelope envelope;
        try {
            envelope = holder.mapper().readValue(file.toFile(), CacheEnvelope.class);
        }
        catch (IOException | RuntimeException e) {
            return Load.miss("unreadable cache: " + abbreviate(String.valueOf(e.getMessage())));
        }
        if (envelope == null) {
            return Load.miss("empty cache file");
        }

        if (envelope.format() != IndexCacheFormat.VERSION) {
            return Load.miss("format " + envelope.format() + " != " + IndexCacheFormat.VERSION);
        }
        if (!IndexCacheFormat.codeStamp().equals(envelope.codeStamp())) {
            return Load.miss("written by a different build");
        }
        if (!root.toAbsolutePath().normalize().toString().equals(envelope.root())) {
            return Load.miss("cache belongs to another root");
        }
        if (!optionsKey(options).equals(envelope.optionsKey())) {
            return Load.miss("options changed");
        }
        if (envelope.summaries().size() != walked.size() || envelope.fileCount() != walked.size()) {
            // File added or removed: the payload is still per-file and remains reusable, but the
            // counts alone no longer describe a valid snapshot.
            return new Load(false, "file count " + walked.size() + " != cached " + envelope.fileCount(),
                    List.of(), List.of(), List.of(), 0, envelope.summaries());
        }
        String digest = digest(walked);
        if (!digest.equals(envelope.digest())) {
            return new Load(false, "files changed since the cache was written", List.of(), List.of(),
                    List.of(), 0, envelope.summaries());
        }
        return new Load(true, null, envelope.summaries(), envelope.problems(), envelope.warnings(),
                envelope.builtAtEpochMillis(), envelope.summaries());
    }

    /**
     * Writes the index of a snapshot.
     *
     * <p>
     * Failure is logged by the caller and otherwise ignored: a read-only cache directory must not turn
     * a successful analysis into an error.
     * </p>
     *
     * @param root project root
     * @param options options the snapshot was built with
     * @param index the snapshot
     * @return {@code null} on success, otherwise a short failure description
     */
    public String save(Path root, IndexOptions options, SourceIndex index) {
        if (!enabled()) {
            lastFailure = null;
            return null;
        }
        List<FileFingerprint> fingerprints = index.fingerprints();
        CacheEnvelope envelope = new CacheEnvelope(IndexCacheFormat.VERSION, IndexCacheFormat.codeStamp(),
                root.toAbsolutePath().normalize().toString(), optionsKey(options), digest(fingerprints),
                fingerprints.size(), System.currentTimeMillis(), index.metadata().problems(),
                index.metadata().warnings(), summariesOf(index));

        Path file = fileFor(root);
        Path tmp = file.resolveSibling(IndexCacheFormat.FILE_NAME + TMP_SUFFIX);
        try {
            Files.createDirectories(file.getParent());
            holder.mapper().writeValue(tmp.toFile(), envelope);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            }
            catch (AtomicMoveNotSupportedException e) {
                // Some network shares refuse atomic moves; a plain replace is still better than failing.
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            lastFailure = null;
        }
        catch (IOException | RuntimeException e) {
            deleteQuietly(tmp);
            lastFailure = e.getClass().getSimpleName() + ": " + abbreviate(String.valueOf(e.getMessage()));
            return lastFailure;
        }

        // Only after a successful write, and only once per process: the entry just written is the
        // newest one, so it can never be the one evicted.
        if (pruned.compareAndSet(false, true)) {
            pruneQuietly();
        }
        return null;
    }

    /**
     * Trims the cache directory, never letting a failure escape.
     *
     * <p>
     * Same rule as everywhere else in this class: cache maintenance may not break a tool call. A
     * locked file, a permission error or a network share that refuses deletes changes nothing about
     * the answer.
     * </p>
     */
    private void pruneQuietly() {
        try {
            prune(MAX_TOTAL_BYTES);
        }
        catch (IOException | RuntimeException e) {
            // Deliberately silent: this is background housekeeping, not a result.
        }
    }

    /**
     * Drops the least recently written projects until the cache fits in {@code maxBytes}.
     *
     * <p>
     * Ordering is by the cache file's modification time, which is when the project was last
     * <em>built</em>, not last read: a hit does not rewrite the file. A hot project that keeps hitting
     * can therefore look old and be evicted under pressure — the cost of that mistake is one rebuild,
     * which is the same cost as having no cache at all, so the trade is safe in the only direction
     * that matters.
     * </p>
     *
     * <p>
     * Leftover {@code .tmp} files from writes that died midway are removed first and for free.
     * </p>
     *
     * @param maxBytes budget for the whole directory
     * @return number of project directories removed
     * @throws IOException when the directory cannot be listed
     */
    int prune(long maxBytes) throws IOException {
        if (!enabled() || !Files.isDirectory(directory)) {
            return 0;
        }

        List<Path> entries = new ArrayList<>();
        long total = 0;
        try (Stream<Path> children = Files.list(directory)) {
            for (Path child : children.toList()) {
                if (!Files.isDirectory(child)) {
                    continue;
                }
                Path tmp = child.resolve(IndexCacheFormat.FILE_NAME + TMP_SUFFIX);
                if (ageMillis(tmp) > TMP_GRACE_MILLIS) {
                    deleteQuietly(tmp);
                }
                Path file = child.resolve(IndexCacheFormat.FILE_NAME);
                if (!Files.isRegularFile(file)) {
                    // Empty directory from a write that never completed: no bytes to reclaim.
                    continue;
                }
                entries.add(child);
                total += sizeOf(file);
            }
        }

        if (total <= maxBytes) {
            return 0;
        }

        entries.sort(Comparator.comparingLong(IndexCacheStore::lastModified));
        int removed = 0;
        for (Path entry : entries) {
            if (total <= maxBytes) {
                break;
            }
            long size = sizeOf(entry.resolve(IndexCacheFormat.FILE_NAME));
            deleteQuietly(entry.resolve(IndexCacheFormat.FILE_NAME));
            deleteQuietly(entry);
            total -= size;
            removed++;
        }
        return removed;
    }

    /**
     * @param path file to inspect
     * @return milliseconds since it was last modified, {@code 0} when it does not exist or cannot be read
     */
    private static long ageMillis(Path path) {
        try {
            return System.currentTimeMillis() - Files.getLastModifiedTime(path).toMillis();
        }
        catch (IOException e) {
            return 0;
        }
    }

    /**
     * @param path file to measure
     * @return its size, or {@code 0} when it cannot be read
     */
    private static long sizeOf(Path path) {
        try {
            return Files.size(path);
        }
        catch (IOException e) {
            return 0;
        }
    }

    /**
     * @param path file to inspect
     * @return its modification time, or {@code 0} when it cannot be read — which sorts it as the
     * oldest entry, the right default for something that cannot be examined
     */
    private static long lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path.resolve(IndexCacheFormat.FILE_NAME)).toMillis();
        }
        catch (IOException e) {
            return 0;
        }
    }

    /**
     * Deletes the cache of one project.
     * @param root project root
     */
    public void invalidate(Path root) {
        if (!enabled()) {
            return;
        }
        deleteQuietly(fileFor(root));
    }

    /**
     * @param root project root
     * @return the file this store would use, whether or not it exists
     */
    public Path fileFor(Path root) {
        String key = hashHex(canonical(root));
        return directory == null ? Path.of(IndexCacheFormat.FILE_NAME) : directory.resolve(key)
            .resolve(IndexCacheFormat.FILE_NAME);
    }


    /**
     * One spelling per directory, so the writer and the reader agree on the cache key.
     *
     * <p>
     * {@code toRealPath()} resolves everything the file system treats as an alias: 8.3 short names
     * (`RUNNER~1` on a CI runner), symlinks, junctions and letter case. Hashing
     * {@code toAbsolutePath().normalize()} instead meant a store written under the short name was
     * looked up under the long one — the cache was written, never found, and nothing reported it.
     * The registry already resolves its root this way; the hash has to match it.
     * </p>
     *
     * @param root project root, in whatever spelling the caller has
     * @return the canonical form when the path exists, otherwise a merely absolute one
     */
    private static String canonical(Path root) {
        try {
            return root.toRealPath().toString();
        }
        catch (IOException e) {
            return root.toAbsolutePath().normalize().toString();
        }
    }

    private static List<CuSummary> summariesOf(SourceIndex index) {
        return index.summariesByFile().values().stream().toList();
    }

    /**
     * Canonical form of the build options.
     *
     * <p>
     * {@code maxBuildTime} is left out on purpose: it bounds how long a build may take, not what it
     * produces, so a caller raising the timeout must not throw away a perfectly good cache.
     * </p>
     *
     * @param options options to describe
     * @return a stable one-line description
     */
    static String optionsKey(IndexOptions options) {
        StringJoiner excludes = new StringJoiner(",");
        options.extraExcludes().stream().sorted().forEach(excludes::add);
        return String.format(Locale.ROOT, "tests=%s;generated=%s;excludes=%s;maxFileBytes=%d;maxFiles=%d",
                options.includeTests(), options.includeGenerated(), excludes, options.maxFileBytes(),
                options.maxIndexedFiles());
    }

    /**
     * Digest of the file fingerprints, ordered by path.
     * @param fingerprints files as found by the walker, sorted by path
     * @return hex SHA-256
     */
    static String digest(List<FileFingerprint> fingerprints) {
        MessageDigest sha = sha256();
        for (FileFingerprint fingerprint : fingerprints) {
            sha.update(fingerprint.path().toString().getBytes(StandardCharsets.UTF_8));
            sha.update((byte) 0);
            sha.update(longBytes(fingerprint.size()));
            sha.update(longBytes(fingerprint.lastModifiedMillis()));
            sha.update((byte) '\n');
        }
        return HexFormat.of().formatHex(sha.digest());
    }

    private static byte[] longBytes(long value) {
        byte[] out = new byte[8];
        for (int i = 7; i >= 0; i--) {
            out[i] = (byte) (value & 0xFF);
            value >>>= 8;
        }
        return out;
    }

    private static String hashHex(String value) {
        return HexFormat.of().formatHex(sha256().digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        }
        catch (NoSuchAlgorithmException e) {
            // Every JRE is required to provide SHA-256; failing here means the JRE is broken.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        }
        catch (IOException e) {
            // Nothing useful to do: the next save overwrites it anyway.
        }
    }

    private static String abbreviate(String message) {
        if (message == null) {
            return "";
        }
        return message.length() <= MAX_REPORTED_BYTES ? message : message.substring(0, MAX_REPORTED_BYTES) + "…";
    }

    /**
     * Lazily built mapper.
     *
     * <p>
     * Lazy because {@link IndexCacheStore#disabled()} is the common case in tests and in the baseline
     * runner, and constructing a Jackson mapper there would be pure waste.
     * </p>
     */
    private static final class ObjectMapperHolder {

        private volatile com.fasterxml.jackson.databind.ObjectMapper mapper;

        com.fasterxml.jackson.databind.ObjectMapper mapper() {
            com.fasterxml.jackson.databind.ObjectMapper current = mapper;
            if (current == null) {
                synchronized (this) {
                    current = mapper;
                    if (current == null) {
                        current = CacheMapper.create();
                        mapper = current;
                    }
                }
            }
            return current;
        }
    }

    /**
     * @param mode configured mode string, {@code off} disables caching
     * @param directory directory to use when enabled, {@code null} for the platform default
     * @return the store to use
     */
    public static IndexCacheStore fromMode(String mode, Path directory) {
        if (mode != null && (mode.equalsIgnoreCase("off") || mode.equalsIgnoreCase("false")
                || mode.equalsIgnoreCase("none"))) {
            return disabled();
        }
        return at(directory == null ? defaultDirectory() : directory);
    }
}

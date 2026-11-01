package io.github.springmcp.core.cache;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.StringJoiner;

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

    private static final String TMP_SUFFIX = ".tmp";

    private final Path directory;

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
            return null;
        }
        catch (IOException | RuntimeException e) {
            deleteQuietly(tmp);
            return abbreviate(String.valueOf(e.getMessage()));
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
        String key = hashHex(root.toAbsolutePath().normalize().toString());
        return directory == null ? Path.of(IndexCacheFormat.FILE_NAME) : directory.resolve(key)
            .resolve(IndexCacheFormat.FILE_NAME);
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

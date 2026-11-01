package io.github.springmcp.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

import io.github.springmcp.core.cache.IndexCacheStore;
import io.github.springmcp.core.index.CuSummary;
import io.github.springmcp.core.index.FileFingerprint;
import io.github.springmcp.core.index.ModuleLayout;
import io.github.springmcp.core.index.SourceIndex;
import io.github.springmcp.core.index.SourceWalker;
import io.github.springmcp.core.parse.AstCache;
import io.github.springmcp.core.parse.JavaParserFactory;
import io.github.springmcp.spi.ErrorCode;
import io.github.springmcp.spi.ToolFailure;
import io.github.springmcp.spi.api.CoreAnalyzer;
import io.github.springmcp.spi.api.IndexHandle;
import io.github.springmcp.spi.api.IndexOptions;
import io.github.springmcp.spi.model.IndexMetadata;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Caches one immutable index snapshot per project root and rebuilds it when the sources change.
 *
 * <p>
 * Locking is minimal by construction. Snapshot reads take no lock at all: the reference is an
 * {@link AtomicReference} and the snapshot itself is immutable. Only the first build of a root, or
 * a rebuild after a change, takes a per-root {@link ReentrantLock} with a double check, so twenty
 * concurrent tool calls on a cold cache still build the index exactly once.
 * </p>
 *
 * <p>
 * Change detection is a {@code stat} sweep compared against the fingerprints recorded at build
 * time. It is throttled, because an LLM often issues five tool calls in a row and re-walking the
 * tree for each of them would dominate the response time.
 * </p>
 */
public final class IndexRegistry implements CoreAnalyzer {

    /** How source changes are detected after the first build. */
    public enum Revalidation {

        /** Re-walk the project and compare size plus modification time. Default. */
        STAT,

        /** Rebuild after a fixed time-to-live, without touching the filesystem. */
        TTL,

        /** Never rebuild; the snapshot lives until the process exits. */
        NEVER

    }

    private static final Logger LOG = LoggerFactory.getLogger(IndexRegistry.class);

    /**
     * Minimum gap between two filesystem probes.
     *
     * <p>
     * This is the staleness window: an edit followed by a query within this period can still see the
     * previous snapshot. It was 2s, which is long enough for an edit-then-ask loop to hit regularly;
     * 1s halves the window and, because the throttle is rate based rather than count based, a burst of
     * tool calls still triggers at most one walk.
     * </p>
     */
    private static final long PROBE_THROTTLE_MILLIS = 1_000;

    private static final long DEFAULT_TTL_MILLIS = 30_000;

    private final Map<Path, Holder> holders = new ConcurrentHashMap<>();

    private final SourceWalker walker = new SourceWalker();

    private final SourceIndex.Builder builder = new SourceIndex.Builder();

    private final Revalidation revalidation;

    private final long ttlMillis;

    private final IndexCacheStore cache;

    /** Creates a registry configured from the environment, without a disk cache. */
    public IndexRegistry() {
        this(fromEnvironment(), DEFAULT_TTL_MILLIS);
    }

    /**
     * @param revalidation change detection mode
     * @param ttlMillis time-to-live used by {@link Revalidation#TTL}
     */
    public IndexRegistry(Revalidation revalidation, long ttlMillis) {
        this(revalidation, ttlMillis, IndexCacheStore.disabled());
    }

    /**
     * Builds a registry whose change detection comes from {@code SPRING_MCP_INDEX_REVALIDATE}.
     * @param cache disk cache to consult before building, {@code null} for none
     * @return the registry
     */
    public static IndexRegistry fromEnvironment(IndexCacheStore cache) {
        return new IndexRegistry(fromEnvironment(), DEFAULT_TTL_MILLIS, cache);
    }

    /**
     * @param revalidation change detection mode
     * @param ttlMillis time-to-live used by {@link Revalidation#TTL}
     * @param cache disk cache to consult before building, {@code null} for none
     */
    public IndexRegistry(Revalidation revalidation, long ttlMillis, IndexCacheStore cache) {
        this.revalidation = revalidation == null ? Revalidation.STAT : revalidation;
        this.ttlMillis = ttlMillis > 0 ? ttlMillis : DEFAULT_TTL_MILLIS;
        this.cache = cache == null ? IndexCacheStore.disabled() : cache;
    }

    private static Revalidation fromEnvironment() {
        String raw = System.getenv("SPRING_MCP_INDEX_REVALIDATE");
        if (raw == null || raw.isBlank()) {
            return Revalidation.STAT;
        }
        try {
            return Revalidation.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        }
        catch (IllegalArgumentException e) {
            return Revalidation.STAT;
        }
    }

    @Override
    public IndexHandle open(Path projectRoot, IndexOptions options) throws ToolFailure {
        if (projectRoot == null) {
            throw ToolFailure.of(ErrorCode.INVALID_ARGUMENT, "projectRoot 不能为空", null);
        }
        Path real;
        try {
            real = projectRoot.toRealPath();
        }
        catch (IOException e) {
            throw ToolFailure.of(ErrorCode.PATH_NOT_FOUND, "路径不存在: " + projectRoot,
                    "请传工程根目录的绝对路径", e);
        }
        if (!Files.isDirectory(real)) {
            throw ToolFailure.of(ErrorCode.PATH_NOT_DIRECTORY, "不是目录: " + real,
                    "projectRoot 需要指向工程根目录；若目标是单个文件，请用 get-file-dependencies", null);
        }

        Holder holder = holders.computeIfAbsent(real, Holder::new);
        SourceIndex current = holder.current.get();
        if (current == null) {
            return buildIfAbsent(holder, options);
        }
        Probe probe = probe(holder, current, options);
        if (probe.rebuild()) {
            long seenGeneration = current.metadata().generation();
            return rebuildIfUnchanged(holder, options, seenGeneration, probe.walked());
        }
        return current;
    }

    /**
     * Builds the snapshot only when nobody else already did.
     *
     * @param holder per root state
     * @param options requested options
     * @return the snapshot now current
     */
    private SourceIndex buildIfAbsent(Holder holder, IndexOptions options) throws ToolFailure {
        holder.buildLock.lock();
        try {
            SourceIndex current = holder.current.get();
            if (current != null && options.equals(current.options())) {
                return current;
            }
            return buildFresh(holder, options, null);
        }
        finally {
            holder.buildLock.unlock();
        }
    }

    /**
     * Rebuilds, unless another thread already replaced the snapshot we based the decision on.
     *
     * <p>
     * The generation check is what makes change detection correct under concurrency. Two callers can
     * both notice that a file changed; without this they would each rebuild, and — worse — a caller
     * holding a stale reference could overwrite a newer snapshot. Comparing against the generation
     * we observed means exactly one rebuild happens per detected change.
     * </p>
     *
     * @param holder per root state
     * @param options requested options
     * @param seenGeneration generation of the snapshot the decision was based on
     * @return the snapshot now current
     */
    private SourceIndex rebuildIfUnchanged(Holder holder, IndexOptions options, long seenGeneration,
            SourceWalker.WalkResult walked) throws ToolFailure {
        holder.buildLock.lock();
        try {
            SourceIndex current = holder.current.get();
            if (current != null && current.metadata().generation() != seenGeneration
                    && options.equals(current.options())) {
                return current;
            }
            return buildFresh(holder, options, walked);
        }
        finally {
            holder.buildLock.unlock();
        }
    }

    /**
     * Builds the snapshot, from disk when the cache is still valid.
     *
     * <p>
     * The walk happens here rather than inside the builder so that a cache miss does not pay for it
     * twice: the same file list decides whether the cache is current and, on a miss, feeds the parser.
     * Every cache problem is a miss, never an error.
     * </p>
     *
     * @param holder per root state
     * @param options requested options
     * @return the snapshot now current
     */
    private SourceIndex buildFresh(Holder holder, IndexOptions options, SourceWalker.WalkResult preWalked)
            throws ToolFailure {
        long generation = holder.generation.incrementAndGet();
        SourceIndex previous = holder.current.get();
        boolean reusePrevious = previous != null && options.equals(previous.options());

        // Nothing to reuse and no cache to consult: let the builder walk and parse in one pass.
        if (!cache.enabled() && !reusePrevious) {
            SourceIndex index = builder.build(holder.root, options, generation, holder.astCache);
            publish(holder, index, true);
            holder.lastReuseCount = 0;
            return index;
        }

        long startedAt = System.currentTimeMillis();
        SourceWalker.WalkResult walked = preWalked != null ? preWalked : walkQuietly(holder.root, options);
        if (walked == null) {
            // The walk itself failed (too many files, unreadable tree); let the builder raise the
            // proper ToolFailure, which carries the error code and the hint.
            SourceIndex index = builder.build(holder.root, options, generation, holder.astCache);
            publish(holder, index, true);
            return index;
        }

        List<CuSummary> reusable = new ArrayList<>();
        // With a live snapshot in hand, the file on disk cannot be newer and can only repeat what the
        // snapshot already holds — so skip reading 36 MB to learn nothing.
        if (cache.enabled() && !reusePrevious) {
            IndexCacheStore.Load load = cache.load(holder.root, options, walked.files());
            if (load.hit()) {
                SourceIndex restored = restore(holder, options, generation, load, startedAt);
                LOG.info("索引命中磁盘缓存: {} ({} 个文件)", holder.root, load.summaries().size());
                return restored;
            }
            LOG.debug("索引缓存未命中: {} ({})", holder.root, load.reason());
            // Stale, but still per-file: parsing only what changed is the difference between 8s and 1s.
            reusable.addAll(load.reusable());
        }
        if (reusePrevious) {
            // The in-memory snapshot is newer than the file on disk, so it goes last and wins.
            reusable.addAll(previous.summariesByFile().values());
        }

        SourceIndex index = builder.build(holder.root, options, generation, holder.astCache, walked, reusable);
        publish(holder, index, true);
        int reused = countDistinctReuse(reusable, index);
        holder.lastReuseCount = reused;
        if (reused > 0) {
            LOG.info("增量索引: {} 复用 {} 个摘要，解析 {} 个文件", holder.root, reused,
                    Math.max(0, index.metadata().fileCount() - reused));
        }
        if (cache.enabled()) {
            String failure = cache.save(holder.root, options, index);
            if (failure != null) {
                // Not fatal — the next run just rebuilds — but silent only looks like "the cache does
                // nothing", which is impossible to diagnose from the outside.
                LOG.warn("索引缓存写入失败，下次将重建: {} ({})", holder.root, failure);
            }
        }
        return index;
    }

    /**
     * Counts how many of the reusable summaries actually ended up in the snapshot.
     *
     * <p>
     * The build already reports the reuse rate in its own warning; this exists only so the log line
     * does not claim more reuse than happened when the two sources overlapped.
     * </p>
     */
    private static int countDistinctReuse(List<CuSummary> reusable, SourceIndex index) {
        int matched = 0;
        for (CuSummary summary : reusable) {
            CuSummary stored = index.summariesByFile().get(summary.file());
            if (stored == summary) {
                matched++;
            }
        }
        return matched;
    }

    /**
     * Installs a snapshot as the current one.
     *
     * @param holder per root state
     * @param index snapshot to install
     * @param parsed whether this snapshot came from parsing, as opposed to the disk cache; the build
     * counter only tracks real builds, which is what lets a test prove the cache was used
     */
    private void publish(Holder holder, SourceIndex index, boolean parsed) {
        holder.current.set(index);
        holder.lastProbeMillis = System.currentTimeMillis();
        if (parsed) {
            holder.buildCount.incrementAndGet();
        }
    }

    /**
     * Rebuilds a snapshot from cached summaries.
     *
     * <p>
     * The metadata is re-created rather than replayed verbatim: the generation must be the one this
     * process just allocated, and the timing fields must describe this load, not the build that
     * happened yesterday. Problems and warnings are replayed, because those change what a caller is
     * told about the completeness of the answer.
     * </p>
     *
     * @param holder per root state
     * @param options requested options
     * @param generation generation allocated for this snapshot
     * @param load cache payload
     * @param startedAt when this load started
     * @return the restored snapshot, already published
     */
    private SourceIndex restore(Holder holder, IndexOptions options, long generation, IndexCacheStore.Load load,
            long startedAt) {
        ModuleLayout layout = ModuleLayout.detect(holder.root);
        int typeCount = load.summaries().stream().mapToInt(summary -> summary.types().size()).sum();
        IndexMetadata metadata = new IndexMetadata(holder.root.toString(), generation, load.summaries().size(),
                typeCount, startedAt, System.currentTimeMillis() - startedAt, load.problems(), load.warnings());
        SourceIndex index = new SourceIndex(holder.root, metadata, options, layout, load.summaries(), holder.astCache);
        publish(holder, index, false);
        return index;
    }

    /**
     * Walks, treating any failure as "cannot use the cache".
     * @param root project root
     * @param options build options
     * @return the walk result, or {@code null} when walking failed
     */
    private SourceWalker.WalkResult walkQuietly(Path root, IndexOptions options) {
        try {
            return walker.walk(root, options);
        }
        catch (IOException | ToolFailure e) {
            return null;
        }
    }

    /**
     * Decides whether the snapshot is stale, and keeps the walk it performed to decide.
     *
     * <p>
     * Returning the walk matters: a rebuild needs the same file list a moment later, and on Windows
     * one walk of an 8 000 file tree costs ~0.75 s. Re-walking inside the builder doubled the cost of
     * every rebuild for no added information.
     * </p>
     *
     * @param holder per root state
     * @param current snapshot in hand
     * @param options requested options
     * @return the decision plus the file list, when one was produced
     */
    private Probe probe(Holder holder, SourceIndex current, IndexOptions options) {
        if (!options.equals(current.options())) {
            return new Probe(true, null);
        }
        if (revalidation == Revalidation.NEVER) {
            return new Probe(false, null);
        }
        long now = System.currentTimeMillis();
        long sinceProbe = now - holder.lastProbeMillis;
        if (sinceProbe < PROBE_THROTTLE_MILLIS) {
            return new Probe(false, null);
        }
        if (revalidation == Revalidation.TTL) {
            return new Probe(sinceProbe >= ttlMillis, null);
        }

        // STAT: a cheap sweep, compared against the recorded fingerprints.
        holder.lastProbeMillis = now;
        SourceWalker.WalkResult walked;
        try {
            walked = walker.walk(holder.root, options);
        }
        catch (IOException | ToolFailure e) {
            // Failing to probe is not a reason to fail the tool call; keep the current snapshot.
            return new Probe(false, null);
        }
        List<FileFingerprint> now2 = walked.files();
        List<FileFingerprint> before = current.fingerprints();
        if (now2.size() != before.size()) {
            return new Probe(true, walked);
        }
        for (int i = 0; i < now2.size(); i++) {
            FileFingerprint a = now2.get(i);
            FileFingerprint b = before.get(i);
            if (!a.path().equals(b.path()) || a.size() != b.size() || a.lastModifiedMillis() != b.lastModifiedMillis()) {
                return new Probe(true, walked);
            }
        }
        return new Probe(false, walked);
    }

    /**
     * Outcome of a staleness probe.
     *
     * @param rebuild whether a rebuild is needed
     * @param walked file list produced while probing, {@code null} when no walk happened
     */
    private record Probe(boolean rebuild, SourceWalker.WalkResult walked) {
    }

    @Override
    public String readSource(Path file) throws ToolFailure {
        if (file == null) {
            throw ToolFailure.of(ErrorCode.INVALID_ARGUMENT, "文件路径不能为空", null);
        }
        if (!Files.isRegularFile(file)) {
            throw ToolFailure.of(ErrorCode.PATH_NOT_FOUND, "文件不存在: " + file, null);
        }
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        }
        catch (IOException e) {
            throw ToolFailure.of(ErrorCode.PATH_NOT_READABLE, "读取文件失败: " + file, null, e);
        }
    }

    @Override
    public void invalidateAll() {
        for (Holder h : holders.values()) {
            h.current.set(null);
            h.astCache.clear();
        }
    }

    /**
     * Number of files whose summaries came from an earlier build during the last rebuild of a root.
     *
     * <p>
     * Exposed for the same reason as {@link #buildCount(Path)}: the incremental path only differs by
     * what it skips, so a test has to observe the skip directly rather than infer it from timing.
     * </p>
     *
     * @param projectRoot root to inspect
     * @return reused file count of the last build, {@code 0} when nothing was reused
     */
    public int reusedFileCount(Path projectRoot) {
        try {
            Holder holder = holders.get(projectRoot.toRealPath());
            return holder == null ? 0 : holder.lastReuseCount;
        }
        catch (IOException e) {
            return 0;
        }
    }

    /**
     * Number of times the index of a root was actually built. Used by tests to prove the cache
     * works.
     * @param projectRoot root to inspect
     * @return build count, {@code 0} when the root was never opened
     */
    public int buildCount(Path projectRoot) {
        try {
            Holder h = holders.get(projectRoot.toRealPath());
            return h == null ? 0 : h.buildCount.get();
        }
        catch (IOException e) {
            return 0;
        }
    }

    /** Per-root state. */
    private static final class Holder {

        private final Path root;

        private final ReentrantLock buildLock = new ReentrantLock();

        private final AtomicReference<SourceIndex> current = new AtomicReference<>();

        private final AtomicLong generation = new AtomicLong();

        private final AtomicInteger buildCount = new AtomicInteger();

        private final AstCache astCache = new AstCache(AstCache.DEFAULT_CAPACITY, new JavaParserFactory());

        private volatile long lastProbeMillis = System.currentTimeMillis();

        private volatile int lastReuseCount;

        private Holder(Path root) {
            this.root = root;
        }
    }
}

package io.github.springmcp.core.parse;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import com.github.javaparser.ast.CompilationUnit;

/**
 * Bounded LRU cache of parsed compilation units.
 *
 * <p>
 * Only one of the five tools needs method bodies ({@code find-service-callers}); the other four
 * answer from the lightweight summaries. Keeping every AST resident would cost hundreds of
 * megabytes on a mid-sized repository, so they are cached here with a hard cap and can always be
 * re-parsed.
 * </p>
 *
 * <p>
 * The cache is deliberately <em>not</em> part of the immutable index snapshot: throwing it away
 * never changes an answer, it only makes the next call slower.
 * </p>
 */
public final class AstCache {

    /** Default number of compilation units kept in memory. */
    public static final int DEFAULT_CAPACITY = 512;

    private final int capacity;

    private final JavaParserFactory parser;

    private final Map<Path, Entry> lru;

    /**
     * @param capacity maximum number of compilation units retained
     * @param parser parser used on a miss
     */
    public AstCache(int capacity, JavaParserFactory parser) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
        this.parser = parser;
        this.lru = Collections.synchronizedMap(new LinkedHashMap<>(64, 0.75f, true) {
            private static final long serialVersionUID = 1L;

            @Override
            protected boolean removeEldestEntry(Map.Entry<Path, Entry> eldest) {
                return size() > AstCache.this.capacity;
            }
        });
    }

    /**
     * Returns the AST of a file, parsing it if necessary.
     * @param file source file
     * @param lastModifiedMillis current modification time of the file, used to detect staleness
     * @return the compilation unit, or {@code null} when the file could not be parsed at all
     * @throws IOException when the file cannot be read
     */
    public CompilationUnit obtain(Path file, long lastModifiedMillis) throws IOException {
        Entry cached = lru.get(file);
        if (cached != null && cached.lastModifiedMillis == lastModifiedMillis) {
            return cached.unit;
        }
        ParseOutcome outcome = parser.parse(file);
        if (outcome.unit() == null) {
            // 缓存失败结果，避免同一个坏文件在一次会话里被反复解析。
            lru.put(file, new Entry(lastModifiedMillis, null));
            return null;
        }
        lru.put(file, new Entry(lastModifiedMillis, outcome.unit()));
        return outcome.unit();
    }

    /** Discards every cached unit. */
    public void clear() {
        lru.clear();
    }

    private record Entry(long lastModifiedMillis, CompilationUnit unit) {
    }
}

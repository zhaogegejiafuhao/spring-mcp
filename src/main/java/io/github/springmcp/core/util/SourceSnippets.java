package io.github.springmcp.core.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Extracts the source line of a finding, without keeping whole files in the index.
 *
 * <p>
 * Snippets are only produced for files that actually contain a hit, which is a small subset of the
 * project, so a modest LRU of file line-arrays is enough and avoids re-reading the same file once
 * per call site.
 * </p>
 */
public final class SourceSnippets {

    private static final int CACHE_CAPACITY = 64;

    private static final int MAX_SNIPPET_LENGTH = 200;

    private final Map<Path, List<String>> cache;

    public SourceSnippets() {
        this.cache = Collections.synchronizedMap(new LinkedHashMap<>(32, 0.75f, true) {
            private static final long serialVersionUID = 1L;

            @Override
            protected boolean removeEldestEntry(Map.Entry<Path, List<String>> eldest) {
                return size() > CACHE_CAPACITY;
            }
        });
    }

    /**
     * Returns the trimmed source line at a 1-based line number.
     * @param file source file
     * @param line 1-based line number; {@code <= 0} yields an empty string
     * @return the line, truncated to 200 characters; empty when unavailable
     */
    public String snippet(Path file, int line) {
        if (file == null || line <= 0) {
            return "";
        }
        List<String> lines = cache.get(file);
        if (lines == null) {
            try {
                lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            }
            catch (IOException e) {
                // 读不到就把空快照放进缓存，避免每次调用都重试同一个坏文件。
                lines = List.of();
            }
            cache.put(file, lines);
        }
        if (line > lines.size()) {
            return "";
        }
        return truncate(lines.get(line - 1).trim());
    }

    /**
     * Truncates a string to the snippet limit.
     * @param value input
     * @return the input, cut with an ellipsis when too long
     */
    public static String truncate(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= MAX_SNIPPET_LENGTH ? value : value.substring(0, MAX_SNIPPET_LENGTH) + "...";
    }

    /** Drops cached content. */
    public void clear() {
        cache.clear();
    }
}

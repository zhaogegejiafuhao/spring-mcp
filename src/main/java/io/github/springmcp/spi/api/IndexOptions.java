package io.github.springmcp.spi.api;

import java.time.Duration;
import java.util.List;

/**
 * Tuning knobs for building a source index.
 *
 * <p>
 * Defaults are chosen for a typical Spring Boot repository (a few hundred to a few thousand
 * files): include production sources, skip build output and VCS metadata, cap a single file at
 * 1 MiB and the whole project at 20 000 files.
 * </p>
 *
 * @param includeTests also index {@code src/test/java}
 * @param includeGenerated also index paths that look generated ({@code generated-sources},
 * {@code *Generated.java})
 * @param extraExcludes additional directory names or path prefixes to skip; empty by default
 * @param maxFileBytes skip individual files larger than this
 * @param maxIndexedFiles abort indexing when more files than this are found, rather than
 * silently producing a partial and misleading index
 * @param maxBuildTime budget for a full build; exceeding it fails with {@code TIMEOUT}
 */
public record IndexOptions(boolean includeTests, boolean includeGenerated, List<String> extraExcludes,
        long maxFileBytes, int maxIndexedFiles, Duration maxBuildTime) {

    public IndexOptions {
        extraExcludes = extraExcludes == null ? List.of() : List.copyOf(extraExcludes);
        if (maxFileBytes <= 0) {
            throw new IllegalArgumentException("maxFileBytes must be positive");
        }
        if (maxIndexedFiles <= 0) {
            throw new IllegalArgumentException("maxIndexedFiles must be positive");
        }
        if (maxBuildTime == null || maxBuildTime.isNegative() || maxBuildTime.isZero()) {
            throw new IllegalArgumentException("maxBuildTime must be positive");
        }
    }

    /**
     * Production defaults.
     * @return options suitable for most projects
     */
    public static IndexOptions defaults() {
        return new IndexOptions(false, false, List.of(), 1024L * 1024L, 20_000, Duration.ofSeconds(60));
    }

    /**
     * Copy with a different test-inclusion flag.
     * @param value whether to include test sources
     * @return new options
     */
    public IndexOptions withIncludeTests(boolean value) {
        return new IndexOptions(value, includeGenerated, extraExcludes, maxFileBytes, maxIndexedFiles, maxBuildTime);
    }

    /**
     * Copy with a different file cap.
     * @param value maximum number of files to index, must be positive
     * @return new options
     */
    public IndexOptions withMaxIndexedFiles(int value) {
        return new IndexOptions(includeTests, includeGenerated, extraExcludes, maxFileBytes, value, maxBuildTime);
    }

    /**
     * Copy with extra exclusions appended.
     * @param more additional directory names or path prefixes
     * @return new options
     */
    public IndexOptions withExtraExcludes(List<String> more) {
        if (more == null || more.isEmpty()) {
            return this;
        }
        List<String> merged = new java.util.ArrayList<>(extraExcludes);
        merged.addAll(more);
        return new IndexOptions(includeTests, includeGenerated, merged, maxFileBytes, maxIndexedFiles, maxBuildTime);
    }
}

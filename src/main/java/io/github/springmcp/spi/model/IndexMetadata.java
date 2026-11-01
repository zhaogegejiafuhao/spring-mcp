package io.github.springmcp.spi.model;

import java.util.List;

/**
 * Metadata describing an immutable source index snapshot.
 *
 * @param root absolute project root the snapshot was built from
 * @param generation monotonically increasing id, bumped on every rebuild; lets a client tell
 * two answers apart after the sources changed
 * @param fileCount number of {@code .java} files that were parsed
 * @param typeCount number of top-level and nested type declarations found
 * @param builtAtEpochMillis build start time in epoch milliseconds
 * @param buildDurationMs wall clock duration of the build
 * @param problems files that failed to parse or were skipped, with the reason
 * @param warnings non-fatal notes collected while indexing
 */
public record IndexMetadata(String root, long generation, int fileCount, int typeCount, long builtAtEpochMillis,
        long buildDurationMs, List<ParseProblem> problems, List<String> warnings) {

    public IndexMetadata {
        problems = problems == null ? List.of() : List.copyOf(problems);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    /**
     * A file that produced diagnostics.
     *
     * @param file absolute path
     * @param reason {@code "syntax"}, {@code "too-large"}, {@code "unreadable"} ...
     * @param detail parser message or exception text
     */
    public record ParseProblem(String file, String reason, String detail) {
    }
}

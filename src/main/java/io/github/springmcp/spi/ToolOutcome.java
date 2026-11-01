package io.github.springmcp.spi;

import java.util.List;

/**
 * Result of a single tool invocation.
 *
 * <p>
 * A tool never returns a protocol object; it returns either a success payload or a
 * {@link ToolFailure}. The server is solely responsible for translating this into an MCP
 * {@code CallToolResult}.
 * </p>
 */
public sealed interface ToolOutcome permits ToolOutcome.Success, ToolOutcome.Failure {

    /**
     * Successful analysis.
     *
     * @param structured domain specific result object. The server serialises it into
     * {@code structuredContent}. If it is not already a {@code Map}, the server converts it to
     * one, because MCP requires {@code structuredContent} to be a JSON object. Prefer records
     * or maps; avoid {@code null} — pass an empty structure instead.
     * @param humanSummary one-paragraph text the LLM reads first. Keep it short: the full
     * result lives in {@code structured}.
     * @param warnings non-fatal issues (unparsable files, unresolved literals). Never
     * {@code null}; use {@link List#of()} when empty.
     */
    record Success(Object structured, String humanSummary, List<String> warnings) implements ToolOutcome {

        public Success {
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }
    }

    /** Failed analysis. */
    record Failure(ToolFailure failure) implements ToolOutcome {

        public Failure {
            if (failure == null) {
                throw new IllegalArgumentException("failure must not be null");
            }
        }
    }

    /**
     * Convenience factory for a success without warnings.
     * @param structured result payload
     * @param humanSummary short text summary
     * @return outcome
     */
    static ToolOutcome ok(Object structured, String humanSummary) {
        return new Success(structured, humanSummary, List.of());
    }

    /**
     * Convenience factory for a success carrying warnings.
     * @param structured result payload
     * @param humanSummary short text summary
     * @param warnings non-fatal issues
     * @return outcome
     */
    static ToolOutcome ok(Object structured, String humanSummary, List<String> warnings) {
        return new Success(structured, humanSummary, warnings);
    }

    /**
     * Convenience factory for a failure.
     * @param failure the failure
     * @return outcome
     */
    static ToolOutcome fail(ToolFailure failure) {
        return new Failure(failure);
    }
}

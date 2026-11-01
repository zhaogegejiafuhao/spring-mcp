package io.github.springmcp.core.parse;

import java.util.List;

import com.github.javaparser.ast.CompilationUnit;

/**
 * Outcome of parsing one source file.
 *
 * <p>
 * Three-state on purpose. JavaParser returns a partial AST even when a file has syntax errors,
 * and a partial AST is still useful: we would rather report five of six controller methods than
 * fail the whole scan because somebody left a brace open. {@link #problems()} records what went
 * wrong so the tool result can say so.
 * </p>
 *
 * @param unit the parsed unit, or {@code null} when nothing usable came back
 * @param problems human readable parser diagnostics, empty when clean
 * @param clean {@code true} when the parser reported no problems
 * @param source how the file was decoded, {@code null} when the caller supplied the text itself
 */
public record ParseOutcome(CompilationUnit unit, List<String> problems, boolean clean, SourceText source) {

    public ParseOutcome {
        problems = problems == null ? List.of() : List.copyOf(problems);
    }

    /**
     * @param unit the parsed unit
     * @param problems parser diagnostics
     * @param clean whether the parser reported no problems
     */
    public ParseOutcome(CompilationUnit unit, List<String> problems, boolean clean) {
        this(unit, problems, clean, null);
    }

    /** @return whether a usable AST is present */
    public boolean hasUnit() {
        return unit != null;
    }
}

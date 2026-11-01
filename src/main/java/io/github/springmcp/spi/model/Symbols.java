package io.github.springmcp.spi.model;

import java.util.List;
import java.util.Set;

/**
 * Type declarations found by name.
 *
 * <p>
 * Answers "where does class X live", which is the most common navigation question and the one an
 * agent otherwise answers with a full-text search that also matches comments, strings and unrelated
 * identifiers. Everything here comes from parsed declarations, so a hit is a declaration.
 * </p>
 */
public final class Symbols {

    private Symbols() {
    }

    /**
     * What to look for.
     *
     * @param name full or partial simple name, or a fully qualified fragment; matching is
     * case-insensitive
     * @param kinds restrict to these kinds ({@code CLASS}, {@code INTERFACE}, {@code ENUM},
     * {@code RECORD}, {@code ANNOTATION}); empty means all
     * @param limit maximum number of symbols returned
     */
    public record Query(String name, Set<String> kinds, int limit) {

        public Query {
            kinds = kinds == null ? Set.of() : Set.copyOf(kinds);
        }
    }

    /**
     * One declaration.
     *
     * @param typeFqn fully qualified name, nesting included
     * @param simpleName simple name
     * @param packageName declaring package, empty for the default package
     * @param kind declaration kind
     * @param abstractType whether the declaration is abstract
     * @param annotations simple names of the annotations on it, sorted
     * @param file absolute source path
     * @param line line of the declaration
     */
    public record Symbol(String typeFqn, String simpleName, String packageName, String kind, boolean abstractType,
            List<String> annotations, String file, int line) {

        public Symbol {
            annotations = annotations == null ? List.of() : List.copyOf(annotations);
        }
    }

    /**
     * @param symbols matches, best first (exact name, then prefix, then substring)
     * @param total matches before the limit was applied
     * @param truncated whether {@code limit} cut the list short
     * @param warnings non-fatal notes, e.g. a query that matched far more than it returned
     */
    public record Report(List<Symbol> symbols, int total, boolean truncated, List<String> warnings) {

        public Report {
            symbols = symbols == null ? List.of() : List.copyOf(symbols);
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }
    }
}

package io.github.springmcp.spi.model;

import java.util.List;
import java.util.Set;

/**
 * Search for elements carrying a given annotation.
 */
public final class Annotations {

    private Annotations() {
    }

    /** Where an annotation was found. */
    public enum ElementKind {

        /** Class, interface, enum or record declaration. */
        TYPE,

        /** A method declaration, including interface methods. */
        METHOD,

        /** A constructor declaration. */
        CONSTRUCTOR,

        /** A field declaration. */
        FIELD,

        /** A method or constructor parameter. */
        PARAMETER
    }

    /**
     * @param annotation fully qualified name or simple name, without the leading {@code @}
     * @param elementKinds restrict to these kinds; empty means all
     * @param strictFqn when {@code true} only an exact fully qualified match counts; when
     * {@code false} a simple name also matches (reported in the warnings)
     * @param limit hard upper bound on hits
     */
    public record Query(String annotation, Set<ElementKind> elementKinds, boolean strictFqn, int limit) {

        public Query {
            elementKinds = elementKinds == null ? Set.of() : Set.copyOf(elementKinds);
        }
    }

    /**
     * One annotated element.
     *
     * @param annotationFqn annotation as written, normalised by stripping {@code @}
     * @param annotationSimpleName simple name of that annotation
     * @param elementKind what kind of element carries it
     * @param ownerType fully qualified type owning the element (the type itself for
     * {@link ElementKind#TYPE})
     * @param memberName method/field/parameter name, {@code null} for types
     * @param signature readable signature of the member, may be {@code null}
     * @param file absolute source path
     * @param line 1-based line of the element declaration
     * @param column 1-based column of the element declaration
     */
    public record Hit(String annotationFqn, String annotationSimpleName, ElementKind elementKind, String ownerType,
            String memberName, String signature, String file, int line, int column) {
    }

    /**
     * @param hits matching elements, sorted by file then line
     * @param total number of matches before {@code limit} was applied
     * @param truncated whether {@code limit} cut the result short
     * @param warnings non-fatal notes
     */
    public record Report(List<Hit> hits, int total, boolean truncated, List<String> warnings) {

        public Report {
            hits = hits == null ? List.of() : List.copyOf(hits);
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }
    }
}

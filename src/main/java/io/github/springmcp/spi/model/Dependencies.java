package io.github.springmcp.spi.model;

import java.util.List;

/**
 * Dependencies of a single source file: what it uses, and what uses it.
 */
public final class Dependencies {

    private Dependencies() {
    }

    /** How the edge was discovered. */
    public enum Kind {

        /** A regular {@code import a.b.C;}. */
        IMPORT,

        /** {@code import static a.b.C.member;}. */
        STATIC_IMPORT,

        /** {@code import a.b.*;}. */
        WILDCARD_IMPORT,

        /** The type is referenced without an import because it lives in the same package. */
        SAME_PACKAGE_USAGE,

        /** A type name appears in a field, parameter, return, local, {@code new} or cast. */
        TYPE_USAGE
    }

    /**
     * A directed dependency edge.
     *
     * @param targetType fully qualified target, or the raw text when it could not be resolved
     * @param kind how the edge was found
     * @param internal {@code true} when the target lives inside the analysed project
     * @param fromFile absolute path of the depending file, {@code null} if unknown
     * @param toFile absolute path of the depended-on file, {@code null} when external
     * @param line 1-based line of the reference, {@code 0} when unknown
     */
    public record Edge(String targetType, Kind kind, boolean internal, String fromFile, String toFile, int line) {
    }

    /**
     * @param file absolute path of the analysed file
     * @param typeFqn the primary type declared in that file
     * @param packageName package of the file
     * @param outgoing types this file depends on
     * @param incoming files in the project that depend on this file's types
     * @param samePackageSiblings other files in the same package, which can couple implicitly
     * @param warnings non-fatal notes
     */
    public record Report(String file, String typeFqn, String packageName, List<Edge> outgoing, List<Edge> incoming,
            List<String> samePackageSiblings, List<String> warnings) {

        public Report {
            outgoing = outgoing == null ? List.of() : List.copyOf(outgoing);
            incoming = incoming == null ? List.of() : List.copyOf(incoming);
            samePackageSiblings = samePackageSiblings == null ? List.of() : List.copyOf(samePackageSiblings);
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }
    }
}

package io.github.springmcp.spi.model;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Collections;
import java.util.LinkedHashMap;

/**
 * Persistence layer interfaces: MyBatis annotation mappers, MyBatis XML mappers and JPA repositories.
 */
public final class Mappers {

    private Mappers() {
    }

    /** Origin of a mapper declaration. */
    public enum Kind {

        /** An interface annotated with {@code @Mapper}. */
        ANNOTATION,

        /** An XML file with a {@code <mapper namespace="...">} root. */
        XML,

        /** An interface extending a Spring Data repository. */
        JPA
    }

    /**
     * @param kinds which sources to include; empty means all
     * @param nameFilter case-insensitive substring match on the interface simple name or XML
     * file name; {@code null} means no filtering
     */
    public record Query(Set<Kind> kinds, String nameFilter) {

        public Query {
            kinds = kinds == null ? Set.of() : Set.copyOf(kinds);
        }
    }

    /**
     * One SQL statement or reusable fragment.
     *
     * @param id MyBatis statement id ({@code namespace.method}) or fragment id
     * @param commandType {@code SELECT}, {@code INSERT}, {@code UPDATE}, {@code DELETE},
     * {@code UNKNOWN} for XML, or the annotation name for interfaces
     * @param name short statement name
     * @param parameterType declared parameter type as written, may be {@code null}
     * @param resultType declared result type as written, may be {@code null}
     * @param includes {@code refid}s pulled in via {@code <include>}
     * @param fragment {@code true} when this is a {@code <sql>} fragment rather than a statement
     * @param line 1-based line in the declaring file, {@code 0} when unknown
     */
    public record Statement(String id, String commandType, String name, String parameterType, String resultType,
            List<String> includes, boolean fragment, int line) {

        public Statement {
            includes = includes == null ? List.of() : List.copyOf(includes);
        }
    }

    /**
     * A mapper, possibly backed by both an interface and an XML file.
     *
     * @param kind primary origin
     * @param interfaceFqn fully qualified interface name, {@code null} for XML-only mappers
     * @param simpleName interface simple name or XML file base name
     * @param xmlPath absolute path of the XML file, {@code null} for interface-only mappers
     * @param entityType JPA entity type as written, {@code null} otherwise
     * @param idType JPA id type as written, {@code null} otherwise
     * @param declaredMethods method names declared on the interface
     * @param statements SQL statements attributable to this mapper
     * @param issues non-fatal problems, e.g. an unreadable XML file
     */
    public record Descriptor(Kind kind, String interfaceFqn, String simpleName, String xmlPath, String entityType,
            String idType, List<String> declaredMethods, List<Statement> statements, List<String> issues) {

        public Descriptor {
            declaredMethods = declaredMethods == null ? List.of() : List.copyOf(declaredMethods);
            statements = statements == null ? List.of() : List.copyOf(statements);
            issues = issues == null ? List.of() : List.copyOf(issues);
        }
    }

    /**
     * @param mappers discovered mappers, sorted by simple name
     * @param orphans XML files whose {@code namespace} matches no interface in the project
     * @param counts number of mappers per {@link Kind}
     * @param warnings non-fatal notes
     */
    public record Report(List<Descriptor> mappers, List<String> orphans, Map<String, Integer> counts,
            List<String> warnings) {

        public Report {
            mappers = mappers == null ? List.of() : List.copyOf(mappers);
            orphans = orphans == null ? List.of() : List.copyOf(orphans);
            // Not Map.copyOf: its iteration order is randomised per JVM, and this map is serialised
            // into the tool output. Two runs of the same analysis then produced different bytes, which
            // breaks diffing, the golden baseline and anyone caching the response.
            counts = counts == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(counts));
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }
    }
}

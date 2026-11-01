package io.github.springmcp.spi.model;

import java.util.List;

/**
 * Which types implement a given interface or extend a given class.
 *
 * <p>
 * Answers the second most common question in a Spring code base after "where is this endpoint":
 * "this interface has how many implementations, and which of them is a bean". {@code find-service-callers}
 * answers the opposite direction (who uses it); neither is a substitute for the other.
 * </p>
 */
public final class TypeHierarchy {

    private TypeHierarchy() {
    }

    /**
     * What to look for.
     *
     * @param type interface or class, simple name or fully qualified
     * @param includeAbstract also report abstract classes that implement it; default behaviour is to
     * report them separately, because an abstract base is not a bean and answering "three
     * implementations" when one of them cannot be instantiated is misleading
     * @param includeInterfaces also report sub-interfaces
     */
    public record Query(String type, boolean includeAbstract, boolean includeInterfaces) {
    }

    /**
     * One subtype.
     *
     * @param typeFqn fully qualified name
     * @param simpleName simple name
     * @param kind {@code CLASS}, {@code INTERFACE}, {@code ENUM}, {@code RECORD}, {@code ANNOTATION}
     * @param abstractType whether the subtype itself is abstract
     * @param springStereotypes Spring stereotype annotations directly on it, e.g. {@code ["Service"]};
     * empty when it is not obviously a bean
     * @param direct whether it declares the target in its own {@code extends}/{@code implements}
     * clause, as opposed to inheriting it through another type
     * @param file absolute source path
     * @param line line of the declaration
     */
    public record Implementation(String typeFqn, String simpleName, String kind, boolean abstractType,
            List<String> springStereotypes, boolean direct, String file, int line) {

        public Implementation {
            springStereotypes = springStereotypes == null ? List.of() : List.copyOf(springStereotypes);
        }
    }

    /**
     * @param targetFqn resolved type that was searched for
     * @param targetKind kind of the target
     * @param targetAbstract whether the target is abstract
     * @param implementations subtypes, sorted by name
     * @param count number of implementations returned
     * @param warnings non-fatal notes, e.g. a class with no subtypes at all
     */
    public record Report(String targetFqn, String targetKind, boolean targetAbstract,
            List<Implementation> implementations, int count, List<String> warnings) {

        public Report {
            implementations = implementations == null ? List.of() : List.copyOf(implementations);
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }
    }
}

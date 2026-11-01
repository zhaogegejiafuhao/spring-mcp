package io.github.springmcp.core.index;

import java.nio.file.Path;
import java.util.List;

/**
 * A type declaration, flattened.
 *
 * @param fqn fully qualified name using dots for nesting ({@code com.acme.Outer.Inner})
 * @param simpleName simple name of this declaration
 * @param packageName declaring package, empty for the default package
 * @param kind what kind of declaration this is
 * @param abstractType whether the declaration is abstract; reported because an abstract class cannot
 * be instantiated as a bean, so "which classes implement this interface" has a different answer
 * depending on whether bases are counted
 * @param typeParameters declared type parameter names in order, e.g. {@code ["T", "ID"]}; needed to
 * substitute arguments when an entity type is inherited through an intermediate interface
 * @param rawSuperTypes {@code extends} and {@code implements} clauses as written, generics
 * included and un-resolved
 * @param annotations annotations on the type
 * @param fields declared fields
 * @param methods declared methods and constructors
 * @param file source file containing the declaration
 * @param line 1-based line of the declaration
 * @param column 1-based column of the declaration
 */
public record TypeSummary(String fqn, String simpleName, String packageName, TypeKind kind, boolean abstractType,
        List<String> typeParameters, List<String> rawSuperTypes, List<AnnotationUse> annotations,
        List<FieldSummary> fields, List<MethodSummary> methods, Path file, int line, int column) {

    public TypeSummary {
        typeParameters = typeParameters == null ? List.of() : List.copyOf(typeParameters);
        rawSuperTypes = rawSuperTypes == null ? List.of() : List.copyOf(rawSuperTypes);
        annotations = annotations == null ? List.of() : List.copyOf(annotations);
        fields = fields == null ? List.of() : List.copyOf(fields);
        methods = methods == null ? List.of() : List.copyOf(methods);
    }

    /**
     * @param simpleOrFqn annotation simple name or fully qualified name
     * @return whether the type carries the annotation (simple-name comparison)
     */
    public boolean hasAnnotation(String simpleOrFqn) {
        String simple = simpleOrFqn.substring(simpleOrFqn.lastIndexOf('.') + 1);
        for (AnnotationUse a : annotations) {
            if (a.name().equals(simpleOrFqn) || a.simpleName().equals(simple)) {
                return true;
            }
        }
        return false;
    }

    /**
     * @param simpleOrFqn annotation simple name or fully qualified name
     * @return the annotation usage, or {@code null}
     */
    public AnnotationUse annotation(String simpleOrFqn) {
        String simple = simpleOrFqn.substring(simpleOrFqn.lastIndexOf('.') + 1);
        for (AnnotationUse a : annotations) {
            if (a.name().equals(simpleOrFqn) || a.simpleName().equals(simple)) {
                return a;
            }
        }
        return null;
    }

    /**
     * Finds a declared field by name.
     * @param name field name
     * @return the field, or {@code null}
     */
    public FieldSummary field(String name) {
        for (FieldSummary f : fields) {
            if (f.name().equals(name)) {
                return f;
            }
        }
        return null;
    }

    /**
     * Finds the first declared method with the given name.
     * @param name method name
     * @return the method, or {@code null}
     */
    public MethodSummary method(String name) {
        for (MethodSummary m : methods) {
            if (!m.constructor() && m.name().equals(name)) {
                return m;
            }
        }
        return null;
    }

    /** @return declared constructors */
    public List<MethodSummary> constructors() {
        return methods.stream().filter(MethodSummary::constructor).toList();
    }

    /** @return declared non-constructor methods */
    public List<MethodSummary> plainMethods() {
        return methods.stream().filter(m -> !m.constructor()).toList();
    }
}

package io.github.springmcp.core.index;

import java.util.List;
import java.util.StringJoiner;

/**
 * A method or constructor declaration.
 *
 * @param name method name; for constructors the simple type name
 * @param returnTypeRaw declared return type as written, {@code null} for constructors
 * @param parameters declared parameters in order
 * @param annotations annotations on the method
 * @param constructor whether this is a constructor
 * @param isPublic whether the declaration is {@code public}
 * @param isStatic whether the method is {@code static}
 * @param line 1-based line of the declaration
 * @param column 1-based column of the declaration
 */
public record MethodSummary(String name, String returnTypeRaw, List<ParameterSummary> parameters,
        List<AnnotationUse> annotations, boolean constructor, boolean isPublic, boolean isStatic, int line,
        int column) {

    public MethodSummary {
        parameters = parameters == null ? List.of() : List.copyOf(parameters);
        annotations = annotations == null ? List.of() : List.copyOf(annotations);
    }

    /** @return number of declared parameters */
    public int arity() {
        return parameters.size();
    }

    /**
     * @param simpleOrFqn annotation simple name or fully qualified name
     * @return whether the annotation is present (simple-name comparison)
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
     * Looks up an annotation by simple name.
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

    /** @return human readable signature, e.g. {@code "create(CreateOrderCommand) : Order"} */
    public String signature() {
        StringJoiner params = new StringJoiner(", ");
        for (ParameterSummary p : parameters) {
            params.add(p.typeRaw());
        }
        String base = name + "(" + params + ")";
        return constructor || returnTypeRaw == null ? base : base + " : " + returnTypeRaw;
    }
}

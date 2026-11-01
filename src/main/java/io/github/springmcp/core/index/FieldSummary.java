package io.github.springmcp.core.index;

import java.util.List;

/**
 * A field declaration.
 *
 * @param name field name
 * @param typeRaw declared type exactly as written, generics and arrays included
 * @param annotations annotations on the field, e.g. {@code @Autowired}, {@code @Resource}
 * @param isStatic whether the field is static
 * @param constantInitializer initialiser of a string constant, as written, or {@code null}
 * @param line 1-based declaration line
 * @param column 1-based declaration column
 */
public record FieldSummary(String name, String typeRaw, List<AnnotationUse> annotations, boolean isStatic,
        String constantInitializer, int line, int column) {

    public FieldSummary {
        annotations = annotations == null ? List.of() : List.copyOf(annotations);
    }

    /**
     * Only {@code static final String} / interface constants (Java) and {@code const val} (Kotlin)
     * carry an initialiser. A field that can be reassigned has no value a reader may rely on, so it
     * stays {@code null} rather than becoming a guess dressed as a fact.
     *
     * <p>
     * The text is stored as written — quotes included — because it is not always a literal:
     * {@code API_FRONT_URL_PREFIX = API_URL_PREFIX + "/front"} holds a reference to a sibling
     * constant, one of the most common ways to build a path prefix. Folding such a chain is
     * {@link io.github.springmcp.core.resolve.ConstantResolver}'s job, and keeping the source text
     * here is what lets it happen after the AST is gone.
     * </p>
     *
     * @return whether the field is a constant whose value can be evaluated
     */
    public boolean isStringConstant() {
        return constantInitializer != null;
    }
}

package io.github.springmcp.core.index;

import java.util.List;

/**
 * One member-value pair of an annotation instance, normalised to plain strings.
 *
 * <p>
 * Recording the AST of an annotation argument would force us to keep compilation units alive.
 * Instead each element of the value is stringified once and a single flag says whether all of
 * them were genuine string literals. That is enough to answer every question the analyzers ask:
 * "is this a literal path I can trust, or an opaque expression I must report as unresolved?"
 * </p>
 *
 * @param key member name; {@code "value"} for the shorthand form {@code @GetMapping("/a")}
 * @param values one entry per element; a single value is a one-element list
 * @param allStringLiterals {@code true} when every element was a {@code String} literal, which
 * is the only case where {@code values} can be trusted as literal text
 */
public record AnnotationArg(String key, List<String> values, boolean allStringLiterals) {

    public AnnotationArg {
        values = values == null ? List.of() : List.copyOf(values);
    }

    /** @return the first value, or {@code null} */
    public String first() {
        return values.isEmpty() ? null : values.get(0);
    }
}

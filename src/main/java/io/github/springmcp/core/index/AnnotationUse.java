package io.github.springmcp.core.index;

import java.util.List;

/**
 * An annotation usage, name kept exactly as written in source.
 *
 * <p>
 * The name may be a simple name ({@code RequestMapping}) or fully qualified
 * ({@code org.springframework.web.bind.annotation.RequestMapping}). Resolving it to a fully
 * qualified name needs the imports of the enclosing file, so that job is left to
 * {@link io.github.springmcp.core.resolve.TypeNameResolver} and
 * {@link io.github.springmcp.core.util.AnnotationNames}.
 * </p>
 *
 * @param name annotation name without the leading {@code @}
 * @param line 1-based line of the annotation
 * @param args member values, empty for marker annotations
 */
public record AnnotationUse(String name, int line, List<AnnotationArg> args) {

    public AnnotationUse {
        args = args == null ? List.of() : List.copyOf(args);
    }

    /** @return the part after the last dot, or the whole name when unqualified */
    public String simpleName() {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(dot + 1);
    }

    /**
     * Looks up a member value, falling back to the shorthand {@code value} member.
     * @param key member name, e.g. {@code "path"}
     * @return the argument, or {@code null} when absent
     */
    public AnnotationArg arg(String key) {
        for (AnnotationArg a : args) {
            if (a.key().equals(key)) {
                return a;
            }
        }
        return null;
    }

    /** @return the shorthand {@code value} member, or {@code null} */
    public AnnotationArg value() {
        return arg("value");
    }
}

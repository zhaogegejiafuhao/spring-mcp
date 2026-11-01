package io.github.springmcp.core.util;

import io.github.springmcp.core.index.AnnotationUse;
import io.github.springmcp.core.index.CuSummary;
import io.github.springmcp.core.resolve.Resolution;
import io.github.springmcp.core.resolve.TypeNameResolver;

/**
 * Annotation name normalisation and matching.
 *
 * <p>
 * Source may write {@code @RequestMapping} or the fully qualified form; both must match the same
 * query. Resolution goes through the same {@link TypeNameResolver} used for types, because an
 * annotation name is just a type name in a different position.
 * </p>
 *
 * <p>
 * Meta-annotations are deliberately not expanded. {@code @GetMapping} is <em>not</em> reported as a
 * {@code @RequestMapping} hit: the MVP reports what is literally written, and the tool
 * descriptions say so, because a wrong "yes" here would send a caller down the wrong path.
 * </p>
 */
public final class AnnotationNames {

    private AnnotationNames() {
    }

    /**
     * Removes a leading {@code @} and trims.
     * @param raw raw name
     * @return normalised name, empty when {@code raw} is {@code null}
     */
    public static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim();
        while (s.startsWith("@")) {
            s = s.substring(1).trim();
        }
        return s;
    }

    /**
     * @param raw annotation name, with or without {@code @}
     * @return the part after the last dot
     */
    public static String simpleName(String raw) {
        String n = normalize(raw);
        int dot = n.lastIndexOf('.');
        return dot < 0 ? n : n.substring(dot + 1);
    }

    /**
     * Best-effort fully qualified name of an annotation usage.
     * @param use the usage found in source
     * @param context the file it appears in
     * @param resolver project resolver
     * @return a fully qualified name, or {@code null} when it could not be determined
     */
    public static String resolveFqn(AnnotationUse use, CuSummary context, TypeNameResolver resolver) {
        if (use == null) {
            return null;
        }
        String raw = normalize(use.name());
        if (raw.indexOf('.') > 0) {
            return raw;
        }
        Resolution r = resolver.resolve(raw, context);
        return r.hasFqn() ? r.fqn() : null;
    }

    /**
     * Whether an annotation usage answers a query.
     * @param use the usage found in source
     * @param context the file it appears in
     * @param resolver project resolver
     * @param query annotation name or fully qualified name supplied by the caller
     * @param strictFqn when {@code true} only an exact fully qualified match counts
     * @return {@code true} on match
     */
    public static boolean matches(AnnotationUse use, CuSummary context, TypeNameResolver resolver, String query,
            boolean strictFqn) {
        String wanted = normalize(query);
        if (wanted.isEmpty() || use == null) {
            return false;
        }
        String raw = normalize(use.name());

        if (strictFqn) {
            if (raw.equals(wanted)) {
                return true;
            }
            String fqn = resolveFqn(use, context, resolver);
            return fqn != null && fqn.equals(wanted);
        }

        // Lenient: simple name equality is enough. This is what a caller wants in the common case
        // ("find @Transactional"), and the tool result says the match was by simple name.
        if (simpleName(raw).equals(simpleName(wanted))) {
            return true;
        }
        String fqn = resolveFqn(use, context, resolver);
        return fqn != null && fqn.equals(wanted);
    }
}

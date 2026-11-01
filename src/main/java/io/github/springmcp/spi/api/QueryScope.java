package io.github.springmcp.spi.api;

import java.util.ArrayList;
import java.util.List;

/**
 * Narrows one query to a subset of a multi-module project's modules.
 *
 * <p>
 * Scope is deliberately a <em>query</em> concept, not a build option. Putting it into
 * {@link IndexOptions} would change the cache key, so every different combination of modules would
 * trigger a full rebuild — switching scope twice on a large repository would cost two index builds.
 * Instead the index is always built over the whole project (which is also what keeps cross-module
 * type resolution working), and the scope is applied when the results are assembled.
 * </p>
 *
 * <p>
 * Module identifiers are project-relative paths such as {@code services/order}, never artifact ids:
 * an artifact id is a name someone chose, a path is a fact about the repository.
 * </p>
 *
 * @param includeModules modules to keep; empty means every module
 * @param excludeModules modules to drop; applied after the include list
 */
public record QueryScope(List<String> includeModules, List<String> excludeModules) {

    public QueryScope {
        includeModules = clean(includeModules);
        excludeModules = clean(excludeModules);
    }

    /**
     * @return a scope that keeps everything
     */
    public static QueryScope unrestricted() {
        return new QueryScope(List.of(), List.of());
    }

    /**
     * @param include modules to keep, empty for all
     * @param exclude modules to drop
     * @return the scope
     */
    public static QueryScope of(List<String> include, List<String> exclude) {
        return new QueryScope(include, exclude);
    }

    /**
     * @return whether this scope can discard anything at all
     */
    public boolean isUnrestricted() {
        return includeModules.isEmpty() && excludeModules.isEmpty();
    }

    /**
     * Tests one module against this scope.
     *
     * <p>
     * A pattern matches its own module and every module below it, so {@code services} selects
     * {@code services/order} and {@code services/payment} as well. The files that sit outside any
     * module carry the empty id — they are only kept when the scope imposes no include list.
     * </p>
     *
     * @param module project-relative module path, {@code ""} for files outside every module
     * @return whether results from that module belong in the answer
     */
    public boolean includes(String module) {
        String id = module == null ? "" : module;
        if (!includeModules.isEmpty() && includeModules.stream().noneMatch(pattern -> matches(id, pattern))) {
            return false;
        }
        return excludeModules.stream().noneMatch(pattern -> matches(id, pattern));
    }

    /**
     * Intersects two scopes, so wrapping an already scoped handle narrows further instead of
     * silently widening it.
     *
     * <p>
     * Include patterns are subtrees, so intersecting two of them means keeping the deeper one. An
     * empty intersection is expressed as "exclude everything" rather than "include nothing", because
     * an empty include list already means <em>all</em> modules — conflating the two would turn
     * {@code scope=a} followed by {@code scope=b} into the whole project.
     * </p>
     *
     * @param other scope to intersect with
     * @return the narrower scope
     */
    public QueryScope and(QueryScope other) {
        if (other == null || other.isUnrestricted()) {
            return this;
        }
        if (isUnrestricted()) {
            return other;
        }

        List<String> includes;
        if (includeModules.isEmpty()) {
            includes = other.includeModules();
        }
        else if (other.includeModules().isEmpty()) {
            includes = includeModules;
        }
        else {
            includes = new ArrayList<>();
            for (String mine : includeModules) {
                for (String theirs : other.includeModules()) {
                    String narrower = narrower(mine, theirs);
                    if (narrower != null && !includes.contains(narrower)) {
                        includes.add(narrower);
                    }
                }
            }
        }

        List<String> excludes = new ArrayList<>(excludeModules);
        excludes.addAll(other.excludeModules());
        if (includes.isEmpty() && !includeModules.isEmpty() && !other.includeModules().isEmpty()) {
            return new QueryScope(List.of(), List.of("*"));
        }
        return new QueryScope(includes, excludes);
    }

    /**
     * @return the deeper of two patterns when one contains the other, otherwise {@code null}
     */
    private static String narrower(String a, String b) {
        if (a.equals(b)) {
            return a;
        }
        if (b.startsWith(a + "/")) {
            return b;
        }
        if (a.startsWith(b + "/")) {
            return a;
        }
        return null;
    }

    /**
     * @return a short human readable form, e.g. {@code "模块 services/order"}
     */
    public String describe() {
        if (isUnrestricted()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        if (!includeModules.isEmpty()) {
            sb.append("仅 ").append(String.join(", ", includeModules));
        }
        if (!excludeModules.isEmpty()) {
            if (sb.length() > 0) {
                sb.append("；");
            }
            sb.append("排除 ").append(String.join(", ", excludeModules));
        }
        return sb.toString();
    }

    private static boolean matches(String module, String pattern) {
        String p = pattern.replace('\\', '/');
        if (p.isEmpty() || "*".equals(p)) {
            return true;
        }
        if (module.isEmpty()) {
            // Files outside every module can still be named explicitly as "." or "<root>".
            return ".".equals(p) || "<root>".equalsIgnoreCase(p);
        }
        return module.equals(p) || module.startsWith(p + "/");
    }

    private static boolean containsIgnoreCase(List<String> values, String candidate) {
        for (String value : values) {
            if (value.equalsIgnoreCase(candidate)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> clean(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String raw : values) {
            if (raw == null) {
                continue;
            }
            String v = raw.trim().replace('\\', '/');
            while (v.startsWith("./")) {
                v = v.substring(2);
            }
            while (v.endsWith("/")) {
                v = v.substring(0, v.length() - 1);
            }
            if (!v.isEmpty() && !containsIgnoreCase(out, v)) {
                out.add(v);
            }
        }
        return List.copyOf(out);
    }

    @Override
    public String toString() {
        return isUnrestricted() ? "QueryScope[all]"
                : "QueryScope[" + String.join(",", includeModules) + " -" + String.join(",", excludeModules) + "]";
    }
}

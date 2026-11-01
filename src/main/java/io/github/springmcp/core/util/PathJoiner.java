package io.github.springmcp.core.util;

import java.util.ArrayList;
import java.util.List;

/**
 * URL path concatenation for {@code @RequestMapping} composition.
 *
 * <p>
 * Spring's own rules are more forgiving than a naive {@code a + b}: an empty class prefix must not
 * produce a double slash, a method path with no leading slash must still yield {@code /api/x}, and
 * the root must be {@code /} rather than the empty string. Getting this wrong produces endpoints
 * that look plausible but do not exist, which is the worst failure mode for a tool whose whole job
 * is telling a caller what URLs a project exposes.
 * </p>
 */
public final class PathJoiner {

    private PathJoiner() {
    }

    /**
     * Joins a class-level prefix with a method-level path.
     * @param prefix class prefix, may be {@code null} or empty
     * @param path method path, may be {@code null} or empty
     * @return a path starting with {@code /}; {@code "/"} when both are empty
     */
    public static String join(String prefix, String path) {
        String a = clean(prefix);
        String b = clean(path);
        // "/" is the root, not a segment. Treating it as one produced "//test" for the common
        // @RequestMapping("/") + @GetMapping("/test") pair, which is a URL that does not exist.
        if (a.isEmpty() || "/".equals(a)) {
            return b.isEmpty() ? "/" : b;
        }
        if (b.isEmpty() || "/".equals(b)) {
            return a;
        }
        return a + (b.startsWith("/") ? b : "/" + b);
    }

    /**
     * Normalises slashes: ensures one leading slash, strips duplicates, keeps a single trailing
     * slash when the input had one.
     * @param raw segment
     * @return normalised segment, empty for {@code null} or blank
     */
    public static String clean(String raw) {
        if (raw == null) {
            return "";
        }
        // No backslash conversion here: a URL path may legitimately contain one. Spring's regex path
        // constraint {id:\\d+} was silently rewritten to {id:/d+} — a path the application does not
        // serve, which is the worst kind of wrong answer. Callers that accept file-system-style input
        // go through cleanFilter instead.
        String s = raw.trim();
        if (s.isEmpty()) {
            return "";
        }
        boolean trailingSlash = s.length() > 1 && s.endsWith("/");
        while (s.contains("//")) {
            s = s.replace("//", "/");
        }
        if (!s.startsWith("/")) {
            s = "/" + s;
        }
        if (trailingSlash && s.length() > 1 && !s.endsWith("/")) {
            s = s + "/";
        }
        if (s.length() > 1 && s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    /**
     * Normalises a path supplied by a caller, where a Windows-style separator is a likely typo.
     * @param raw user-supplied path filter
     * @return normalised segment
     */
    public static String cleanFilter(String raw) {
        return clean(raw == null ? null : raw.replace('\\', '/'));
    }

    /**
     * Concatenates two path fragments without normalising them.
     *
     * <p>
     * Used when at least one fragment is not a literal — a constant reference or a placeholder. In
     * that case the result must not look like a URL, because it is not one: {@code ApiConst.BASE/ping}
     * is obviously an expression, whereas {@code /ApiConst.BASE/ping} reads like a real endpoint and
     * would mislead the caller.
     * </p>
     * @param prefix class fragment as written
     * @param path method fragment as written
     * @return the two fragments joined, no slash inserted or removed
     */
    public static String rawConcat(String prefix, String path) {
        String a = prefix == null ? "" : prefix.trim();
        String b = path == null ? "" : path.trim();
        if (a.isEmpty() || "/".equals(a)) {
            return b.isEmpty() ? "/" : b;
        }
        if (b.isEmpty() || "/".equals(b)) {
            return a;
        }
        return a + (b.startsWith("/") ? "" : "/") + b;
    }

    /**
     * Turns one class prefix and one method path into every combination, dropping duplicates.
     * @param prefixes class-level paths
     * @param paths method-level paths
     * @return distinct joined paths in input order
     */
    public static List<String> combinations(List<String> prefixes, List<String> paths) {
        List<String> out = new ArrayList<>();
        List<String> left = prefixes == null || prefixes.isEmpty() ? List.of("") : prefixes;
        List<String> right = paths == null || paths.isEmpty() ? List.of("") : paths;
        for (String p : left) {
            for (String s : right) {
                String joined = join(p, s);
                if (!out.contains(joined)) {
                    out.add(joined);
                }
            }
        }
        return out;
    }
}

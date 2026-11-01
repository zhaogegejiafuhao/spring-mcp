package io.github.springmcp.core.resolve;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.springmcp.core.index.CuSummary;
import io.github.springmcp.core.index.TypeSummary;

/**
 * Maps type names as they appear in source to fully qualified names, using nothing but the syntax
 * of the file they appear in.
 *
 * <p>
 * Without a symbol solver there is no type environment to consult, so resolution follows the same
 * order a human reading the file would: an explicit import wins, then the same package, then
 * wildcard imports, then "there is only one class with this name in the project". The last step is
 * a pragmatic fallback that resolves {@code OrderService} even when the file forgets to import it,
 * which happens constantly in single-module projects.
 * </p>
 *
 * <p>
 * Immutable and thread-safe.
 * </p>
 */
public final class TypeNameResolver {

    private static final Set<String> JAVA_LANG = Set.of("Object", "String", "Integer", "Long", "Short", "Byte",
            "Character", "Boolean", "Float", "Double", "Number", "Void", "Class", "Enum", "Record", "Throwable",
            "Exception", "RuntimeException", "Error", "Iterable", "Comparable", "Runnable", "Thread", "StringBuilder",
            "CharSequence", "Math", "System", "Override", "Deprecated", "SuppressWarnings", "FunctionalInterface");

    private final Map<String, TypeSummary> byFqn;

    private final Map<String, List<TypeSummary>> bySimpleName;

    /**
     * @param byFqn every type in the project keyed by fully qualified name
     * @param bySimpleName every type keyed by simple name; more than one entry means ambiguity
     */
    public TypeNameResolver(Map<String, TypeSummary> byFqn, Map<String, List<TypeSummary>> bySimpleName) {
        this.byFqn = byFqn;
        this.bySimpleName = bySimpleName;
    }

    /**
     * Resolves a type name in the context of a file.
     * @param name name as written, e.g. {@code OrderService} or {@code com.acme.OrderService}
     * @param context the file the reference appears in, may be {@code null} for a context-free
     * lookup
     * @return the resolution, never {@code null}
     */
    public Resolution resolve(String name, CuSummary context) {
        if (name == null || name.isBlank()) {
            return Resolution.unknown();
        }
        String cleaned = stripTypeArguments(name.trim());
        if (cleaned.isEmpty()) {
            return Resolution.unknown();
        }

        // 1. Already fully qualified.
        if (cleaned.indexOf('.') > 0) {
            String dotted = cleaned.replace('$', '.');
            if (byFqn.containsKey(dotted)) {
                return Resolution.resolved(dotted, Resolution.Rule.FULLY_QUALIFIED);
            }
            // A dotted name that is not in the project is assumed to be a real JDK/third-party FQN.
            return Resolution.external(dotted, Resolution.Rule.FULLY_QUALIFIED);
        }

        if (context == null) {
            return resolveGlobally(cleaned);
        }

        // 2. Explicit single-type import.
        String imported = context.importFor(cleaned);
        if (imported != null) {
            return byFqn.containsKey(imported) ? Resolution.resolved(imported, Resolution.Rule.EXPLICIT_IMPORT)
                    : Resolution.external(imported, Resolution.Rule.EXPLICIT_IMPORT);
        }

        // 3. Same package.
        String pkg = context.packageName();
        if (!pkg.isEmpty()) {
            String samePkg = pkg + "." + cleaned;
            if (byFqn.containsKey(samePkg)) {
                return Resolution.resolved(samePkg, Resolution.Rule.SAME_PACKAGE);
            }
        }

        // 4. Wildcard imports.
        List<String> wildcards = context.wildcardImportPackages();
        if (!wildcards.isEmpty()) {
            List<String> hits = new ArrayList<>();
            for (String w : wildcards) {
                String candidate = w + "." + cleaned;
                if (byFqn.containsKey(candidate)) {
                    hits.add(candidate);
                }
            }
            if (hits.size() == 1) {
                return Resolution.resolved(hits.get(0), Resolution.Rule.WILDCARD_IMPORT);
            }
            if (hits.size() > 1) {
                return Resolution.ambiguous(hits);
            }
        }

        // 5. Global unique simple name.
        return resolveGlobally(cleaned);
    }

    /**
     * Resolves a type name without file context, using only the project-wide simple-name index.
     * @param name simple or qualified type name
     * @return the resolution
     */
    public Resolution resolveGlobally(String name) {
        if (name == null || name.isBlank()) {
            return Resolution.unknown();
        }
        String cleaned = stripTypeArguments(name.trim());
        if (cleaned.indexOf('.') > 0) {
            String dotted = cleaned.replace('$', '.');
            return byFqn.containsKey(dotted) ? Resolution.resolved(dotted, Resolution.Rule.FULLY_QUALIFIED)
                    : Resolution.external(dotted, Resolution.Rule.FULLY_QUALIFIED);
        }
        List<TypeSummary> matches = bySimpleName.get(cleaned);
        if (matches == null || matches.isEmpty()) {
            if (JAVA_LANG.contains(cleaned)) {
                return Resolution.external("java.lang." + cleaned, Resolution.Rule.NONE);
            }
            return Resolution.unknown();
        }
        if (matches.size() == 1) {
            return Resolution.resolved(matches.get(0).fqn(), Resolution.Rule.GLOBAL_UNIQUE);
        }
        return Resolution.ambiguous(matches.stream().map(TypeSummary::fqn).sorted().toList());
    }

    /**
     * Strips generic arguments and array brackets from a type expression.
     * @param raw e.g. {@code List<OrderService[]>}
     * @return e.g. {@code List}
     */
    public static String stripTypeArguments(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim();
        int lt = s.indexOf('<');
        if (lt >= 0) {
            s = s.substring(0, lt);
        }
        while (s.endsWith("[]")) {
            s = s.substring(0, s.length() - 2).trim();
        }
        // Varargs in a parameter list.
        while (s.endsWith("...")) {
            s = s.substring(0, s.length() - 3).trim();
        }
        return s.trim();
    }

    /**
     * Extracts the first generic type argument, e.g. {@code Order} from
     * {@code JpaRepository<Order, Long>}.
     * @param raw raw type text
     * @return the first argument, or {@code null} when there are none
     */
    public static String firstTypeArgument(String raw) {
        if (raw == null) {
            return null;
        }
        int lt = raw.indexOf('<');
        int gt = raw.lastIndexOf('>');
        if (lt < 0 || gt <= lt) {
            return null;
        }
        String inner = raw.substring(lt + 1, gt);
        int depth = 0;
        for (int i = 0; i < inner.length(); i++) {
            char c = inner.charAt(i);
            if (c == '<') {
                depth++;
            }
            else if (c == '>') {
                depth--;
            }
            else if (c == ',' && depth == 0) {
                return inner.substring(0, i).trim();
            }
        }
        return inner.trim();
    }

    /**
     * Extracts the nth generic type argument.
     * @param raw raw type text
     * @param index zero-based argument index
     * @return the argument text, or {@code null}
     */
    public static String typeArgument(String raw, int index) {
        List<String> parts = typeArguments(raw);
        return index >= 0 && index < parts.size() ? parts.get(index) : null;
    }

    /**
     * Splits a generic type expression into its arguments.
     *
     * <p>
     * Depth-aware, because {@code BaseDao<Map<String, User>>} has two arguments, not three — the same
     * comma that separates parameters also appears inside a nested argument.
     * </p>
     *
     * @param raw raw type text such as {@code JpaRepository<Order, Long>}
     * @return the arguments in order, empty when the type is not generic
     */
    public static List<String> typeArguments(String raw) {
        if (raw == null) {
            return List.of();
        }
        int lt = raw.indexOf('<');
        int gt = raw.lastIndexOf('>');
        if (lt < 0 || gt <= lt) {
            return List.of();
        }
        String inner = raw.substring(lt + 1, gt);
        List<String> parts = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < inner.length(); i++) {
            char c = inner.charAt(i);
            if (c == '<') {
                depth++;
            }
            else if (c == '>') {
                depth--;
            }
            else if (c == ',' && depth == 0) {
                parts.add(inner.substring(start, i).trim());
                start = i + 1;
            }
        }
        parts.add(inner.substring(start).trim());
        return parts;
    }

    /** @return every type in the project keyed by fully qualified name */
    public Map<String, TypeSummary> byFqn() {
        return byFqn;
    }

    /** @return every type in the project keyed by simple name */
    public Map<String, List<TypeSummary>> bySimpleName() {
        return bySimpleName;
    }
}

package io.github.springmcp.core.resolve;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import io.github.springmcp.core.index.CuSummary;
import io.github.springmcp.core.index.TypeSummary;

/**
 * Transitive supertype closure and assignability checks, computed from {@code extends} /
 * {@code implements} clauses only.
 *
 * <p>
 * Needed because a field is almost always declared with the interface type
 * ({@code private OrderService orderService;}) while the class that actually exists in the project
 * is the implementation. Answering "who calls {@code OrderServiceImpl}" therefore requires knowing
 * that {@code OrderServiceImpl} is an {@code OrderService}.
 * </p>
 *
 * <p>
 * Cycles are handled with a visited set; the JDK has none but hand-written generic hierarchies
 * occasionally do after a refactor, and an infinite loop inside a tool handler would hang the
 * session.
 * </p>
 */
public final class SuperTypeGraph {

    private final Map<String, TypeSummary> byFqn;

    private final Map<Path, CuSummary> byFile;

    private final TypeNameResolver resolver;

    private final Map<String, Set<String>> closureCache = new ConcurrentHashMap<>();

    /**
     * @param byFqn every type in the project
     * @param byFile every file summary, used to resolve each type's supertype names in its own
     * import context
     * @param resolver name resolver
     */
    public SuperTypeGraph(Map<String, TypeSummary> byFqn, Map<Path, CuSummary> byFile, TypeNameResolver resolver) {
        this.byFqn = byFqn;
        this.byFile = byFile;
        this.resolver = resolver;
    }

    /**
     * Returns the type itself plus every supertype reachable through {@code extends} and
     * {@code implements}, resolved to fully qualified names where possible.
     * @param fqn starting type
     * @return immutable set, empty when the type is unknown
     */
    public Set<String> closureOf(String fqn) {
        if (fqn == null) {
            return Set.of();
        }
        Set<String> cached = closureCache.get(fqn);
        if (cached != null) {
            return cached;
        }
        Set<String> visited = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(fqn);
        while (!queue.isEmpty()) {
            String current = queue.poll();
            if (!visited.add(current)) {
                continue;
            }
            TypeSummary type = byFqn.get(current);
            if (type == null) {
                continue;
            }
            for (String raw : type.rawSuperTypes()) {
                Resolution r = resolveSuper(raw, type);
                if (r.hasFqn() && !visited.contains(r.fqn())) {
                    queue.add(r.fqn());
                }
            }
        }
        Set<String> result = Set.copyOf(visited);
        closureCache.put(fqn, result);
        return result;
    }

    /**
     * Looks up an indexed type by fully qualified name.
     * @param fqn fully qualified name
     * @return the type, or {@code null} when it is not part of the index (JDK, third-party jar)
     */
    public TypeSummary typeByFqn(String fqn) {
        return fqn == null ? null : byFqn.get(fqn);
    }

    /**
     * Directly declared supertypes, resolved to fully qualified names where possible.
     *
     * <p>
     * Ordered as written in the {@code extends} / {@code implements} clause, so a caller looking for
     * "the nearest declared annotation" walks from the most specific type outwards.
     * </p>
     *
     * @param fqn type to inspect
     * @return resolved direct supertypes, skipping the ones that could not be resolved
     */
    public List<String> directSuperTypes(String fqn) {
        TypeSummary type = byFqn.get(fqn);
        if (type == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String raw : type.rawSuperTypes()) {
            Resolution resolved = resolveSuper(raw, type);
            if (resolved.hasFqn() && !out.contains(resolved.fqn())) {
                out.add(resolved.fqn());
            }
        }
        return List.copyOf(out);
    }

    /**
     * Directly declared supertypes as written in source (generics stripped), regardless of whether
     * they could be resolved. Used for pattern checks such as "does this extend JpaRepository".
     * @param fqn type to inspect
     * @return simple names of direct supertypes
     */
    public List<String> directSuperTypeNames(String fqn) {
        TypeSummary type = byFqn.get(fqn);
        if (type == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String raw : type.rawSuperTypes()) {
            String stripped = TypeNameResolver.stripTypeArguments(raw);
            int dot = stripped.lastIndexOf('.');
            out.add(dot < 0 ? stripped : stripped.substring(dot + 1));
        }
        return out;
    }

    /**
     * Full text of the directly declared supertypes, generics included. Used to extract JPA entity
     * types from {@code JpaRepository<Order, Long>}.
     * @param fqn type to inspect
     * @return raw supertype declarations
     */
    public List<String> rawSuperTypes(String fqn) {
        TypeSummary type = byFqn.get(fqn);
        return type == null ? List.of() : type.rawSuperTypes();
    }

    /**
     * Whether {@code candidate} is {@code target} or a subtype of it.
     * @param candidate the type in hand
     * @param target the type we are looking for
     * @return {@code true} when a call on {@code candidate} may reach {@code target}
     */
    public boolean isAssignableTo(String candidate, String target) {
        return candidate != null && target != null && closureOf(candidate).contains(target);
    }

    /**
     * Whether either type is a subtype of the other. Useful when the caller may pass either the
     * interface or the implementation.
     * @param a first type
     * @param b second type
     * @return {@code true} when the two are related by inheritance in either direction
     */
    public boolean related(String a, String b) {
        return isAssignableTo(a, b) || isAssignableTo(b, a);
    }

    /**
     * Whether the {@code extends}/{@code implements} chain of a type mentions any of the given
     * simple names, without needing those names to resolve inside the project.
     * @param fqn type to inspect
     * @param simpleNames e.g. {@code ["JpaRepository", "CrudRepository"]}
     * @return {@code true} when a match is found
     */
    public boolean anySuperTypeNamed(String fqn, Set<String> simpleNames) {
        TypeSummary type = byFqn.get(fqn);
        if (type == null) {
            return false;
        }
        Deque<String> queue = new ArrayDeque<>();
        Set<String> visited = new LinkedHashSet<>();
        for (String raw : type.rawSuperTypes()) {
            Resolution r = resolveSuper(raw, type);
            String name = TypeNameResolver.stripTypeArguments(raw);
            int dot = name.lastIndexOf('.');
            String simple = dot < 0 ? name : name.substring(dot + 1);
            if (simpleNames.contains(simple)) {
                return true;
            }
            if (r.hasFqn()) {
                queue.add(r.fqn());
            }
        }
        while (!queue.isEmpty()) {
            String current = queue.poll();
            if (!visited.add(current)) {
                continue;
            }
            TypeSummary t = byFqn.get(current);
            if (t == null) {
                continue;
            }
            for (String raw : t.rawSuperTypes()) {
                Resolution r = resolveSuper(raw, t);
                String name = TypeNameResolver.stripTypeArguments(raw);
                int dot = name.lastIndexOf('.');
                String simple = dot < 0 ? name : name.substring(dot + 1);
                if (simpleNames.contains(simple)) {
                    return true;
                }
                if (r.hasFqn()) {
                    queue.add(r.fqn());
                }
            }
        }
        return false;
    }

    /**
     * Resolves one {@code extends} / {@code implements} entry to a project type.
     *
     * <p>
     * Needed when the caller has to pair the declaration as written ({@code BaseDao<Coin>}) with the
     * type it denotes, so generic arguments can be carried along a chain. {@link #directSuperTypes}
     * cannot be used for that: it drops the entries it cannot resolve, which loses the pairing.
     * </p>
     *
     * @param ownerFqn type declaring the clause
     * @param rawSuperType one entry as written, generics included
     * @return fully qualified name of the supertype, or {@code null} when it is not in the index
     */
    public String resolveSuperType(String ownerFqn, String rawSuperType) {
        TypeSummary owner = byFqn.get(ownerFqn);
        if (owner == null || rawSuperType == null) {
            return null;
        }
        Resolution resolved = resolveSuper(rawSuperType, owner);
        return resolved.hasFqn() ? resolved.fqn() : null;
    }

    private Resolution resolveSuper(String raw, TypeSummary owner) {
        CuSummary cu = byFile.get(owner.file());
        return resolver.resolve(TypeNameResolver.stripTypeArguments(raw), cu);
    }
}

package io.github.springmcp.core.analyze;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import io.github.springmcp.core.index.CuSummary;
import io.github.springmcp.core.index.ImportInfo;
import io.github.springmcp.core.index.TypeSummary;
import io.github.springmcp.core.resolve.Resolution;
import io.github.springmcp.core.resolve.TypeNameResolver;
import io.github.springmcp.spi.ErrorCode;
import io.github.springmcp.spi.ToolFailure;
import io.github.springmcp.spi.model.Dependencies;

/**
 * Dependency edges of a single file, in both directions.
 *
 * <p>
 * Incoming edges are indexed once, eagerly, from two sources: explicit imports that point at a
 * project type, and same-package references that appear without any import at all. The second
 * source matters — class files in one package are the tightest coupling in most Spring projects and
 * an import-only analysis reports zero dependencies for exactly those.
 * </p>
 */
public final class FileDependencyAnalyzer {

    private final Map<Path, CuSummary> byFile;

    private final Map<String, TypeSummary> byFqn;

    private final Map<String, List<TypeSummary>> bySimpleName;

    private final TypeNameResolver resolver;

    /** target FQN -> edges pointing at it. */
    private final Map<String, List<Dependencies.Edge>> incoming;

    /**
     * @param byFile all file summaries
     * @param byFqn all types keyed by fully qualified name
     * @param bySimpleName all types keyed by simple name
     * @param resolver name resolver
     */
    public FileDependencyAnalyzer(Map<Path, CuSummary> byFile, Map<String, TypeSummary> byFqn,
            Map<String, List<TypeSummary>> bySimpleName, TypeNameResolver resolver) {
        this.byFile = byFile;
        this.byFqn = byFqn;
        this.bySimpleName = bySimpleName;
        this.resolver = resolver;
        this.incoming = buildIncomingIndex();
    }

    private Map<String, List<Dependencies.Edge>> buildIncomingIndex() {
        Map<String, List<Dependencies.Edge>> index = new LinkedHashMap<>();
        Set<String> dedupe = new LinkedHashSet<>();

        for (CuSummary cu : byFile.values()) {
            for (ImportInfo imp : cu.imports()) {
                if (imp.isWildcard()) {
                    continue;
                }
                TypeSummary target = byFqn.get(imp.name());
                if (target == null || target.file().equals(cu.file())) {
                    continue;
                }
                String key = target.fqn() + "|" + cu.file() + "|IMPORT";
                if (!dedupe.add(key)) {
                    continue;
                }
                Dependencies.Kind kind = imp.isStatic() ? Dependencies.Kind.STATIC_IMPORT
                        : Dependencies.Kind.IMPORT;
                index.computeIfAbsent(target.fqn(), k -> new ArrayList<>())
                    .add(new Dependencies.Edge(target.fqn(), kind, true, cu.file().toString(),
                            target.file().toString(), imp.line()));
            }

            for (String simple : cu.referencedSimpleTypeNames()) {
                TypeSummary target = samePackageType(cu.packageName(), simple);
                if (target == null || target.file().equals(cu.file())) {
                    continue;
                }
                if (cu.importFor(simple) != null) {
                    // Already captured as an explicit import above; avoid double counting.
                    continue;
                }
                String key = target.fqn() + "|" + cu.file() + "|SAME_PACKAGE";
                if (!dedupe.add(key)) {
                    continue;
                }
                index.computeIfAbsent(target.fqn(), k -> new ArrayList<>())
                    .add(new Dependencies.Edge(target.fqn(), Dependencies.Kind.SAME_PACKAGE_USAGE, true,
                            cu.file().toString(), target.file().toString(), 0));
            }
        }
        return index;
    }

    /**
     * Builds the report for one file.
     * Builds the report for one file, considering the whole project.
     * @param file absolute, normalised path of a file that is part of the index
     * @param includeSamePackage include sibling files of the same package
     * @return the dependency report
     * @throws ToolFailure when the file is outside the index
     */
    public Dependencies.Report analyze(Path file, boolean includeSamePackage) throws ToolFailure {
        return analyze(file, includeSamePackage, path -> true);
    }

    /**
     * Builds the report for one file, restricting the inbound side to a subset of the project.
     *
     * <p>
     * Outgoing edges are never filtered, and that asymmetry is the entire point: "what does this
     * file depend on" must keep answering across module boundaries, otherwise scoping would hide
     * exactly the coupling it was meant to help inspect. Only "who depends on this file" — which can
     * legitimately come from anywhere — is narrowed.
     * </p>
     *
     * @param file absolute, normalised path of a file that is part of the index
     * @param includeSamePackage include sibling files of the same package
     * @param fileInScope which other files may contribute inbound edges or siblings
     * @return the dependency report
     * @throws ToolFailure when the file is outside the index
     */
    public Dependencies.Report analyze(Path file, boolean includeSamePackage, Predicate<Path> fileInScope)
            throws ToolFailure {
        CuSummary cu = byFile.get(file);
        if (cu == null) {
            throw ToolFailure.of(ErrorCode.NOT_A_SOURCE_FILE,
                    "文件不在索引中（可能不是 .java 文件，或位于被排除的目录）: " + file,
                    "确认路径属于该工程，且不是 target/ 等构建产物目录");
        }

        String primaryFqn = cu.types().isEmpty() ? null : cu.types().get(0).fqn();

        Map<String, Dependencies.Edge> outgoing = new LinkedHashMap<>();
        for (ImportInfo imp : cu.imports()) {
            Dependencies.Kind kind = imp.isWildcard() ? Dependencies.Kind.WILDCARD_IMPORT
                    : (imp.isStatic() ? Dependencies.Kind.STATIC_IMPORT : Dependencies.Kind.IMPORT);
            boolean internal = !imp.isWildcard() && byFqn.containsKey(imp.name());
            String toFile = internal ? byFqn.get(imp.name()).file().toString() : null;
            String target = imp.isWildcard() ? imp.name() + ".*" : imp.name();
            outgoing.putIfAbsent(target + "|" + kind,
                    new Dependencies.Edge(target, kind, internal, cu.file().toString(), toFile, imp.line()));
        }

        for (String simple : cu.referencedSimpleTypeNames()) {
            Resolution r = resolver.resolve(simple, cu);
            if (!r.hasFqn()) {
                continue;
            }
            // java.lang is imported implicitly and is never a coupling worth reporting; including it
            // would bury the real dependencies under a "String" entry for every single file.
            if (r.fqn().startsWith("java.lang.")) {
                continue;
            }
            TypeSummary target = byFqn.get(r.fqn());
            if (target != null && target.file().equals(cu.file())) {
                continue;
            }
            Dependencies.Kind kind = r.rule() == Resolution.Rule.SAME_PACKAGE ? Dependencies.Kind.SAME_PACKAGE_USAGE
                    : Dependencies.Kind.TYPE_USAGE;
            outgoing.putIfAbsent(r.fqn() + "|" + kind,
                    new Dependencies.Edge(r.fqn(), kind, target != null, cu.file().toString(),
                            target == null ? null : target.file().toString(), 0));
        }

        List<Dependencies.Edge> incomingEdges = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (TypeSummary t : cu.types()) {
            for (Dependencies.Edge e : incoming.getOrDefault(t.fqn(), List.of())) {
                if (e.fromFile() != null && !fileInScope.test(Path.of(e.fromFile()))) {
                    continue;
                }
                String key = e.fromFile() + "|" + e.kind();
                if (seen.add(key)) {
                    incomingEdges.add(e);
                }
            }
        }

        List<String> siblings = new ArrayList<>();
        if (includeSamePackage) {
            for (CuSummary other : byFile.values()) {
                if (other.file().equals(cu.file())) {
                    continue;
                }
                if (other.packageName().equals(cu.packageName()) && fileInScope.test(other.file())) {
                    siblings.add(other.file().toString());
                }
            }
            siblings.sort(Comparator.naturalOrder());
        }

        List<Dependencies.Edge> out = new ArrayList<>(outgoing.values());
        out.sort(Comparator.comparing(Dependencies.Edge::targetType));
        incomingEdges.sort(Comparator.comparing((Dependencies.Edge e) -> e.fromFile() == null ? "" : e.fromFile()));

        return new Dependencies.Report(cu.file().toString(), primaryFqn, cu.packageName(), out, incomingEdges,
                siblings, List.of());
    }

    /**
     * Looks up a type by simple name within one package.
     * @param packageName package to restrict to
     * @param simpleName type simple name
     * @return the type, or {@code null}
     */
    private TypeSummary samePackageType(String packageName, String simpleName) {
        List<TypeSummary> candidates = bySimpleName.get(simpleName);
        if (candidates == null) {
            return null;
        }
        for (TypeSummary t : candidates) {
            if (t.packageName().equals(packageName)) {
                return t;
            }
        }
        return null;
    }
}

package io.github.springmcp.core.index;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * Everything the index keeps about one source file once the AST has been thrown away.
 *
 * <p>
 * This is the central performance trade-off of the whole project. A summary is a few kilobytes;
 * a compilation unit is tens to hundreds. Four of the five tools can be answered from summaries
 * alone, and {@code find-service-callers} uses {@link #calledMethodNames()} to narrow thousands
 * of files down to the handful that can possibly contain a matching call before it touches the
 * AST at all.
 * </p>
 *
 * @param file absolute, normalised source path
 * @param module project-relative module path this file belongs to, {@code ""} when the project has
 * no detected module structure; kept here rather than on {@link TypeSummary} because it is a
 * property of the file and there is one summary per file but dozens of types per file
 * @param packageName package declaration, empty when absent
 * @param imports import statements in source order
 * @param types top-level types declared in the file (nested ones are flattened into the index but
 * are not repeated here)
 * @param calledMethodNames every method name that appears in a call expression anywhere in the
 * file; used purely as a cheap pre-filter
 * @param referencedSimpleTypeNames simple names appearing in type positions (extends, implements,
 * field/param/local types, {@code new}, casts)
 * @param problems parser diagnostics for this file, empty when clean
 * @param lastModifiedMillis file modification time at index build time
 * @param size file size in bytes at index build time
 */
public record CuSummary(Path file, String module, String packageName, List<ImportInfo> imports,
        List<TypeSummary> types, Set<String> calledMethodNames, Set<String> referencedSimpleTypeNames,
        List<String> problems, long lastModifiedMillis, long size) {

    public CuSummary {
        module = module == null ? "" : module;
        imports = imports == null ? List.of() : List.copyOf(imports);
        types = types == null ? List.of() : List.copyOf(types);
        calledMethodNames = calledMethodNames == null ? Set.of() : Set.copyOf(calledMethodNames);
        referencedSimpleTypeNames = referencedSimpleTypeNames == null ? Set.of()
                : Set.copyOf(referencedSimpleTypeNames);
        problems = problems == null ? List.of() : List.copyOf(problems);
    }

    /**
     * @return whether the file lives under {@code src/test/java} or any {@code /test/} segment
     */
    public boolean inTestSources() {
        String p = file.toString().replace('\\', '/');
        return p.contains("/src/test/") || p.contains("/test/java/");
    }

    /**
     * @param simpleName type simple name
     * @return the imported name it maps to, or {@code null}
     */
    public String importFor(String simpleName) {
        for (ImportInfo i : imports) {
            if (i.isStatic() || i.isWildcard()) {
                continue;
            }
            String n = i.name();
            int dot = n.lastIndexOf('.');
            String tail = dot < 0 ? n : n.substring(dot + 1);
            if (tail.equals(simpleName)) {
                return n;
            }
        }
        return null;
    }

    /**
     * @return wildcard (non-static) import package names, e.g. {@code ["com.acme.model"]}
     */
    public List<String> wildcardImportPackages() {
        return imports.stream().filter(i -> i.isWildcard() && !i.isStatic()).map(ImportInfo::name).toList();
    }
}

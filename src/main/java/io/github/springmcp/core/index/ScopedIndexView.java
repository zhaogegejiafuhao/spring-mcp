package io.github.springmcp.core.index;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import io.github.springmcp.spi.ToolFailure;
import io.github.springmcp.spi.api.IndexHandle;
import io.github.springmcp.spi.api.QueryScope;
import io.github.springmcp.spi.model.Annotations;
import io.github.springmcp.spi.model.Controllers;
import io.github.springmcp.spi.model.Dependencies;
import io.github.springmcp.spi.model.FeignClients;
import io.github.springmcp.spi.model.IndexMetadata;
import io.github.springmcp.spi.model.Mappers;
import io.github.springmcp.spi.model.GatewayRoutes;
import io.github.springmcp.spi.model.ServiceCalls;
import io.github.springmcp.spi.model.Symbols;
import io.github.springmcp.spi.model.TypeHierarchy;

/**
 * A module-scoped view over one {@link SourceIndex}.
 *
 * <p>
 * The whole point is what it does <em>not</em> do: it never rebuilds an index, never removes a type
 * from the name resolver, and never touches the supertype graph. Filtering happens on the way out,
 * so a scoped query answers exactly like the unscoped one would have — minus the modules the caller
 * excluded. That keeps cross-module resolution intact, which is the entire reason scoping is not
 * done while walking.
 * </p>
 *
 * <p>
 * The scoped type list is computed once per view. A view is created per tool call, so this is one
 * pass over the file map per call — microseconds next to the analysis it feeds.
 * </p>
 */
final class ScopedIndexView implements IndexHandle {

    private final SourceIndex index;

    private final QueryScope scope;

    private List<TypeSummary> scopedTypes;

    private Set<String> scopedModuleNames;

    /**
     * @param index full snapshot
     * @param scope modules to keep
     */
    ScopedIndexView(SourceIndex index, QueryScope scope) {
        this.index = index;
        this.scope = scope;
    }

    @Override
    public IndexMetadata metadata() {
        return index.metadata();
    }

    @Override
    public Path root() {
        return index.root();
    }

    @Override
    public QueryScope scope() {
        return scope;
    }

    @Override
    public IndexHandle scoped(QueryScope other) {
        return index.scoped(scope.and(other));
    }

    @Override
    public List<String> modules() {
        return index.modules();
    }

    @Override
    public Map<String, List<String>> moduleDependencies() {
        return index.moduleDependencies();
    }

    @Override
    public FeignClients.Report feignClients(FeignClients.Query query) {
        // Scoped types only: a client declared in an excluded module is out of scope, while a client
        // whose target is elsewhere stays — the exclusion says nothing about the remote service.
        return index.feignClients(types(), query);
    }

    @Override
    public Controllers.Report controllers(Controllers.Query query) {
        return index.controllers(types(), query, pathPredicate());
    }

    @Override
    public ServiceCalls.Report findServiceCallers(ServiceCalls.Query query) throws ToolFailure {
        return index.serviceCallers(query, filePredicate());
    }

    @Override
    public Mappers.Report mappers(Mappers.Query query) {
        return index.mappers(types(), query, pathPredicate());
    }

    @Override
    public Annotations.Report findByAnnotation(Annotations.Query query) throws ToolFailure {
        return index.annotations(types(), query);
    }

    @Override
    public Dependencies.Report fileDependencies(Path file, boolean includeSamePackage) throws ToolFailure {
        Dependencies.Report scoped = index.fileDependencies(file, includeSamePackage, pathPredicate());
        if (!scoped.warnings().isEmpty() || scope.isUnrestricted()) {
            return scoped;
        }
        // Counting what was dropped requires the unscoped answer, but only the cheap part: the
        // incoming-edge map is memoised per snapshot, so the second call costs a map lookup.
        Dependencies.Report everything = index.fileDependencies(file, includeSamePackage);
        int droppedIncoming = everything.incoming().size() - scoped.incoming().size();
        int droppedSiblings = everything.samePackageSiblings().size() - scoped.samePackageSiblings().size();
        if (droppedIncoming == 0 && droppedSiblings == 0) {
            return scoped;
        }
        List<String> warnings = new ArrayList<>(scoped.warnings());
        warnings.add("作用域（" + scope.describe() + "）过滤掉 " + droppedIncoming + " 条入向依赖、"
                + droppedSiblings + " 个同包文件；出向依赖未过滤");
        return new Dependencies.Report(scoped.file(), scoped.typeFqn(), scoped.packageName(), scoped.outgoing(),
                scoped.incoming(), scoped.samePackageSiblings(), warnings);
    }

    @Override
    public GatewayRoutes gatewayRoutes() throws ToolFailure {
        GatewayRoutes full = index.gatewayRoutes();
        ModuleLayout layout = index.layout();
        if (layout.isEmpty()) {
            return full;
        }
        List<GatewayRoutes.Route> kept = new ArrayList<>();
        int dropped = 0;
        for (GatewayRoutes.Route route : full.routes()) {
            // A route whose target is not a module of this project (an absolute URI, or a service that
            // lives elsewhere) is kept: scoping is about this repository's modules, and such a route
            // says nothing about being out of scope.
            if (route.targetModule() == null || scope.includes(route.targetModule())) {
                kept.add(route);
            }
            else {
                dropped++;
            }
        }
        if (dropped == 0) {
            return full;
        }
        List<String> warnings = new ArrayList<>(full.warnings());
        warnings.add("作用域（" + scope.describe() + "）过滤掉 " + dropped + " 条网关路由");
        return new GatewayRoutes(kept, kept.size(), full.discoveryLocatorEnabled(), warnings);
    }

    @Override
    public Symbols.Report searchSymbols(Symbols.Query query) throws ToolFailure {
        Symbols.Report full = index.searchSymbols(query);
        ModuleLayout layout = index.layout();
        if (layout.isEmpty()) {
            return full;
        }
        List<Symbols.Symbol> kept = new ArrayList<>();
        int dropped = 0;
        for (Symbols.Symbol symbol : full.symbols()) {
            if (scope.includes(layout.moduleOf(Path.of(symbol.file())))) {
                kept.add(symbol);
            }
            else {
                dropped++;
            }
        }
        if (dropped == 0) {
            return full;
        }
        List<String> warnings = new ArrayList<>(full.warnings());
        warnings.add("作用域（" + scope.describe() + "）过滤掉 " + dropped + " 个类型");
        return new Symbols.Report(kept, kept.size(), full.truncated(), warnings);
    }

    @Override
    public TypeHierarchy.Report implementations(TypeHierarchy.Query query) throws ToolFailure {
        TypeHierarchy.Report full = index.implementations(query);
        ModuleLayout layout = index.layout();
        if (layout.isEmpty()) {
            return full;
        }
        List<TypeHierarchy.Implementation> kept = new ArrayList<>();
        int dropped = 0;
        for (TypeHierarchy.Implementation implementation : full.implementations()) {
            String module = layout.moduleOf(Path.of(implementation.file()));
            if (scope.includes(module)) {
                kept.add(implementation);
            }
            else {
                dropped++;
            }
        }
        if (dropped == 0) {
            return full;
        }
        List<String> warnings = new ArrayList<>(full.warnings());
        warnings.add("作用域（" + scope.describe() + "）过滤掉 " + dropped + " 个实现类");
        return new TypeHierarchy.Report(full.targetFqn(), full.targetKind(), full.targetAbstract(), kept,
                kept.size(), warnings);
    }

    /**
     * @return modules this scope actually keeps, for callers that want to explain the scope
     */
    Set<String> keptModules() {
        if (scopedModuleNames == null) {
            Set<String> kept = new LinkedHashSet<>();
            for (String module : index.modules()) {
                if (scope.includes(module)) {
                    kept.add(module);
                }
            }
            scopedModuleNames = kept;
        }
        return scopedModuleNames;
    }

    private List<TypeSummary> types() {
        if (scopedTypes == null) {
            List<TypeSummary> out = new ArrayList<>();
            for (CuSummary cu : index.summariesByFile().values()) {
                if (scope.includes(cu.module())) {
                    out.addAll(cu.types());
                }
            }
            scopedTypes = List.copyOf(out);
        }
        return scopedTypes;
    }

    private Predicate<Path> pathPredicate() {
        // Module membership comes from the layout rather than from the per-file summaries, because
        // mapper XML is not an indexed source file and would otherwise always look "unknown" — which
        // would silently keep every module's XML in a scoped query.
        ModuleLayout layout = index.layout();
        if (layout.isEmpty()) {
            return file -> true;
        }
        return file -> scope.includes(layout.moduleOf(file));
    }

    private Predicate<CuSummary> filePredicate() {
        return cu -> scope.includes(cu.module());
    }
}

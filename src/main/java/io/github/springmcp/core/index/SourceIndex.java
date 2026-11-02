package io.github.springmcp.core.index;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import io.github.springmcp.core.analyze.AnnotationAnalyzer;
import io.github.springmcp.core.analyze.ControllerAnalyzer;
import io.github.springmcp.core.analyze.FeignClientAnalyzer;
import io.github.springmcp.core.analyze.FileDependencyAnalyzer;
import io.github.springmcp.core.analyze.GatewayRouteAnalyzer;
import io.github.springmcp.core.analyze.MapperAnalyzer;
import io.github.springmcp.core.analyze.RouteLocatorAnalyzer;
import io.github.springmcp.core.analyze.RouterFunctionAnalyzer;
import io.github.springmcp.core.analyze.ServiceCallerAnalyzer;
import io.github.springmcp.core.kotlin.KotlinSummaryBuilder;
import io.github.springmcp.core.parse.AstCache;
import io.github.springmcp.core.parse.JavaParserFactory;
import io.github.springmcp.core.parse.ParseOutcome;
import io.github.springmcp.core.parse.SourceText;
import io.github.springmcp.core.resolve.Resolution;
import io.github.springmcp.core.resolve.SuperTypeGraph;
import io.github.springmcp.core.resolve.ConstantResolver;
import io.github.springmcp.core.resolve.TypeNameResolver;
import io.github.springmcp.core.util.PathJoiner;
import io.github.springmcp.spi.ErrorCode;
import io.github.springmcp.spi.ToolFailure;
import io.github.springmcp.spi.api.IndexHandle;
import io.github.springmcp.spi.api.IndexOptions;
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
 * An immutable snapshot of a project's source index.
 *
 * <p>
 * Immutability is what makes the whole server simple: a snapshot can be read by any number of
 * concurrent tool calls without locking, and a rebuild publishes a brand new instance instead of
 * mutating the old one. Only the {@link AstCache} is mutable, and it is a pure cache — dropping it
 * changes latency, never an answer.
 * </p>
 */
public final class SourceIndex implements IndexHandle {

    private final Path root;

    private final IndexMetadata metadata;

    private final IndexOptions options;

    private final ModuleLayout layout;

    private final Map<Path, CuSummary> byFile;

    private final Map<String, TypeSummary> byFqn;

    private final Map<String, List<TypeSummary>> bySimpleName;

    private final List<TypeSummary> allTypes;

    private final TypeNameResolver resolver;

    private final SuperTypeGraph superTypes;

    private final AstCache astCache;

    private final ControllerAnalyzer controllerAnalyzer;

    private final GatewayRouteAnalyzer gatewayRouteAnalyzer = new GatewayRouteAnalyzer();

    private final RouteLocatorAnalyzer routeLocatorAnalyzer;

    private final RouterFunctionAnalyzer routerFunctionAnalyzer;

    /** Computed at most once per snapshot; the route table never changes without a rebuild. */
    private volatile GatewayRoutes gatewayRoutes;

    /** Functional routes are parsed on demand, once per snapshot. */
    private volatile RouterFunctionAnalyzer.Result routerFunctions;

    private final AnnotationAnalyzer annotationAnalyzer = new AnnotationAnalyzer();

    private final FeignClientAnalyzer feignClientAnalyzer = new FeignClientAnalyzer();

    private final MapperAnalyzer mapperAnalyzer;

    private final FileDependencyAnalyzer dependencyAnalyzer;

    private final ServiceCallerAnalyzer callerAnalyzer;

    /**
     * @param root project root
     * @param metadata snapshot metadata
     * @param options options the snapshot was built with
     * @param layout module structure, used for query-time scoping
     * @param summaries per file summaries, insertion order irrelevant
     * @param astCache shared AST cache
     */
    public SourceIndex(Path root, IndexMetadata metadata, IndexOptions options, ModuleLayout layout,
            List<CuSummary> summaries, AstCache astCache) {
        this.root = root;
        this.metadata = metadata;
        this.options = options;
        this.layout = layout == null ? ModuleLayout.empty(root) : layout;
        this.astCache = astCache;

        Map<Path, CuSummary> files = new LinkedHashMap<>();
        Map<String, TypeSummary> fqn = new LinkedHashMap<>();
        Map<String, List<TypeSummary>> simple = new LinkedHashMap<>();
        List<TypeSummary> flat = new ArrayList<>();

        for (CuSummary cu : summaries) {
            files.put(cu.file(), cu);
            for (TypeSummary t : cu.types()) {
                fqn.putIfAbsent(t.fqn(), t);
                simple.computeIfAbsent(t.simpleName(), k -> new ArrayList<>()).add(t);
                flat.add(t);
            }
        }

        Map<String, List<TypeSummary>> frozenSimple = new LinkedHashMap<>();
        simple.forEach((k, v) -> frozenSimple.put(k, List.copyOf(v)));

        this.byFile = Map.copyOf(files);
        this.byFqn = Map.copyOf(fqn);
        this.bySimpleName = Map.copyOf(frozenSimple);
        this.allTypes = List.copyOf(flat);
        this.resolver = new TypeNameResolver(byFqn, bySimpleName);
        this.superTypes = new SuperTypeGraph(byFqn, byFile, resolver);
        this.controllerAnalyzer = new ControllerAnalyzer(superTypes,
                new ConstantResolver(resolver, superTypes, allTypes, byFile), byFile::get);
        this.routeLocatorAnalyzer = new RouteLocatorAnalyzer(astCache);
        this.routerFunctionAnalyzer = new RouterFunctionAnalyzer(astCache);
        this.mapperAnalyzer = new MapperAnalyzer(superTypes);
        this.dependencyAnalyzer = new FileDependencyAnalyzer(byFile, byFqn, bySimpleName, resolver);
        this.callerAnalyzer = new ServiceCallerAnalyzer(byFqn, byFile, allTypes, resolver, superTypes::isAssignableTo,
                ServiceCallerAnalyzer.receiverResolver(byFqn, resolver, superTypes::closureOf), astCache);
    }

    @Override
    public IndexMetadata metadata() {
        return metadata;
    }

    @Override
    public Path root() {
        return root;
    }

    @Override
    public Controllers.Report controllers(Controllers.Query query) {
        return controllers(allTypes, query, path -> true);
    }

    @Override
    public ServiceCalls.Report findServiceCallers(ServiceCalls.Query query) throws ToolFailure {
        return callerAnalyzer.analyze(query);
    }

    @Override
    public Mappers.Report mappers(Mappers.Query query) {
        return mapperAnalyzer.analyze(allTypes, root, query, options.includeTests());
    }

    @Override
    public FeignClients.Report feignClients(FeignClients.Query query) {
        return feignClientAnalyzer.analyze(allTypes, layout.modules(), query);
    }

    @Override
    public Annotations.Report findByAnnotation(Annotations.Query query) throws ToolFailure {
        return annotationAnalyzer.analyze(allTypes, byFile, resolver, query);
    }

    @Override
    public Dependencies.Report fileDependencies(Path file, boolean includeSamePackage) throws ToolFailure {
        return dependencyAnalyzer.analyze(normalise(file), includeSamePackage);
    }

    @Override
    public Symbols.Report searchSymbols(Symbols.Query query) throws ToolFailure {
        if (query == null || query.name() == null || query.name().isBlank()) {
            throw ToolFailure.of(ErrorCode.INVALID_ARGUMENT, "缺少类型名", "请传类名片段，例如 OrderController");
        }
        String needle = query.name().trim().toLowerCase(java.util.Locale.ROOT);
        int limit = query.limit() > 0 ? query.limit() : 50;

        List<Symbols.Symbol> matches = new ArrayList<>();
        for (TypeSummary type : allTypes) {
            if (!query.kinds().isEmpty() && !query.kinds().contains(type.kind().name())) {
                continue;
            }
            String simple = type.simpleName().toLowerCase(java.util.Locale.ROOT);
            String fqn = type.fqn().toLowerCase(java.util.Locale.ROOT);
            if (!simple.contains(needle) && !fqn.contains(needle)) {
                continue;
            }
            List<String> annotations = new ArrayList<>();
            type.annotations().forEach(annotation -> annotations.add(annotation.simpleName()));
            annotations.sort(Comparator.naturalOrder());
            matches.add(new Symbols.Symbol(type.fqn(), type.simpleName(), type.packageName(),
                    type.kind().name(), type.abstractType(), annotations, type.file().toString(), type.line()));
        }

        // Rank: exact simple name first, then prefix matches, then anything else. Within a rank the
        // shorter name wins, which puts `Order` above `OrderServiceImplFactory` for the query "order".
        matches.sort(Comparator.comparingInt((Symbols.Symbol symbol) -> rank(symbol, needle))
            .thenComparingInt(symbol -> symbol.simpleName().length())
            .thenComparing(Symbols.Symbol::simpleName)
            .thenComparing(Symbols.Symbol::typeFqn));

        int total = matches.size();
        boolean truncated = total > limit;
        List<Symbols.Symbol> page = truncated ? List.copyOf(matches.subList(0, limit)) : List.copyOf(matches);

        List<String> warnings = new ArrayList<>();
        if (truncated) {
            warnings.add("命中 " + total + " 个类型，只返回前 " + limit + " 个；请把名字写得更具体或提高 limit");
        }
        if (total == 0) {
            warnings.add("没有名字包含 '" + query.name() + "' 的类型；确认拼写，或该类型位于未索引的目录/外部依赖中");
        }
        return new Symbols.Report(page, total, truncated, warnings);
    }

    /**
     * @param symbol candidate
     * @param needle lower-cased query
     * @return {@code 0} exact simple name, {@code 1} prefix, {@code 2} substring elsewhere
     */
    private static int rank(Symbols.Symbol symbol, String needle) {
        String simple = symbol.simpleName().toLowerCase(java.util.Locale.ROOT);
        if (simple.equals(needle)) {
            return 0;
        }
        return simple.startsWith(needle) ? 1 : 2;
    }

    @Override
    public GatewayRoutes gatewayRoutes() throws ToolFailure {
        GatewayRoutes computed = gatewayRoutes;
        if (computed != null) {
            return computed;
        }
        synchronized (this) {
            if (gatewayRoutes == null) {
                List<GatewayRoutes.Route> codeRoutes = routeLocatorAnalyzer.routes(root, byFile, layout.modules());
                gatewayRoutes = gatewayRouteAnalyzer.report(root, options.includeTests(), layout.modules(),
                        codeRoutes);
            }
            return gatewayRoutes;
        }
    }

    @Override
    public TypeHierarchy.Report implementations(TypeHierarchy.Query query) throws ToolFailure {
        if (query == null || query.type() == null || query.type().isBlank()) {
            throw ToolFailure.of(ErrorCode.INVALID_ARGUMENT, "缺少类型名",
                    "请传接口或类的简单名/全限定名，例如 OrderService");
        }
        Resolution target = resolver.resolveGlobally(query.type());
        if (target.status() == Resolution.Status.AMBIGUOUS) {
            throw ToolFailure.of(ErrorCode.AMBIGUOUS_CLASS,
                    "类名 '" + query.type() + "' 匹配到多个类: " + String.join(", ", target.candidates()),
                    "请改用全限定名重试");
        }
        if (!target.hasFqn()) {
            throw ToolFailure.of(ErrorCode.CLASS_NOT_FOUND, "找不到类: " + query.type(),
                    "确认类名拼写，或提供全限定名");
        }

        String targetFqn = target.fqn();
        TypeSummary targetType = byFqn.get(targetFqn);
        List<TypeHierarchy.Implementation> implementations = new ArrayList<>();
        int skippedAbstract = 0;
        int skippedInterface = 0;

        for (TypeSummary candidate : allTypes) {
            if (candidate.fqn().equals(targetFqn)) {
                continue;
            }
            if (!superTypes.isAssignableTo(candidate.fqn(), targetFqn)) {
                continue;
            }
            if (candidate.kind() == TypeKind.INTERFACE) {
                if (!query.includeInterfaces()) {
                    skippedInterface++;
                    continue;
                }
            }
            else if (candidate.abstractType() && !query.includeAbstract()) {
                skippedAbstract++;
                continue;
            }
            implementations.add(new TypeHierarchy.Implementation(candidate.fqn(), candidate.simpleName(),
                    candidate.kind().name(), candidate.abstractType(), stereotypes(candidate),
                    superTypes.directSuperTypes(candidate.fqn()).contains(targetFqn),
                    candidate.file().toString(), candidate.line()));
        }
        implementations.sort(Comparator.comparing(TypeHierarchy.Implementation::simpleName)
            .thenComparing(TypeHierarchy.Implementation::typeFqn));

        List<String> warnings = new ArrayList<>();
        if (implementations.isEmpty()) {
            warnings.add("没有找到 '" + targetFqn + "' 的实现类：可能是接口尚无实现，或实现类位于未索引的目录/外部依赖中");
        }
        if (skippedAbstract > 0) {
            warnings.add("另有 " + skippedAbstract + " 个抽象类实现该类型，设 includeAbstract=true 可列出");
        }
        if (skippedInterface > 0) {
            warnings.add("另有 " + skippedInterface + " 个子接口，设 includeInterfaces=true 可列出");
        }

        return new TypeHierarchy.Report(targetFqn, targetType == null ? "" : targetType.kind().name(),
                targetType != null && targetType.abstractType(), implementations, implementations.size(), warnings);
    }

    /** Spring annotations that make a class a bean by themselves. */
    private static final Set<String> STEREOTYPES = Set.of("Service", "Component", "Repository", "Controller",
            "RestController", "Configuration");

    /**
     * @param type candidate implementation
     * @return Spring stereotype annotations declared directly on it, in a stable order
     */
    private static List<String> stereotypes(TypeSummary type) {
        List<String> out = new ArrayList<>();
        for (String name : STEREOTYPES) {
            if (type.hasAnnotation(name)) {
                out.add(name);
            }
        }
        out.sort(Comparator.naturalOrder());
        return out;
    }

    @Override
    public IndexHandle scoped(QueryScope scope) {
        if (scope == null || scope.isUnrestricted() || layout.isEmpty()) {
            // Without a detected module structure a scope could only ever filter everything out, so
            // it is ignored rather than turned into an empty answer the caller cannot explain.
            return this;
        }
        return new ScopedIndexView(this, scope);
    }

    @Override
    public List<String> modules() {
        return layout.modules();
    }

    @Override
    public Map<String, List<String>> moduleDependencies() {
        return layout.dependencies();
    }

    /**
     * @return the module structure of this snapshot
     */
    ModuleLayout layout() {
        return layout;
    }

    /**
     * Per-file summaries, ordered by path.
     *
     * <p>
     * Public for the index cache, which persists exactly this list; nothing else should need it.
     * </p>
     *
     * @return per-file summaries, ascending by path
     */
    public Map<Path, CuSummary> summariesByFile() {
        return byFile;
    }

    // --------------------------------------------------- scoped entry points
    // A scoped view runs the very same analyzers, only over a filtered input. Keeping one code path
    // is what guarantees that a scoped answer and an unscoped one cannot disagree about the
    // analysis itself — only about what is included.

    Controllers.Report controllers(List<TypeSummary> types, Controllers.Query query, Predicate<Path> fileInScope) {
        Controllers.Report base = controllerAnalyzer.analyze(types, query);
        RouterFunctionAnalyzer.Result functional = routerFunctions();
        List<String> warnings = new ArrayList<>(base.warnings());
        if (functional.unexpanded() > 0) {
            warnings.add(functional.unexpanded()
                    + " 个 RouterFunctions.route(...) 没有字面量路径（谓词是方法引用或常量），未展开为端点");
        }
        if (functional.unresolvedPrefixes() > 0) {
            // Not a detail: every one of these paths is missing a prefix the code does apply, so a
            // caller comparing them with what the front end calls would see them all as absent.
            warnings.add(functional.unresolvedPrefixes()
                    + " 个端点位于 nest(...) 之下，而该前缀无法静态求值（如 \"/apis/\" + groupVersion），"
                    + "path 不含这层前缀且 pathResolved=false，请勿直接当作可访问地址: "
                    + String.join("、", functional.prefixSamples()));
        }
        if (!functional.structuralNests().isEmpty()) {
            // The prefix crosses a type boundary: CustomEndpointsBuilder nests a router that each
            // CustomEndpoint builds elsewhere. Nothing here can attribute it to an endpoint, but a
            // reader comparing these paths with real traffic has to know the layer exists.
            warnings.add("发现 " + functional.structuralNests().size()
                    + " 处 nest(...) 的前缀作用在别处（路由由变量或另一个类的返回值得出），未应用到本报告的路径；"
                    + "若这些端点正是你查的那批，它们的真实 URL 前面还有一层前缀: "
                    + String.join("、", functional.structuralNests().stream().limit(3).toList()));
        }

        List<Controllers.Endpoint> extra = new ArrayList<>();
        for (Controllers.Endpoint endpoint : functional.endpoints()) {
            if (!fileInScope.test(Path.of(endpoint.file()))) {
                continue;
            }
            if (query.httpMethod() != null && !endpoint.httpMethods().contains(query.httpMethod())) {
                continue;
            }
            if (query.basePathFilter() != null
                    && !endpoint.path().startsWith(PathJoiner.cleanFilter(query.basePathFilter()))) {
                continue;
            }
            extra.add(endpoint);
        }
        if (extra.isEmpty()) {
            return new Controllers.Report(base.endpoints(), base.controllerCount(), base.methodCount(), warnings);
        }

        List<Controllers.Endpoint> merged = new ArrayList<>(base.endpoints());
        merged.addAll(extra);
        merged.sort(Comparator.comparing(Controllers.Endpoint::file)
            .thenComparingInt(Controllers.Endpoint::line)
            .thenComparing(Controllers.Endpoint::path));
        warnings.add(extra.size()
                + " 个端点来自 RouterFunctions 函数式路由，controllerFqn 是声明处的类，没有 @Controller 注解");
        return new Controllers.Report(merged, base.controllerCount(), base.methodCount() + extra.size(), warnings);
    }

    /**
     * @return functional routes of this snapshot, parsed on first use
     */
    private RouterFunctionAnalyzer.Result routerFunctions() {
        RouterFunctionAnalyzer.Result cached = routerFunctions;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (routerFunctions == null) {
                routerFunctions = routerFunctionAnalyzer.scan(root, byFile);
            }
            return routerFunctions;
        }
    }

    FeignClients.Report feignClients(List<TypeSummary> types, FeignClients.Query query) {
        return feignClientAnalyzer.analyze(types, layout.modules(), query);
    }

    Annotations.Report annotations(List<TypeSummary> types, Annotations.Query query) throws ToolFailure {
        return annotationAnalyzer.analyze(types, byFile, resolver, query);
    }

    Mappers.Report mappers(List<TypeSummary> types, Mappers.Query query, Predicate<Path> xmlInScope) {
        return mapperAnalyzer.analyze(types, root, query, options.includeTests(), xmlInScope);
    }

    ServiceCalls.Report serviceCallers(ServiceCalls.Query query, Predicate<CuSummary> fileInScope)
            throws ToolFailure {
        return callerAnalyzer.analyze(query, fileInScope);
    }

    Dependencies.Report fileDependencies(Path file, boolean includeSamePackage, Predicate<Path> fileInScope)
            throws ToolFailure {
        return dependencyAnalyzer.analyze(normalise(file), includeSamePackage, fileInScope);
    }

    /**
     * Maps a caller supplied path onto a key of this snapshot.
     *
     * <p>
     * Three forms are accepted because a human or an LLM will supply all of them: an absolute path,
     * a path relative to the project root, and a path relative to the repository root (the usual
     * {@code src/main/java/...} form). The last one is matched as a suffix, which is unambiguous
     * enough in practice and saves a round trip on a rejected call.
     * </p>
     * @param file path as supplied
     * @return normalised absolute path, possibly not present in the index
     */
    private Path normalise(Path file) {
        Path absolute = (file.isAbsolute() ? file : root.resolve(file)).normalize();
        if (byFile.containsKey(absolute)) {
            return absolute;
        }

        String asText = absolute.toString().replace('\\', '/');
        String supplied = file.toString().replace('\\', '/');
        if (!supplied.startsWith("/") && root.toString().replace('\\', '/').endsWith("/" + supplied)) {
            Path viaSuffix = root.resolve(supplied).normalize();
            if (byFile.containsKey(viaSuffix)) {
                return viaSuffix;
            }
        }

        for (Path candidate : byFile.keySet()) {
            String candidateText = candidate.toString().replace('\\', '/');
            if (candidateText.equalsIgnoreCase(asText)) {
                return candidate;
            }
            if (!supplied.startsWith("/") && candidateText.endsWith("/" + supplied)) {
                return candidate;
            }
        }
        return absolute;
    }

    /** @return every type, sorted by fully qualified name */
    public List<TypeSummary> allTypes() {
        List<TypeSummary> sorted = new ArrayList<>(allTypes);
        sorted.sort(Comparator.comparing(TypeSummary::fqn));
        return sorted;
    }

    /** @return options this snapshot was built with */
    public IndexOptions options() {
        return options;
    }

    /**
     * Lists the fingerprinted files of this snapshot.
     * @return fingerprints sorted by path
     */
    public List<FileFingerprint> fingerprints() {
        List<FileFingerprint> out = new ArrayList<>();
        for (CuSummary cu : byFile.values()) {
            out.add(new FileFingerprint(cu.file(), cu.size(), cu.lastModifiedMillis()));
        }
        out.sort(Comparator.comparing(f -> f.path().toString()));
        return out;
    }

    /**
     * Builds a snapshot from a project root.
     */
    public static final class Builder {

        /**
         * Upper bound on index build threads. Parsing is CPU bound, so more threads than cores only
         * adds contention; the cap also keeps a 64 core CI box from opening 64 file handles per
         * repository.
         */
        private static final int MAX_INDEX_THREADS = 16;

        private final JavaParserFactory parser = new JavaParserFactory();

        private final CuSummaryBuilder summarizer = new CuSummaryBuilder();

        private final KotlinSummaryBuilder kotlinSummarizer = new KotlinSummaryBuilder();

        private final SourceWalker walker = new SourceWalker();

        /**
         * Walks, parses and summarises a project.
         * @param root project root
         * @param options build options
         * @param generation snapshot generation number
         * @param astCache cache shared across snapshots of the same root
         * @return the new snapshot
         * @throws ToolFailure on size overflow, timeout or unrecoverable I/O failure
         */
        public SourceIndex build(Path root, IndexOptions options, long generation, AstCache astCache)
                throws ToolFailure {
            return build(root, options, generation, astCache, null);
        }

        /**
         * Builds a snapshot, optionally reusing a walk the caller already performed.
         *
         * <p>
         * The cache needs the current fingerprints to decide whether its file is still valid, and the
         * builder needs the same list a moment later. Passing it in keeps a cache miss at exactly one
         * walk instead of two — on Windows that is ~0.75 s per 8 000 files, which would otherwise be
         * paid on every miss.
         * </p>
         *
         * @param root project root
         * @param options build options
         * @param generation snapshot generation number
         * @param astCache cache shared across snapshots of the same root
         * @param preWalked files already discovered, or {@code null} to walk here
         * @return the new snapshot
         * @throws ToolFailure on size overflow, timeout or unrecoverable I/O failure
         */
        public SourceIndex build(Path root, IndexOptions options, long generation, AstCache astCache,
                SourceWalker.WalkResult preWalked) throws ToolFailure {
            return build(root, options, generation, astCache, preWalked, List.of());
        }

        /**
         * Builds a snapshot, reusing summaries of files that did not change.
         *
         * <p>
         * A summary is a pure function of one file's content, so an unchanged file's summary is
         * identical whether it was produced a second ago or in a previous process. Matching on
         * {@code (path, size, mtime)} — the same evidence the change detector already trusts — lets a
         * one-file edit cost one parse instead of eight thousand.
         * </p>
         *
         * <p>
         * The derived index is still rebuilt from the complete list, so cross-file resolution is
         * unaffected: reuse shortens parsing, it never narrows what the snapshot knows.
         * </p>
         *
         * @param root project root
         * @param options build options
         * @param generation snapshot generation number
         * @param astCache cache shared across snapshots of the same root
         * @param preWalked files already discovered, or {@code null} to walk here
         * @param reusable summaries from an earlier build, of any subset of the files
         * @return the new snapshot
         * @throws ToolFailure on size overflow, timeout or unrecoverable I/O failure
         */
        public SourceIndex build(Path root, IndexOptions options, long generation, AstCache astCache,
                SourceWalker.WalkResult preWalked, List<CuSummary> reusable) throws ToolFailure {
            long startedAt = System.currentTimeMillis();

            // Detected before parsing because every summary carries its module; the detection reads a
            // handful of build files, so it is noise next to parsing thousands of sources.
            ModuleLayout layout = ModuleLayout.detect(root);

            SourceWalker.WalkResult walked = preWalked;
            try {
                if (walked == null) {
                    walked = walker.walk(root, options);
                }
            }
            catch (SourceWalker.TooManyFilesException e) {
                throw ToolFailure.of(ErrorCode.INDEX_TOO_LARGE, e.getMessage(), tooLargeHint(layout));
            }
            catch (IOException e) {
                throw ToolFailure.of(ErrorCode.INDEX_BUILD_FAILED, "遍历工程失败: " + root,
                        "检查目录权限与是否是可访问的本地路径", e);
            }

            List<CuSummary> summaries = new ArrayList<>(walked.files().size());
            List<IndexMetadata.ParseProblem> problems = new ArrayList<>();
            List<EncodingUse> encodings = new ArrayList<>();
            List<String> warnings = new ArrayList<>(walked.warnings());

            parseAll(walked.files(), options, layout, reusable, summaries, problems, encodings);

            long duration = System.currentTimeMillis() - startedAt;
            int typeCount = summaries.stream().mapToInt(s -> s.types().size()).sum();
            if (!problems.isEmpty()) {
                warnings.add(problems.size() + " 个文件存在解析问题，结果可能不完整");
            }
            if (!encodings.isEmpty()) {
                warnings.add(encodingNote(encodings));
            }
            if (summaries.isEmpty()) {
                warnings.add("未在该目录下找到任何 .java 文件");
            }
            KotlinUsage kotlin = walked.kotlin();
            if (kotlin.files() > 0) {
                StringBuilder note = new StringBuilder("工程含 ").append(kotlin.files())
                    .append(" 个 .kt 文件，已按 Kotlin 语法解析（类/接口/对象、属性、函数、注解、父类型）");
                note.append("；Kotlin 的表达式级类型推断不支持：链式调用与 lambda 接收者上的调用点无法定位，会列在 unresolved 里而不是被猜出来");
                if (kotlin.skippedLarge() > 0) {
                    note.append("；另有 ").append(kotlin.skippedLarge()).append(" 个 .kt 超过单文件上限被跳过");
                }
                warnings.add(note.toString());
            }

            IndexMetadata metadata = new IndexMetadata(root.toString(), generation, summaries.size(), typeCount,
                    startedAt, duration, problems, warnings);
            return new SourceIndex(root, metadata, options, layout, summaries, astCache);
        }

    /**
     * Describes how many files needed a fallback encoding, so a caller can tell "the project is
     * UTF-8" from "57 files were read as GBK and Chinese comments may be approximated".
     *
     * @param uses one entry per file that was not plain UTF-8
     * @return one sentence for the warnings list
     */
    private static String encodingNote(List<EncodingUse> uses) {
        Map<String, Integer> counts = new java.util.TreeMap<>();
        long lossy = 0;
        List<String> sample = new ArrayList<>();
        for (EncodingUse use : uses) {
            counts.merge(use.encoding(), 1, Integer::sum);
            if (use.lossy()) {
                lossy++;
            }
            if (sample.size() < 3) {
                sample.add(use.file().getFileName().toString());
            }
        }
        String breakdown = counts.entrySet().stream()
            .map(e -> e.getKey() + " " + e.getValue())
            .collect(java.util.stream.Collectors.joining("、"));
        StringBuilder note = new StringBuilder().append(uses.size()).append(" 个文件不是 UTF-8（")
            .append(breakdown)
            .append("），已按探测到的编码解码");
        if (lossy > 0) {
            note.append("；其中 ").append(lossy).append(" 个含无法解码的字节，非 ASCII 字符可能被替换");
        }
        note.append("；例如 ").append(String.join(", ", sample));
        return note.toString();
    }

    /**
     * Explains the ways out of a file-count overflow.
         *
         * <p>
         * {@code scope} is deliberately not offered as a remedy: scoping filters results after the
         * index is built, so it cannot lower the file count. Suggesting it here would send the caller
         * in a circle.
         * </p>
         *
         * @param layout detected modules, used to name concrete subdirectories
         * @return the hint text
         */
        private static String tooLargeHint(ModuleLayout layout) {
            StringBuilder sb = new StringBuilder("可提高上限（启动参数 --max-indexed-files=<n>）");
            if (!layout.isEmpty()) {
                List<String> sample = layout.modules().subList(0, Math.min(3, layout.modules().size()));
                sb.append("，或把 projectRoot 指向某个模块子目录（该工程识别到 ").append(layout.modules().size())
                    .append(" 个模块，例如 ").append(String.join(", ", sample)).append("）");
            }
            else {
                sb.append("，或把 projectRoot 指向更小的子目录");
            }
            sb.append("；注意 scope 只过滤结果，不会减少索引文件数");
            return sb.toString();
        }

        /**
         * Parses every file, in parallel.
         *
         * <p>
         * Parsing dominates index build time and is embarrassingly parallel: each file is read,
         * parsed into an AST, reduced to a summary and the AST discarded, with no shared state beyond
         * thread-safe collaborators. Measured on spring-boot's 8 413 source files (16 core laptop):
         * 17.3s sequential, 8.9s with 8 threads, 6.6s with 16. Scaling is sublinear because the two
         * full AST traversals in the summariser are allocation heavy, but the win is real.
         * </p>
         *
         * <p>
         * Two correctness details are load bearing. Results are collected in task order so the
         * summary list — and therefore every derived index — is deterministic across runs. And the
         * timeout is applied through {@code invokeAll}, because a per-file clock check cannot fire
         * while the calling thread is blocked waiting for a slow file.
         * </p>
         */
        private void parseAll(List<FileFingerprint> files, IndexOptions options, ModuleLayout layout,
                List<CuSummary> reusable, List<CuSummary> summaries, List<IndexMetadata.ParseProblem> problems,
                List<EncodingUse> encodings) throws ToolFailure {

            if (files.isEmpty()) {
                return;
            }

            Map<Path, CuSummary> byPath = reusableByPath(reusable);
            CuSummary[] slots = new CuSummary[files.size()];
            List<Integer> pendingSlots = new ArrayList<>();
            List<FileFingerprint> pendingFiles = new ArrayList<>();
            int reused = 0;
            for (int i = 0; i < files.size(); i++) {
                FileFingerprint fingerprint = files.get(i);
                CuSummary previous = byPath.get(fingerprint.path());
                if (previous != null && previous.size() == fingerprint.size()
                        && previous.lastModifiedMillis() == fingerprint.lastModifiedMillis()) {
                    slots[i] = previous;
                    reused++;
                    continue;
                }
                pendingSlots.add(i);
                pendingFiles.add(fingerprint);
            }
            if (pendingFiles.isEmpty()) {
                summaries.addAll(Arrays.asList(slots));
                return;
            }

            int threads = Math.max(1, Math.min(Runtime.getRuntime().availableProcessors(), MAX_INDEX_THREADS));
            ExecutorService pool = Executors.newFixedThreadPool(threads, runnable -> {
                Thread thread = new Thread(runnable, "spring-mcp-index");
                thread.setDaemon(true);
                return thread;
            });

            try {
                List<Callable<FileResult>> tasks = new ArrayList<>(pendingFiles.size());
                for (FileFingerprint fingerprint : pendingFiles) {
                    String module = layout.moduleOf(fingerprint.path());
                    tasks.add(() -> parseOne(fingerprint, module));
                }

                List<Future<FileResult>> futures = pool.invokeAll(tasks, options.maxBuildTime().toMillis(),
                        TimeUnit.MILLISECONDS);
                futures.forEach(f -> f.cancel(true));

                boolean timedOut = false;
                for (int i = 0; i < futures.size(); i++) {
                    Future<FileResult> future = futures.get(i);
                    if (future.isCancelled()) {
                        timedOut = true;
                        continue;
                    }
                    try {
                        FileResult result = future.get();
                        // Written back by slot index, so the resulting order matches the walk order
                        // even though only a subset of files went through the parser.
                        slots[pendingSlots.get(i)] = result.summary();
                        if (result.problem() != null) {
                            problems.add(result.problem());
                        }
                        if (result.source() != null && result.source().nonUtf8()) {
                            encodings.add(new EncodingUse(result.summary().file(), result.source().displayEncoding(),
                                    result.source().lossy()));
                        }
                    }
                    catch (ExecutionException e) {
                        // parseOne already catches everything; this is a last-resort guard.
                        Throwable cause = e.getCause();
                        problems.add(new IndexMetadata.ParseProblem("(unknown)", "parser-failure",
                                cause == null ? "unknown" : cause.getClass().getSimpleName()));
                    }
                }

                if (timedOut) {
                    throw ToolFailure.of(ErrorCode.TIMEOUT,
                            "索引构建超过 " + options.maxBuildTime().toSeconds() + "s，已中止",
                            "请把 projectRoot 指向更具体的子目录");
                }
                summaries.addAll(Arrays.asList(slots));
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw ToolFailure.of(ErrorCode.TIMEOUT, "索引构建被取消", "请缩小 projectRoot 范围后重试", e);
            }
            finally {
                pool.shutdownNow();
            }
        }

        /**
         * Indexes candidate summaries by path.
         *
         * <p>
         * A list rather than a map on the caller side because both sources (the previous snapshot and
         * the disk cache) hand out their own collections, and merging them into a map here keeps the
         * last one written — the previous snapshot, which is the more recent of the two.
         * </p>
         *
         * @param reusable candidate summaries
         * @return path to summary, empty when nothing is reusable
         */
        private static Map<Path, CuSummary> reusableByPath(List<CuSummary> reusable) {
            if (reusable == null || reusable.isEmpty()) {
                return Map.of();
            }
            Map<Path, CuSummary> byPath = new java.util.HashMap<>(reusable.size() * 2);
            for (CuSummary summary : reusable) {
                if (summary != null && summary.file() != null) {
                    byPath.put(summary.file(), summary);
                }
            }
            return byPath;
        }

        /**
         * Parses and summarises one file. Never throws: a pathological file becomes a problem entry
         * so the rest of the project still gets indexed.
         *
         * @param fingerprint file to process
         * @param module module the file belongs to, {@code ""} when unknown
         * @return summary plus an optional diagnostic
         */
        private FileResult parseOne(FileFingerprint fingerprint, String module) {
            try {
                if (isKotlin(fingerprint.path())) {
                    // Decoded through the same fallback as Java: a GBK-encoded Kotlin file used to be
                    // read as UTF-8 with replacement, which silently renamed every non-ASCII identifier
                    // it contained.
                    SourceText source = SourceText.read(fingerprint.path());
                    return new FileResult(kotlinSummarizer.build(fingerprint.path(), module, source.text(),
                            fingerprint.size(), fingerprint.lastModifiedMillis()), null, source);
                }
                ParseOutcome outcome = parser.parse(fingerprint.path());
                IndexMetadata.ParseProblem problem = outcome.clean() ? null
                        : new IndexMetadata.ParseProblem(fingerprint.path().toString(), "syntax",
                                String.join("; ", outcome.problems()));
                return new FileResult(summarizer.build(fingerprint.path(), module, outcome, fingerprint.size(),
                        fingerprint.lastModifiedMillis()), problem, outcome.source());
            }
            catch (IOException e) {
                return new FileResult(emptySummary(fingerprint, module), new IndexMetadata.ParseProblem(
                        fingerprint.path().toString(), "unreadable", String.valueOf(e.getMessage())), null);
            }
            catch (RuntimeException e) {
                return new FileResult(emptySummary(fingerprint, module), new IndexMetadata.ParseProblem(
                        fingerprint.path().toString(), "parser-failure",
                        e.getClass().getSimpleName() + ": " + e.getMessage()), null);
            }
        }

        private static CuSummary emptySummary(FileFingerprint fingerprint, String module) {
            return new CuSummary(fingerprint.path(), module, "", List.of(), List.of(), Set.of(), Set.of(),
                    List.of("文件无法解析"), fingerprint.lastModifiedMillis(), fingerprint.size());
        }

        private static boolean isKotlin(Path path) {
            String name = path.getFileName() == null ? "" : path.getFileName().toString();
            return name.toLowerCase(java.util.Locale.ROOT).endsWith(".kt");
        }

        /** Result of processing one file. */
        private record FileResult(CuSummary summary, IndexMetadata.ParseProblem problem, SourceText source) {
        }

        /**
         * One file that was not plain UTF-8.
         *
         * @param file source file
         * @param encoding encoding that decoded it
         * @param lossy whether characters had to be replaced
         */
        private record EncodingUse(Path file, String encoding, boolean lossy) {
        }
    }
}

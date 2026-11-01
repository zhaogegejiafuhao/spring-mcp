package io.github.springmcp.core.analyze;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.function.Function;

import io.github.springmcp.core.index.AnnotationArg;
import io.github.springmcp.core.index.AnnotationUse;
import io.github.springmcp.core.index.CuSummary;
import io.github.springmcp.core.index.MethodSummary;
import io.github.springmcp.core.index.ParameterSummary;
import io.github.springmcp.core.index.TypeSummary;
import io.github.springmcp.core.resolve.ConstantResolver;
import io.github.springmcp.core.resolve.SuperTypeGraph;
import io.github.springmcp.core.resolve.TypeNameResolver;
import io.github.springmcp.core.util.PathJoiner;
import io.github.springmcp.spi.model.Controllers;

/**
 * Extracts HTTP endpoints from {@code @Controller} and {@code @RestController} classes.
 *
 * <p>
 * Inheritance is followed, because Spring follows it: a controller that extends an abstract base
 * class inherits that class's {@code @RequestMapping} prefix and every mapped handler the base
 * declares. Ignoring that was not a missing feature but a wrong answer — the endpoints simply
 * disappeared, with nothing in the output to suggest they existed. The common
 * {@code class OwnerController extends BaseCrudController<Owner>} pattern is exactly the case that
 * used to vanish.
 * </p>
 *
 * <p>
 * Paths are trusted when they are literals <em>or</em> fold to one. {@code @GetMapping(ApiConst.BASE
 * + "/x")} is resolved by looking the constant up in the same index ({@link ConstantResolver}); only
 * when the expression genuinely depends on run-time state — {@code "${version}"}, an injected value,
 * a call — is the endpoint reported as unresolved with the raw text preserved. That is much more
 * useful than either dropping the endpoint or inventing a path: the caller can see the controller
 * exists, the handler name, the file and the line, and knows to look at the constant itself.
 * </p>
 */
public final class ControllerAnalyzer {

    private static final Set<String> CONTROLLER_ANNOTATIONS = Set.of("RestController", "Controller");

    /** The marker half of {@code @RestController}; a class that declares it maps methods too. */
    private static final String RESPONSE_BODY_ANNOTATION = "ResponseBody";

    /**
     * A path fragment evaluated at run time rather than written out: Spring's {@code ${...}} or
     * Kotlin's {@code $name} template.
     *
     * <p>
     * Deliberately narrower than "contains {@code $}". A regex constraint may legitimately end with
     * the end-anchor {@code $}, as in {@code {login:^[_.@A-Za-z0-9-]*$}}; treating that as a
     * placeholder reported a fully resolved path as unknown, which is the one thing this tool must
     * not do — a caller cannot tell it apart from a path that really is undecidable.
     * </p>
     */
    private static final Pattern TEMPLATE = Pattern.compile("\\$\\{|\\$[A-Za-z_]");

    /** Verb implied by each shortcut annotation. */
    private static final Map<String, String> VERB_BY_ANNOTATION = Map.of("GetMapping", "GET", "PostMapping",
            "POST", "PutMapping", "PUT", "DeleteMapping", "DELETE", "PatchMapping", "PATCH");

    /** Token reported when a {@code @RequestMapping} does not restrict the HTTP verb. */
    public static final String ANY_VERB = "ANY";

    /**
     * Bound on how far up the hierarchy one controller is walked.
     *
     * <p>
     * A hand-written hierarchy deeper than this is pathological, and the walk is per controller, so an
     * unbounded BFS over 100 controllers on a generated code base would cost more than it can
     * possibly return.
     * </p>
     */
    private static final int MAX_SUPER_TYPES = 24;

    /** How many controller names one warning may list before it summarises the rest. */
    private static final int MAX_NAMES_IN_WARNING = 8;

    /** Types whose presence means a global path prefix may be configured. */
    private static final Set<String> PREFIX_CONFIGURER_TYPES = Set.of("WebMvcConfigurer", "WebFluxConfigurer",
            "WebMvcConfigurerAdapter");

    /** Every Spring Boot conditional annotation starts with this; {@code @Profile} is the other one. */
    private static final String CONDITIONAL_PREFIX = "Conditional";

    private final SuperTypeGraph superTypes;

    private final ConstantResolver constants;

    private final Function<Path, CuSummary> contextOf;

    /**
     * @param superTypes used to walk {@code extends} / {@code implements} chains; the class-level
     * prefix and inherited handlers are both found that way
     * @param constants folds compile-time constants in mapping paths; may be {@code null} to keep
     * every non-literal path unresolved
     * @param contextOf looks up the file summary of a declaration, which is what import-aware
     * constant resolution needs; may return {@code null}
     */
    public ControllerAnalyzer(SuperTypeGraph superTypes, ConstantResolver constants,
            Function<Path, CuSummary> contextOf) {
        this.superTypes = superTypes;
        this.constants = constants;
        this.contextOf = contextOf;
    }

    /**
     * Analyses every controller in the project.
     * @param types all types in the index
     * @param query caller filters
     * @return the report; never {@code null}
     */
    public Controllers.Report analyze(List<TypeSummary> types, Controllers.Query query) {
        List<Controllers.Endpoint> endpoints = new ArrayList<>();
        int controllerCount = 0;
        int methodCount = 0;
        int unresolvedPaths = 0;
        int inheritedHandlers = 0;
        List<String> withoutHandlers = new ArrayList<>();
        List<String> inferredControllers = new ArrayList<>();

        for (TypeSummary type : types) {
            boolean declared = isController(type);
            List<Handler> handlers = handlerMethodsOf(type);
            if (!declared && !isImplicitController(type, handlers)) {
                continue;
            }
            controllerCount++;
            if (!declared) {
                inferredControllers.add(type.simpleName());
            }
            if (handlers.isEmpty()) {
                // A @Controller with no HTTP mapping is not an empty controller: it is usually an
                // RSocket @MessageMapping endpoint or a view-only class. Counting it as a controller
                // and showing nothing for it reads as "serves nothing", so it is named instead.
                withoutHandlers.add(type.simpleName());
                continue;
            }

            ClassPath classPath = classPathOf(type);
            for (Handler handler : handlers) {
                methodCount++;
                if (!handler.declaredIn().equals(type.fqn())) {
                    inheritedHandlers++;
                }

                AnnotationUse mapping = handler.mapping();
                PathSpec methodPath = pathsOf(mapping, handler.file(), handler.declaredIn());
                List<String> verbs = verbsOf(mapping);

                if (!classPath.paths().resolved() || !methodPath.resolved()) {
                    unresolvedPaths++;
                }

                List<String> conditions = conditionsOf(type, handler.method());
                List<String> joined = !classPath.paths().resolved() || !methodPath.resolved()
                        ? List.of(PathJoiner.rawConcat(classPath.paths().rawFirst(), methodPath.rawFirst()))
                        : PathJoiner.combinations(classPath.paths().paths(), methodPath.paths());
                for (String path : joined) {
                    boolean literal = classPath.paths().resolved() && methodPath.resolved();
                    // A prefix filter cannot be applied to an unresolved expression, so such
                    // endpoints are kept rather than silently dropped.
                    if (literal && query.basePathFilter() != null
                            && !path.startsWith(PathJoiner.cleanFilter(query.basePathFilter()))) {
                        continue;
                    }
                    List<String> effectiveVerbs = filterVerbs(verbs, query.httpMethod());
                    if (effectiveVerbs.isEmpty()) {
                        continue;
                    }
                    endpoints.add(new Controllers.Endpoint(type.fqn(), rawOf(classPath.annotation()),
                            classPath.paths().resolved(), effectiveVerbs, path,
                            classPath.paths().resolved() && methodPath.resolved(),
                            classPath.paths().resolved() ? methodPath.reason() : classPath.paths().reason(),
                            handler.method().name(),
                            handler.method().signature(), handler.file().toString(), handler.method().line(),
                            handler.declaredIn(), conditions));
                }
            }
        }

        endpoints.sort(Comparator.comparing(Controllers.Endpoint::file)
            .thenComparingInt(Controllers.Endpoint::line)
            .thenComparing(Controllers.Endpoint::path));

        List<String> prefixConfigurers = pathPrefixConfigurers(types);
        List<String> conditionNames = endpoints.stream()
            .flatMap(e -> e.conditions().stream())
            .distinct()
            .sorted()
            .toList();
        long conditioned = endpoints.stream().filter(e -> !e.conditions().isEmpty()).count();
        List<String> warnings = new ArrayList<>();
        if (unresolvedPaths > 0) {
            warnings.add(unresolvedPaths
                    + " 个 handler 的映射路径无法静态求值（占位符、注入值或拼接），已按原文返回，pathResolved=false");
        }
        if (inheritedHandlers > 0) {
            warnings.add(inheritedHandlers + " 个 handler 继承自父类或接口，其 file/line 指向声明处，可用 handlerDeclaredIn 区分");
        }
        if (!withoutHandlers.isEmpty()) {
            // Nested test controllers share a simple name, so repeating "ServerController" eight times
            // tells the reader nothing. Distinct names, with the duplicate count kept.
            List<String> distinct = withoutHandlers.stream().distinct().sorted().toList();
            String listed = sample(distinct);
            if (distinct.size() < withoutHandlers.size()) {
                listed += "（同名 " + (withoutHandlers.size() - distinct.size()) + " 个）";
            }
            warnings.add(withoutHandlers.size()
                    + " 个 controller 没有任何 HTTP 映射（常见于 RSocket 的 @MessageMapping 或纯视图控制器），不计入端点"
                    + nonSpringHint(withoutHandlers.size(), controllerCount) + ": "
                    + listed);
        }

        if (conditioned > 0) {
            warnings.add(conditioned + " 个端点受条件装配控制（" + sample(conditionNames)
                    + "），是否注册取决于运行环境；conditions 字段逐条给出条件");
        }
        if (!inferredControllers.isEmpty()) {
            warnings.add(inferredControllers.size()
                    + " 个类带方法级映射注解但没有 @Controller/@RestController，按 @ResponseBody 或自定义 *Controller "
                    + "注解推断为控制器（常见于自定义 RequestMappingHandlerMapping）："
                    + sample(inferredControllers.stream().distinct().sorted().toList()));
        }
        if (!prefixConfigurers.isEmpty()) {
            warnings.add("发现 " + prefixConfigurers.size()
                    + " 处可能改写全局路径前缀的配置（WebMvcRegistrations / configurePathMatch）："
                    + "这里给的是注解里的路径，外部实际 URL 可能还带一层前缀，网关与前端看到的是后者: "
                    + sample(prefixConfigurers.stream().distinct().sorted().toList()));
        }

        return new Controllers.Report(endpoints, controllerCount, methodCount, warnings);
    }

    /**
     * Finds configuration that rewrites every path below a prefix.
     *
     * <p>
     * {@code WebMvcRegistrations.setPathPrefixes} and {@code configurePathMatch} add a prefix that no
     * annotation mentions — the prefix comes from configuration, so it cannot be resolved here. It is
     * reported instead of ignored: a caller comparing the output with what the front end calls would
     * otherwise see every URL "missing a segment" and have no way to know why.
     * </p>
     *
     * @param types every type in the index
     * @return declarations that may be adding a prefix, in index order
     */
    private static List<String> pathPrefixConfigurers(List<TypeSummary> types) {
        List<String> found = new ArrayList<>();
        for (TypeSummary type : types) {
            boolean configurer = false;
            for (String raw : type.rawSuperTypes()) {
                String name = TypeNameResolver.stripTypeArguments(raw);
                if (PREFIX_CONFIGURER_TYPES.contains(simpleNameOf(name))) {
                    configurer = true;
                }
            }
            boolean registrations = type.methods()
                .stream()
                .anyMatch(m -> !m.constructor() && m.returnTypeRaw() != null
                        && "WebMvcRegistrations".equals(simpleNameOf(m.returnTypeRaw())));
            if (registrations || configurer && type.method("configurePathMatch") != null) {
                found.add(type.fqn());
            }
        }
        return found;
    }

    private static String simpleNameOf(String typeName) {
        String name = typeName == null ? "" : typeName.trim();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(dot + 1);
    }

    /**
     * Collects the annotations that decide whether an endpoint exists at run time.
     *
     * <p>
     * {@code @Profile("dev")} and {@code @ConditionalOnProperty} do not change the URL, they decide
     * whether the handler is registered at all. Evaluating them would need the active environment,
     * which a static tool does not have; reporting them as written is the honest middle ground — a
     * caller comparing the list with production traffic then knows which entries may legitimately be
     * absent.
     * </p>
     *
     * @param controller the controller type
     * @param handler the handler method
     * @return distinct rendered conditions, sorted, empty when the endpoint is unconditional
     */
    private static List<String> conditionsOf(TypeSummary controller, MethodSummary handler) {
        List<String> out = new ArrayList<>();
        collectConditions(controller.annotations(), out);
        collectConditions(handler.annotations(), out);
        return out.stream().distinct().sorted().toList();
    }

    private static void collectConditions(List<AnnotationUse> annotations, List<String> out) {
        for (AnnotationUse annotation : annotations) {
            String simple = annotation.simpleName();
            if ("Profile".equals(simple) || simple.startsWith(CONDITIONAL_PREFIX)) {
                out.add(render(annotation));
            }
        }
    }

    /**
     * @param annotation annotation usage
     * @return its source form, e.g. {@code @Profile("dev")}; string literals are re-quoted because
     * {@link AnnotationArg} stores their content, not their source
     */
    private static String render(AnnotationUse annotation) {
        if (annotation.args().isEmpty()) {
            return "@" + annotation.simpleName();
        }
        StringBuilder text = new StringBuilder("@").append(annotation.simpleName()).append('(');
        List<AnnotationArg> args = annotation.args();
        for (int i = 0; i < args.size(); i++) {
            AnnotationArg arg = args.get(i);
            if (i > 0) {
                text.append(", ");
            }
            if (!"value".equals(arg.key())) {
                text.append(arg.key()).append('=');
            }
            if (arg.allStringLiterals()) {
                text.append(arg.values().stream().map(v -> "\"" + v + "\"").collect(java.util.stream.Collectors
                    .joining(", ")));
            }
            else {
                text.append(String.join(", ", arg.values()));
            }
        }
        return text.append(')').toString();
    }

    /**
     * Renders at most {@link #MAX_NAMES_IN_WARNING} names, so a project with fifty RSocket controllers
     * does not turn one warning into a wall of text.
     *
     * @param names sorted names
     * @return the first few, with a count for the rest
     */
    private static String sample(List<String> names) {
        if (names.size() <= MAX_NAMES_IN_WARNING) {
            return String.join(", ", names);
        }
        return String.join(", ", names.subList(0, MAX_NAMES_IN_WARNING))
                + " 等 " + names.size() + " 个";
    }

    /**
     * Explains a mass of mapping-less controllers when that mass suggests the project is not Spring MVC.
     *
     * <p>
     * Solon marks its classes with {@code @Controller} too, but maps methods with its own
     * {@code @Mapping}. Counting 67 such classes and reporting zero endpoints is technically right and
     * reads like a broken scanner — three separate reviewers asked "did it miss the routes?" on exactly
     * that repository. The count is the tell: a Spring project with a handful of view controllers has a
     * handful, not dozens out of dozens.
     * </p>
     *
     * @param withoutHandlers controllers with no Spring mapping
     * @param controllerCount all controllers found
     * @return a clause to append, empty when the count is unremarkable
     */
    private static String nonSpringHint(int withoutHandlers, int controllerCount) {
        if (withoutHandlers < 10 || withoutHandlers * 2 < controllerCount) {
            return "";
        }
        return "；数量这么大时，也可能这些类用的是别的 Web 框架（如 Solon 的 @Mapping），"
                + "本工具只认 Spring 的映射注解，所以看起来像「有控制器、没端点」";
    }

    private static boolean isController(TypeSummary type) {
        for (String a : CONTROLLER_ANNOTATIONS) {
            if (type.hasAnnotation(a)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Recognises a controller that does not carry {@code @Controller}.
     *
     * <p>
     * Spring's default {@code RequestMappingHandlerMapping} only accepts types annotated with
     * {@code @Controller} or {@code @RequestMapping}, but a project may register its own mapping —
     * spring-boot-admin does exactly that: its controllers carry {@code @AdminController} (a bare
     * marker) plus {@code @ResponseBody}, and a custom {@code RequestMappingHandlerMapping} picks them
     * up. Ignoring them reports zero endpoints for a server whose REST API is the whole point.
     * </p>
     *
     * <p>
     * Two shapes are accepted, both grounded in something Spring itself would honour: a class-level
     * {@code @ResponseBody} — which is literally the second half of {@code @RestController} — or an
     * annotation named {@code ...Controller}, which is what such marker annotations are always called.
     * Requiring at least one mapping method keeps interfaces, DTOs and stray annotations out.
     * </p>
     *
     * @param type candidate type
     * @param handlers methods of it that carry an HTTP mapping annotation
     * @return whether it should be treated as a controller
     */
    private static boolean isImplicitController(TypeSummary type, List<Handler> handlers) {
        if (handlers.isEmpty()) {
            return false;
        }
        if (type.hasAnnotation(RESPONSE_BODY_ANNOTATION)) {
            return true;
        }
        for (AnnotationUse annotation : type.annotations()) {
            if (annotation.simpleName().endsWith("Controller")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Finds the class-level {@code @RequestMapping} that applies to a controller.
     *
     * <p>
     * Spring resolves it with {@code findMergedAnnotation}, which searches the type hierarchy and
     * returns the first match — the nearest declaration wins, and multiple prefixes are not
     * concatenated. Walking in the same order keeps the composed URL identical to what the running
     * application serves.
     * </p>
     *
     * @param controller controller type
     * @return its effective class-level path
     */
    private ClassPath classPathOf(TypeSummary controller) {
        for (TypeSummary current : hierarchy(controller)) {
            AnnotationUse annotation = current.annotation("RequestMapping");
            if (annotation != null) {
                return new ClassPath(pathsOf(annotation, current.file(), current.fqn()), annotation);
            }
        }
        return new ClassPath(pathsOf(null, controller.file(), controller.fqn()), null);
    }

    /**
     * The controller followed by its supertypes, nearest first.
     *
     * @param controller controller type
     * @return declarations in the order Spring would consult them
     */
    private List<TypeSummary> hierarchy(TypeSummary controller) {
        List<TypeSummary> out = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        Deque<TypeSummary> queue = new ArrayDeque<>();
        queue.add(controller);
        while (!queue.isEmpty() && out.size() < MAX_SUPER_TYPES) {
            TypeSummary current = queue.poll();
            if (!visited.add(current.fqn())) {
                continue;
            }
            out.add(current);
            for (String parent : superTypes.directSuperTypes(current.fqn())) {
                TypeSummary resolved = superTypes.typeByFqn(parent);
                if (resolved != null && !visited.contains(resolved.fqn())) {
                    queue.add(resolved);
                }
            }
        }
        return out;
    }

    /**
     * Collects the mapped handlers of a controller, including the ones it inherits.
     *
     * <p>
     * Declarations are grouped by signature, and the nearest declaration that carries a mapping wins.
     * That is the behaviour a reader expects: an override that re-declares {@code @GetMapping} takes
     * effect, an override that merely delegates does not hide the base class mapping, and a base
     * class method that the subclass never mentions still shows up.
     * </p>
     *
     * @param controller controller type
     * @return handlers in hierarchy order
     */
    private List<Handler> handlerMethodsOf(TypeSummary controller) {
        Map<String, Handler> bySignature = new LinkedHashMap<>();

        for (TypeSummary current : hierarchy(controller)) {
            for (MethodSummary method : current.methods()) {
                if (method.constructor()) {
                    continue;
                }
                AnnotationUse mapping = mappingAnnotation(method);
                if (mapping == null) {
                    continue;
                }
                bySignature.putIfAbsent(signatureKey(method),
                        new Handler(current.fqn(), method, mapping, current.file()));
            }
        }
        return List.copyOf(bySignature.values());
    }

    /**
     * Key that identifies an overridden method across the hierarchy.
     * @param method declaration
     * @return name plus erased parameter types, which is stable enough without a type environment
     */
    private static String signatureKey(MethodSummary method) {
        StringBuilder sb = new StringBuilder(method.name()).append('(');
        boolean first = true;
        for (ParameterSummary parameter : method.parameters()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append(parameter.typeRaw());
        }
        return sb.append(')').toString();
    }

    private static AnnotationUse mappingAnnotation(MethodSummary method) {
        for (AnnotationUse a : method.annotations()) {
            if (VERB_BY_ANNOTATION.containsKey(a.simpleName()) || "RequestMapping".equals(a.simpleName())) {
                return a;
            }
        }
        return null;
    }

    private static List<String> verbsOf(AnnotationUse mapping) {
        String simple = mapping.simpleName();
        String fixed = VERB_BY_ANNOTATION.get(simple);
        if (fixed != null) {
            return List.of(fixed);
        }
        // @RequestMapping(method = RequestMethod.GET) or method = {GET, POST}
        AnnotationArg methodArg = mapping.arg("method");
        if (methodArg == null || methodArg.values().isEmpty()) {
            return List.of(ANY_VERB);
        }
        Set<String> verbs = new LinkedHashSet<>();
        for (String raw : methodArg.values()) {
            String v = raw;
            int dot = v.lastIndexOf('.');
            if (dot >= 0) {
                v = v.substring(dot + 1);
            }
            v = v.replace("\"", "").trim().toUpperCase(Locale.ROOT);
            if (!v.isEmpty()) {
                verbs.add(v);
            }
        }
        return verbs.isEmpty() ? List.of(ANY_VERB) : List.copyOf(verbs);
    }

    private static List<String> filterVerbs(List<String> verbs, String wanted) {
        if (wanted == null) {
            return verbs;
        }
        if (verbs.contains(wanted) || verbs.contains(ANY_VERB)) {
            return List.of(wanted);
        }
        return List.of();
    }

    private static String rawOf(AnnotationUse annotation) {
        if (annotation == null) {
            return "";
        }
        AnnotationArg arg = annotation.arg("path") != null ? annotation.arg("path") : annotation.value();
        if (arg == null) {
            return "";
        }
        return String.join(", ", arg.values());
    }

    /**
     * One mapped handler together with where it was declared.
     *
     * @param declaredIn fully qualified type that declared the method
     * @param method the declaration
     * @param mapping the mapping annotation that made it a handler
     * @param file file holding the declaration
     */
    private record Handler(String declaredIn, MethodSummary method, AnnotationUse mapping,
            java.nio.file.Path file) {
    }

    /**
     * Effective class-level prefix of a controller.
     *
     * @param paths resolved (or explicitly unresolved) prefix
     * @param annotation the annotation it came from, {@code null} when the controller has none
     */
    private record ClassPath(PathSpec paths, AnnotationUse annotation) {
    }

    /**
     * Resolved (or explicitly unresolved) path list of a mapping annotation.
     *
     * @param paths one or more path strings
     * @param resolved whether every path is a literal string
     * @param reason why it is unresolved: {@code LITERAL_CONSTANT}, {@code PLACEHOLDER} or
     * {@code CONCATENATION}
     */
    private record PathSpec(List<String> paths, boolean resolved, String reason) {

        /**
         * @return the single fragment to show when the path is not a literal; {@code ""} when the
         * annotation declared no path at all
         */
        String rawFirst() {
            return paths.isEmpty() ? "" : paths.get(0);
        }
    }

    private PathSpec pathsOf(AnnotationUse annotation, Path file, String ownerFqn) {
        if (annotation == null) {
            return new PathSpec(List.of(""), true, null);
        }
        AnnotationArg arg = annotation.arg("path");
        if (arg == null) {
            arg = annotation.value();
        }
        if (arg == null || arg.values().isEmpty()) {
            return new PathSpec(List.of(""), true, null);
        }
        if (arg.allStringLiterals()) {
            for (String v : arg.values()) {
                if (v.contains("${")) {
                    return new PathSpec(List.of(v), false, "PLACEHOLDER");
                }
            }
            return new PathSpec(arg.values(), true, null);
        }
        PathSpec folded = fold(arg.values(), file, ownerFqn);
        if (folded != null) {
            return folded;
        }
        String raw = String.join(", ", arg.values());
        // Order matters: a Kotlin interpolation is rendered as ${...} and is a placeholder even when
        // it sits next to a concatenation, so it is checked before the plus sign.
        String reason = raw.contains("${") ? "PLACEHOLDER"
                : (raw.contains("+") ? "CONCATENATION" : "LITERAL_CONSTANT");
        return new PathSpec(List.of(raw), false, reason);
    }

    /**
     * Folds non-literal arguments through {@link ConstantResolver}.
     *
     * <p>
     * A fold that still contains {@code $} is not a resolved path: {@code ${...}} is evaluated by the
     * property resolver at start-up, and Kotlin's {@code "$base/x"} template is string concatenation
     * written differently. Reporting either as the route would be wrong about what the application
     * serves.
     * </p>
     *
     * @param values raw argument expressions
     * @param file file the annotation was declared in
     * @param ownerFqn type declaring the annotation, used to resolve an unqualified constant name
     * @return the resolved paths, or {@code null} when any argument is not a compile-time constant
     */
    private PathSpec fold(List<String> values, Path file, String ownerFqn) {
        if (constants == null) {
            return null;
        }
        CuSummary context = file == null || contextOf == null ? null : contextOf.apply(file);
        List<String> folded = new ArrayList<>(values.size());
        for (String value : values) {
            String resolved = constants.evaluate(value, context, ownerFqn);
            if (resolved == null) {
                return null;
            }
            folded.add(resolved);
        }
        for (String value : folded) {
            if (TEMPLATE.matcher(value).find()) {
                return new PathSpec(List.of(value), false, "PLACEHOLDER");
            }
        }
        return new PathSpec(folded, true, null);
    }
}

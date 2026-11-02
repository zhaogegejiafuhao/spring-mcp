package io.github.springmcp.core.analyze;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.MethodReferenceExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;

import io.github.springmcp.core.index.CuSummary;
import io.github.springmcp.core.kotlin.KotlinRouterFunctionScanner;
import io.github.springmcp.core.parse.AstCache;
import io.github.springmcp.core.parse.SourceText;
import io.github.springmcp.core.util.TypeFqns;
import io.github.springmcp.spi.model.Controllers;

/**
 * Finds endpoints declared with Spring's functional routing API.
 *
 * <p>
 * {@code RouterFunctions.route().GET("/x", handler)} and {@code RouterFunctions.route(predicate,
 * handler)} are endpoints like any other, but no annotation marks them, so an annotation-only reader
 * reports the module as having none. That is the exact failure this project refuses: an answer that
 * looks complete while a whole routing style is missing from it.
 * </p>
 *
 * <p>
 * Only routes with a <em>literal</em> path are reported. A {@code route(this::acceptsXml, ...)} filter
 * bean has no URL to report, and inventing one would be worse than saying nothing; the count of those
 * is returned so the caller can mention them.
 * </p>
 *
 * <p>
 * {@code nest(...)} contributes a prefix to every route inside it, and ignoring it produces the worst
 * possible answer: a path that looks right, is served nowhere, and carries no marker. Measured on
 * halo (1,383 files, 198 functional endpoints): every single URL was missing its
 * {@code /apis/<group>/<version>} prefix and nothing in the output said so. So a literal prefix is
 * applied, and a prefix that cannot be read — {@code path("/apis/" + gv)} — leaves the routes marked
 * unresolved rather than complete.
 * </p>
 */
public final class RouterFunctionAnalyzer {

    /** Verb-named builder methods of {@code RouterFunctions.Builder}. */
    private static final Set<String> VERBS = Set.of("GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS");

    /** Bound on how far a call's scope chain is followed. */
    private static final int MAX_SCOPE_HOPS = 20;

    /** How many locations a warning may name before it becomes noise. */
    private static final int MAX_PREFIX_SAMPLES = 3;

    private final AstCache astCache;

    /**
     * @param astCache shared parse cache; only files that mention the routing API are parsed
     */
    public RouterFunctionAnalyzer(AstCache astCache) {
        this.astCache = astCache;
    }

    /**
     * @param endpoints routes with a literal path
     * @param unexpanded {@code route(...)} calls that carry no literal path, e.g. a predicate held in
     * a method reference
     * @param unresolvedPrefixes endpoints sitting under a {@code nest(...)} whose prefix could not be
     * read; their {@code path} is missing that prefix and they are marked unresolved
     * @param prefixSamples where those unresolved nests are, at most {@value #MAX_PREFIX_SAMPLES}
     * @param structuralNests {@code nest(...)} calls whose routes live somewhere else (a variable, or
     * another class's method); the prefix cannot be attributed to any endpoint here, so all this pass
     * can do is say the prefix exists and was not applied
     */
    public record Result(List<Controllers.Endpoint> endpoints, int unexpanded, int unresolvedPrefixes,
            List<String> prefixSamples, List<String> structuralNests) {

        public Result {
            endpoints = endpoints == null ? List.of() : List.copyOf(endpoints);
            prefixSamples = prefixSamples == null ? List.of() : List.copyOf(prefixSamples);
            structuralNests = structuralNests == null ? List.of() : List.copyOf(structuralNests);
        }
    }

    /**
     * Counters shared by the per-file scan.
     *
     * <p>
     * A nested prefix is counted per endpoint, not per {@code nest} call: one nesting can cover a
     * hundred routes, and "100 endpoints are missing a prefix" is the sentence a reader can act on.
     * </p>
     */
    private static final class Tally {

        private int unexpanded;

        private int unresolvedPrefixes;

        private final List<String> prefixSamples = new ArrayList<>();

        private final List<String> structuralNests = new ArrayList<>();
    }

    /**
     * Scans every indexed file that could declare a router function.
     *
     * @param root project root
     * @param byFile per-file summaries of the snapshot
     * @return endpoints in a stable order
     */
    public Result scan(Path root, Map<Path, CuSummary> byFile) {
        List<Controllers.Endpoint> endpoints = new ArrayList<>();
        Tally tally = new Tally();
        for (CuSummary cu : byFile.values()) {
            if (!mentionsRouter(cu)) {
                continue;
            }
            if (cu.file().toString().endsWith(".kt")) {
                // The Kotlin scanner reads the same builder chains but not `nest { }`. Marking the
                // routes unresolved is the only honest option: without it the paths look complete
                // while a whole prefix is missing.
                boolean nested = mentionsNest(cu.file());
                for (KotlinRouterFunctionScanner.Route route : KotlinRouterFunctionScanner.scan(cu.file())) {
                    Controllers.Endpoint endpoint = toEndpoint(route, cu.file().toString(), cu.packageName());
                    endpoints.add(nested ? withoutPrefix(endpoint, cu.file().toString(), route.line(), tally)
                            : endpoint);
                }
                continue;
            }
            CompilationUnit ast;
            try {
                ast = astCache.obtain(cu.file(), cu.lastModifiedMillis());
            }
            catch (Exception e) {
                // An unparseable file contributes nothing; the annotation controller pass still runs.
                continue;
            }
            if (ast == null) {
                continue;
            }
            collect(ast, cu.file().toString(), endpoints, tally);
        }
        endpoints.sort(Comparator.comparing(Controllers.Endpoint::file)
            .thenComparingInt(Controllers.Endpoint::line)
            .thenComparing(Controllers.Endpoint::path));
        return new Result(endpoints, tally.unexpanded, tally.unresolvedPrefixes, tally.prefixSamples,
                tally.structuralNests);
    }

    /**
     * @param file a Kotlin source file
     * @return whether it contains a nesting call the token scanner does not model
     */
    private static boolean mentionsNest(Path file) {
        try {
            return SourceText.read(file).text().contains(".nest");
        }
        catch (Exception e) {
            return false;
        }
    }

    /**
     * @param cu file summary
     * @return whether the file could declare functional routes
     */
    private static boolean mentionsRouter(CuSummary cu) {
        return cu.referencedSimpleTypeNames().contains("RouterFunction")
                || cu.referencedSimpleTypeNames().contains("RouterFunctions")
                || cu.referencedSimpleTypeNames().contains("RequestPredicates");
    }

    private void collect(CompilationUnit ast, String file, List<Controllers.Endpoint> out, Tally tally) {
        String packageName = ast.getPackageDeclaration().map(declaration -> declaration.getNameAsString()).orElse("");
        for (MethodCallExpr call : ast.findAll(MethodCallExpr.class)) {
            String name = call.getNameAsString();
            if (VERBS.contains(name)) {
                if (insideRouteBuilder(call)) {
                    Controllers.Endpoint endpoint = chainEndpoint(call, name, file, packageName, tally);
                    if (endpoint == null) {
                        tally.unexpanded++;
                    }
                    else {
                        out.add(endpoint);
                    }
                }
                continue;
            }
            if ("nest".equals(name) && call.getArguments().size() >= 2) {
                // A nest whose supplier holds no route here applies its prefix to a router built
                // somewhere else (halo: CustomEndpointsBuilder wrapping each CustomEndpoint.endpoint()).
                // The prefix is real and every path below is missing it, so it is reported even though
                // it cannot be attached to an endpoint.
                if (!containsRoute(call.getArgument(1))) {
                    tally.structuralNests.add(location(file, call));
                }
                continue;
            }
            if (("route".equals(name) || "andRoute".equals(name)) && call.getArguments().size() == 2) {
                Controllers.Endpoint endpoint = predicateEndpoint(call, file, packageName, tally);
                if (endpoint == null) {
                    tally.unexpanded++;
                }
                else {
                    out.add(endpoint);
                }
            }
        }
    }

    /**
     * @param call a verb-named call
     * @return whether it hangs off a {@code route()} builder
     */
    private static boolean insideRouteBuilder(MethodCallExpr call) {
        Optional<Expression> scope = call.getScope();
        int hops = 0;
        while (scope.isPresent() && hops++ < MAX_SCOPE_HOPS) {
            if (!(scope.get() instanceof MethodCallExpr parent)) {
                return false;
            }
            if ("route".equals(parent.getNameAsString()) && parent.getArguments().isEmpty()) {
                return true;
            }
            scope = parent.getScope();
        }
        return false;
    }

    /**
     * @param router the supplier argument of a {@code nest} call
     * @return whether any route is declared inside it
     */
    private static boolean containsRoute(Expression router) {
        for (MethodCallExpr inner : router.findAll(MethodCallExpr.class)) {
            if (VERBS.contains(inner.getNameAsString()) && insideRouteBuilder(inner)) {
                return true;
            }
        }
        return false;
    }

    /**
     * @param file source file
     * @param call any call
     * @return {@code File.java:42}, the form a reader can jump to
     */
    private static String location(String file, MethodCallExpr call) {
        return Path.of(file).getFileName() + ":" + call.getBegin().map(position -> position.line).orElse(0);
    }

    private Controllers.Endpoint chainEndpoint(MethodCallExpr call, String verb, String file, String packageName,
            Tally tally) {
        String path = literal(call, 0);
        if (path == null) {
            return null;
        }
        String handler = handlerText(call);
        return endpoint(call, verb, path, handler, file, packageName, tally);
    }

    private Controllers.Endpoint predicateEndpoint(MethodCallExpr call, String file, String packageName,
            Tally tally) {
        Expression predicate = call.getArgument(0);
        for (MethodCallExpr inner : predicate.findAll(MethodCallExpr.class)) {
            if (!VERBS.contains(inner.getNameAsString())) {
                continue;
            }
            String path = literal(inner, 0);
            if (path != null) {
                return endpoint(call, inner.getNameAsString(), path, handlerText(call), file, packageName, tally);
            }
        }
        return null;
    }

    private Controllers.Endpoint endpoint(MethodCallExpr call, String verb, String path, String handler, String file,
            String packageName, Tally tally) {
        // Walked by hand: both findAncestor overloads are varargs, and passing a Class or a lambda
        // creates a generic array, which javac reports as an unchecked warning.
        TypeDeclaration<?> owner = null;
        Node current = call;
        while (current != null && owner == null) {
            if (current instanceof TypeDeclaration<?> declaration) {
                owner = declaration;
            }
            current = current.getParentNode().orElse(null);
        }
        String ownerFqn = owner == null ? "" : TypeFqns.of(owner, packageName);
        NestPrefix nested = enclosingPrefix(call);
        String served = joinPaths(nested.literal(), path);
        if (!nested.resolved()) {
            recordUnresolvedPrefix(file, call, nested, tally);
        }
        return new Controllers.Endpoint(ownerFqn, nested.raw(), nested.resolved(), List.of(verb), served,
                nested.resolved(), nested.resolved() ? null : "NESTED_PREFIX", handlerMethod(handler), handler, file,
                call.getBegin().map(position -> position.line).orElse(0), ownerFqn, List.of());
    }

    /**
     * Prefix contributed by the {@code nest(...)} calls an expression sits inside.
     *
     * @param literal the readable parts, outermost first, already joined
     * @param raw the whole prefix expression as written, for the report
     * @param resolved whether every enclosing {@code nest} named a literal prefix
     */
    private record NestPrefix(String literal, String raw, boolean resolved) {
    }

    /**
     * Walks up from a route to every enclosing {@code nest(...)}.
     *
     * <p>
     * The second argument is what decides containment: {@code nest(predicate, routerSupplier)} applies
     * its prefix only to the routes inside that supplier, so a sibling branch of the same builder
     * chain must not inherit it.
     * </p>
     *
     * @param node a route call
     * @return the combined prefix, unresolved when any nesting could not be read
     */
    private static NestPrefix enclosingPrefix(Node node) {
        StringBuilder literal = new StringBuilder();
        List<String> raws = new ArrayList<>();
        boolean resolved = true;
        Node current = node.getParentNode().orElse(null);
        int hops = 0;
        while (current != null && hops++ < MAX_SCOPE_HOPS) {
            if (current instanceof MethodCallExpr call && isNestApplyingTo(call, node)) {
                NestPrefix one = prefixOf(call.getArgument(0));
                if (one.resolved()) {
                    literal.insert(0, one.literal());
                }
                else {
                    resolved = false;
                }
                raws.add(0, one.raw());
            }
            current = current.getParentNode().orElse(null);
        }
        return new NestPrefix(literal.toString(), String.join(" + ", raws), resolved);
    }

    /**
     * @param call a call
     * @param node the route being placed
     * @return whether {@code call} is a {@code nest} whose router argument contains {@code node}
     */
    private static boolean isNestApplyingTo(MethodCallExpr call, Node node) {
        if (!"nest".equals(call.getNameAsString()) || call.getArguments().size() < 2) {
            return false;
        }
        Node router = call.getArgument(1);
        Node current = node;
        while (current != null) {
            if (current == router) {
                return true;
            }
            current = current.getParentNode().orElse(null);
        }
        return false;
    }

    /**
     * @param predicate the first argument of a {@code nest} call
     * @return the literal prefix it names, or an unresolved marker carrying the expression text
     */
    private static NestPrefix prefixOf(Expression predicate) {
        for (MethodCallExpr inner : predicate.findAll(MethodCallExpr.class)) {
            if (!"path".equals(inner.getNameAsString()) || inner.getArguments().isEmpty()) {
                continue;
            }
            Expression argument = inner.getArgument(0);
            if (argument instanceof StringLiteralExpr text) {
                // Same convention as the annotation side: a literal contributes its value, an
                // expression contributes its source text.
                return new NestPrefix(text.asString(), text.asString(), true);
            }
            return new NestPrefix("", render(argument), false);
        }
        // nest(predicateHeldInAVariable, ...) or a pattern we do not model: say so rather than
        // pretend the routes live at the path written inside the supplier.
        return new NestPrefix("", render(predicate), false);
    }

    private static String render(Expression expression) {
        String text = expression.toString().replaceAll("\\s+", " ");
        return text.length() <= 120 ? text : text.substring(0, 117) + "...";
    }

    private static void recordUnresolvedPrefix(String file, MethodCallExpr call, NestPrefix prefix, Tally tally) {
        tally.unresolvedPrefixes++;
        if (tally.prefixSamples.size() >= MAX_PREFIX_SAMPLES) {
            return;
        }
        String one = prefix.raw().isEmpty() ? location(file, call) : location(file, call) + " (" + prefix.raw() + ")";
        if (!tally.prefixSamples.contains(one)) {
            tally.prefixSamples.add(one);
        }
    }

    /**
     * @param prefix literal prefix from {@code nest}, may be empty
     * @param path path written on the route
     * @return the path a client would actually call
     */
    private static String joinPaths(String prefix, String path) {
        if (prefix.isEmpty()) {
            return path;
        }
        if (path.isEmpty()) {
            return prefix;
        }
        if (prefix.endsWith("/") && path.startsWith("/")) {
            return prefix + path.substring(1);
        }
        if (!prefix.endsWith("/") && !path.startsWith("/")) {
            return prefix + "/" + path;
        }
        return prefix + path;
    }

    /**
     * Marks a Kotlin route whose file nests routes the token scanner cannot place.
     *
     * @param endpoint endpoint as scanned
     * @param file source file
     * @param line declaration line
     * @param tally counters to update
     * @return the same endpoint, unresolved
     */
    private static Controllers.Endpoint withoutPrefix(Controllers.Endpoint endpoint, String file, int line,
            Tally tally) {
        tally.unresolvedPrefixes++;
        if (tally.prefixSamples.size() < MAX_PREFIX_SAMPLES) {
            String where = Path.of(file).getFileName() + ":" + line + " (.nest)";
            if (!tally.prefixSamples.contains(where)) {
                tally.prefixSamples.add(where);
            }
        }        return new Controllers.Endpoint(endpoint.controllerFqn(), ".nest", false, endpoint.httpMethods(),
                endpoint.path(), false, "NESTED_PREFIX", endpoint.handlerMethod(), endpoint.signature(),
                endpoint.file(), endpoint.line(), endpoint.handlerDeclaredIn(), endpoint.conditions());
    }

    private static Controllers.Endpoint toEndpoint(KotlinRouterFunctionScanner.Route route, String file,
            String packageName) {
        // The Kotlin scanner works on tokens and knows the simple name; the package comes from the
        // summary, so the field is a usable FQN like every other endpoint's.
        String owner = route.enclosingType().isEmpty() || packageName.isEmpty() ? route.enclosingType()
                : packageName + "." + route.enclosingType();
        return new Controllers.Endpoint(owner, "", true, List.of(route.verb()), route.path(), true, null,
                handlerMethod(route.handler()), route.handler(), file, route.line(), owner, List.of());
    }

    /**
     * @param handler rendered handler expression, e.g. {@code handler::getPerson} or {@code (lambda)}
     * @return the method name when one can be read, otherwise the rendered text
     */
    private static String handlerMethod(String handler) {
        int separator = handler.indexOf("::");
        return separator > 0 ? handler.substring(separator + 2) : handler;
    }

    /**
     * Renders the handler, which is always the last argument: {@code GET(path, handler)} and
     * {@code GET(path, predicate, handler)} both end with it, and picking a fixed index reads the
     * predicate as the handler in the three-argument form.
     *
     * @param call a verb-named or {@code route(...)} call
     * @return rendered handler expression
     */
    private static String handlerText(MethodCallExpr call) {
        if (call.getArguments().isEmpty()) {
            return "";
        }
        Expression argument = call.getArgument(call.getArguments().size() - 1);
        if (argument instanceof MethodReferenceExpr reference) {
            return reference.getScope() + "::" + reference.getIdentifier();
        }
        if (argument instanceof LambdaExpr) {
            return "(lambda)";
        }
        String text = argument.toString().replaceAll("\\s+", " ");
        return text.length() <= 80 ? text : text.substring(0, 77) + "...";
    }

    private static String literal(MethodCallExpr call, int index) {
        if (call.getArguments().size() <= index) {
            return null;
        }
        Expression argument = call.getArgument(index);
        return argument instanceof StringLiteralExpr text ? text.asString() : null;
    }
}

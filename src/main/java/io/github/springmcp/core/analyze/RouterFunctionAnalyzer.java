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
 */
public final class RouterFunctionAnalyzer {

    /** Verb-named builder methods of {@code RouterFunctions.Builder}. */
    private static final Set<String> VERBS = Set.of("GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS");

    /** Bound on how far a call's scope chain is followed. */
    private static final int MAX_SCOPE_HOPS = 20;

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
     */
    public record Result(List<Controllers.Endpoint> endpoints, int unexpanded) {

        public Result {
            endpoints = endpoints == null ? List.of() : List.copyOf(endpoints);
        }
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
        int[] unexpanded = { 0 };
        for (CuSummary cu : byFile.values()) {
            if (!mentionsRouter(cu)) {
                continue;
            }
            if (cu.file().toString().endsWith(".kt")) {
                for (KotlinRouterFunctionScanner.Route route : KotlinRouterFunctionScanner.scan(cu.file())) {
                    endpoints.add(toEndpoint(route, cu.file().toString(), cu.packageName()));
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
            collect(ast, cu.file().toString(), endpoints, unexpanded);
        }
        endpoints.sort(Comparator.comparing(Controllers.Endpoint::file)
            .thenComparingInt(Controllers.Endpoint::line)
            .thenComparing(Controllers.Endpoint::path));
        return new Result(endpoints, unexpanded[0]);
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

    private void collect(CompilationUnit ast, String file, List<Controllers.Endpoint> out, int[] unexpanded) {
        String packageName = ast.getPackageDeclaration().map(declaration -> declaration.getNameAsString()).orElse("");
        for (MethodCallExpr call : ast.findAll(MethodCallExpr.class)) {
            String name = call.getNameAsString();
            if (VERBS.contains(name)) {
                if (insideRouteBuilder(call)) {
                    Controllers.Endpoint endpoint = chainEndpoint(call, name, file, packageName);
                    if (endpoint == null) {
                        unexpanded[0]++;
                    }
                    else {
                        out.add(endpoint);
                    }
                }
                continue;
            }
            if (("route".equals(name) || "andRoute".equals(name)) && call.getArguments().size() == 2) {
                Controllers.Endpoint endpoint = predicateEndpoint(call, file, packageName);
                if (endpoint == null) {
                    unexpanded[0]++;
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

    private Controllers.Endpoint chainEndpoint(MethodCallExpr call, String verb, String file, String packageName) {
        String path = literal(call, 0);
        if (path == null) {
            return null;
        }
        String handler = handlerText(call);
        return endpoint(call, verb, path, handler, file, packageName);
    }

    private Controllers.Endpoint predicateEndpoint(MethodCallExpr call, String file, String packageName) {
        Expression predicate = call.getArgument(0);
        for (MethodCallExpr inner : predicate.findAll(MethodCallExpr.class)) {
            if (!VERBS.contains(inner.getNameAsString())) {
                continue;
            }
            String path = literal(inner, 0);
            if (path != null) {
                return endpoint(call, inner.getNameAsString(), path, handlerText(call), file, packageName);
            }
        }
        return null;
    }

    private Controllers.Endpoint endpoint(MethodCallExpr call, String verb, String path, String handler, String file,
            String packageName) {
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
        return new Controllers.Endpoint(ownerFqn, "", true, List.of(verb), path, true, null, handlerMethod(handler),
                handler, file, call.getBegin().map(position -> position.line).orElse(0), ownerFqn, List.of());
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

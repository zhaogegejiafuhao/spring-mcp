package io.github.springmcp.core.analyze;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;

import io.github.springmcp.core.index.CuSummary;
import io.github.springmcp.core.kotlin.KotlinRouteScanner;
import io.github.springmcp.core.parse.AstCache;
import io.github.springmcp.spi.model.GatewayRoutes;

/**
 * Reads routes defined in code instead of configuration.
 *
 * <p>
 * Spring Cloud Gateway projects increasingly define their route table as a {@code RouteLocator} bean,
 * because it can be typed, tested and composed. Those routes live in no YAML file, so a reader that
 * only parses configuration answers "no gateway" for a gateway that is entirely real. The fix is to
 * read the DSL.
 * </p>
 *
 * <p>
 * Only the two documented forms are recognised: {@code builder.routes().route(...)...uri(...)} in
 * Java and {@code builder.routes { route(...) { ... } }} in Kotlin. A route whose URI or path is
 * built from a constant is still reported with the expression as written — the same rule the rest of
 * this server follows.
 * </p>
 */
public final class RouteLocatorAnalyzer {

    /** Guards against a pathological scope chain; a real one is at most a few calls deep. */
    private static final int MAX_SCOPE_HOPS = 40;

    /** Predicate DSL methods whose arguments describe the match, rendered as {@code Name=value}. */
    private static final Set<String> PREDICATES = Set.of("path", "host", "method", "header", "query", "cookie",
            "after", "before", "between", "remoteAddr", "xforwardedRemoteAddr", "weight", "cloudFoundryRouteService");

    private final AstCache astCache;

    /**
     * @param astCache shared parse cache; only files that mention {@code RouteLocator} are parsed
     */
    public RouteLocatorAnalyzer(AstCache astCache) {
        this.astCache = astCache;
    }

    /**
     * Scans every indexed file that could declare a route locator.
     *
     * @param root project root
     * @param byFile per-file summaries of the snapshot
     * @param modulePaths module paths, used to resolve {@code lb://name}
     * @return routes in a stable order, empty when the project defines none in code
     */
    public List<GatewayRoutes.Route> routes(Path root, Map<Path, CuSummary> byFile, List<String> modulePaths) {
        List<GatewayRoutes.Route> out = new ArrayList<>();
        for (CuSummary cu : byFile.values()) {
            if (!mentionsRouteLocator(cu)) {
                continue;
            }
            String relative = root.relativize(cu.file()).toString().replace('\\', '/');
            if (cu.file().toString().endsWith(".kt")) {
                for (KotlinRouteScanner.Route route : KotlinRouteScanner.scan(cu.file())) {
                    if (route.uri() == null && route.predicates().isEmpty()) {
                        continue;
                    }
                    String serviceId = GatewayRouteAnalyzer.serviceId(route.uri());
                    out.add(new GatewayRoutes.Route(route.id(), route.uri(), serviceId, route.predicates(),
                            route.filters(), GatewayRouteAnalyzer.pathPrefixes(route.predicates()),
                            GatewayRouteAnalyzer.stripPrefix(route.filters()),
                            GatewayRouteAnalyzer.moduleOf(serviceId, modulePaths), relative));
                }
                continue;
            }
            CompilationUnit ast;
            try {
                ast = astCache.obtain(cu.file(), cu.lastModifiedMillis());
            }
            catch (Exception e) {
                // A file that cannot be parsed contributes no routes; the YAML side still answers.
                continue;
            }
            if (ast == null) {
                continue;
            }
            collect(ast, relative, modulePaths, out);
        }
        out.sort(Comparator.comparing(GatewayRoutes.Route::file)
            .thenComparing(route -> route.id() == null ? "" : route.id())
            .thenComparing(route -> route.uri() == null ? "" : route.uri()));
        return out;
    }

    /**
     * @param cu file summary
     * @return whether the file could contain a route locator declaration
     */
    private static boolean mentionsRouteLocator(CuSummary cu) {
        // A RouteLocator bean must name the type it returns, so this is a precise and cheap filter:
        // on spring-boot it narrows 5 700 files down to a handful.
        return cu.referencedSimpleTypeNames().contains("RouteLocator")
                || cu.referencedSimpleTypeNames().contains("RouteLocatorBuilder");
    }

    // ------------------------------------------------------------------ java dsl

    private void collect(CompilationUnit ast, String relative, List<String> modulePaths,
            List<GatewayRoutes.Route> out) {
        Map<String, String> literals = stringVariables(ast);
        for (MethodCallExpr call : ast.findAll(MethodCallExpr.class)) {
            if (!"route".equals(call.getNameAsString()) || !insideRoutesChain(call)) {
                continue;
            }
            LambdaExpr body = lastLambda(call).orElse(null);
            if (body == null) {
                continue;
            }
            String id = call.getArguments()
                .size() >= 2 && call.getArgument(0) instanceof StringLiteralExpr literal ? literal.asString() : null;
            out.add(toRoute(id, body, relative, modulePaths, literals));
        }
    }

    /**
     * Collects {@code String uri = "..."} declarations whose value can be trusted.
     *
     * <p>
     * {@code String url = "..."; ... .uri(url)} is a common way to keep a target in one place, so a
     * literal declared in the same file is substituted. A name that is assigned anywhere else in the
     * file is dropped: a reassignment means the literal is not necessarily the value at the call
     * site, and a wrong URI is worse than an unread one. An injected {@code @Value} field is not a
     * literal and stays reported as the name it is.
     * </p>
     *
     * @param ast compilation unit
     * @return variable name to literal value, empty when nothing qualifies
     */
    private static Map<String, String> stringVariables(CompilationUnit ast) {
        Map<String, String> values = new LinkedHashMap<>();
        Set<String> reassigned = new HashSet<>();
        for (AssignExpr assignment : ast.findAll(AssignExpr.class)) {
            if (assignment.getTarget() instanceof NameExpr name) {
                reassigned.add(name.getNameAsString());
            }
        }
        for (VariableDeclarator declarator : ast.findAll(VariableDeclarator.class)) {
            if (declarator.getInitializer().orElse(null) instanceof StringLiteralExpr literal) {
                values.put(declarator.getNameAsString(), literal.asString());
            }
        }
        values.keySet().removeAll(reassigned);
        return values;
    }

    /**
     * @param call a {@code route(...)} call
     * @return whether it hangs off a {@code routes()} builder
     */
    private static boolean insideRoutesChain(MethodCallExpr call) {
        Optional<Expression> scope = call.getScope();
        int hops = 0;
        while (scope.isPresent() && hops++ < MAX_SCOPE_HOPS) {
            if (!(scope.get() instanceof MethodCallExpr parent)) {
                return false;
            }
            if ("routes".equals(parent.getNameAsString())) {
                return true;
            }
            scope = parent.getScope();
        }
        return false;
    }

    private static Optional<LambdaExpr> lastLambda(MethodCallExpr call) {
        LambdaExpr found = null;
        for (Expression argument : call.getArguments()) {
            if (argument instanceof LambdaExpr lambda) {
                found = lambda;
            }
        }
        return Optional.ofNullable(found);
    }

    private static GatewayRoutes.Route toRoute(String id, LambdaExpr body, String relative,
            List<String> modulePaths, Map<String, String> literals) {
        String uri = null;
        Set<String> predicates = new LinkedHashSet<>();
        Set<String> filters = new LinkedHashSet<>();

        for (MethodCallExpr call : body.findAll(MethodCallExpr.class)) {
            String name = call.getNameAsString();
            if ("uri".equals(name)) {
                uri = renderArgs(call, literals);
            }
            else if (PREDICATES.contains(name)) {
                predicates.add(capitalize(name) + "=" + renderArgs(call, literals));
            }
        }
        for (MethodCallExpr call : body.findAll(MethodCallExpr.class)) {
            if (!"filters".equals(call.getNameAsString())) {
                continue;
            }
            for (Expression argument : call.getArguments()) {
                if (argument instanceof LambdaExpr filterBody) {
                    collectFilters(filterBody, filters, literals);
                }
            }
        }

        List<String> predicateList = List.copyOf(predicates);
        List<String> filterList = List.copyOf(filters);
        return new GatewayRoutes.Route(id, uri, GatewayRouteAnalyzer.serviceId(uri), predicateList, filterList,
                GatewayRouteAnalyzer.pathPrefixes(predicateList), GatewayRouteAnalyzer.stripPrefix(filterList),
                GatewayRouteAnalyzer.moduleOf(GatewayRouteAnalyzer.serviceId(uri), modulePaths), relative);
    }

    private static void collectFilters(LambdaExpr body, Set<String> filters, Map<String, String> literals) {
        for (MethodCallExpr call : body.findAll(MethodCallExpr.class)) {
            String name = call.getNameAsString();
            if ("stripPrefix".equals(name) && call.getArguments().size() == 1
                    && call.getArgument(0) instanceof IntegerLiteralExpr number) {
                filters.add("StripPrefix=" + number.asNumber().intValue());
                continue;
            }
            filters.add(capitalize(name) + "=" + renderArgs(call, literals));
        }
    }

    /**
     * Renders a call's arguments the way the YAML form writes them.
     *
     * <p>
     * String literals lose their quotes; anything computed keeps its source text, so an unresolved
     * value is visible as such instead of being silently dropped or turned into an empty string.
     * </p>
     *
     * @param call call whose arguments are rendered
     * @return comma separated argument text
     */
    private static String renderArgs(MethodCallExpr call, Map<String, String> literals) {
        if (call.getArguments().isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (Expression argument : call.getArguments()) {
            if (out.length() > 0) {
                out.append(',');
            }
            out.append(renderArg(argument, literals));
        }
        return out.toString();
    }

    private static String renderArg(Expression argument, Map<String, String> literals) {
        if (argument instanceof StringLiteralExpr literal) {
            return literal.asString();
        }
        if (argument instanceof NameExpr name && literals.containsKey(name.getNameAsString())) {
            return literals.get(name.getNameAsString());
        }
        return argument.toString();
    }

    private static String capitalize(String name) {
        if (name.isEmpty()) {
            return name;
        }
        return name.substring(0, 1).toUpperCase(Locale.ROOT) + name.substring(1);
    }
}

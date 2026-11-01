package io.github.springmcp.core.analyze;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.fasterxml.jackson.core.JsonLocation;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import io.github.springmcp.core.parse.SourceText;
import io.github.springmcp.spi.model.GatewayRoutes;

/**
 * Reads Spring Cloud Gateway routes out of configuration files and code.
 *
 * <p>
 * The route table is the only description of a microservice system's public surface that a source
 * analysis cannot infer: {@code Path=/mall-admin/**} plus {@code StripPrefix=1} means the URL a client
 * calls is not the URL the service handles. Reading it is cheap — a handful of YAML files — and it turns
 * "here are 245 endpoints" into "here is the front door and where each part leads".
 * </p>
 *
 * <p>
 * Three shapes are recognised, because the property path moved between releases:
 * </p>
 * <ul>
 * <li>{@code spring.cloud.gateway.routes} (Spring Cloud Gateway 3.x)</li>
 * <li>{@code spring.cloud.gateway.server.webflux.routes} (4.x / 2025.x, what mall-swarm uses)</li>
 * <li>{@code spring.cloud.gateway.server.webmvc.routes} (the servlet variant)</li>
 * </ul>
 *
 * <p>
 * Routes built in code ({@code RouteLocator} beans) are read too, by {@link RouteLocatorAnalyzer}, and
 * marked with the file that declares them. A project that moved its routing into Java or Kotlin DSL
 * gets the same table as one that kept it in YAML, and the warnings say which half came from where.
 * </p>
 */
public final class GatewayRouteAnalyzer {

    /** {@code application.yml} plus {@code bootstrap.yml} (older Spring Cloud keeps config there). */
    private static final Pattern CONFIG_FILE = Pattern.compile(
            "(application|bootstrap)[^/\\\\]*\\.ya?ml", Pattern.CASE_INSENSITIVE);

    private static final int MAX_DEPTH = 24;

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    /**
     * Maven's resource-filtering placeholder, {@code @project.version@}.
     *
     * <p>
     * Legal in a Spring YAML file (the build replaces it before packaging) but illegal YAML: a plain
     * scalar may not begin with {@code @}. Jackson therefore rejected the whole document, and a gateway
     * whose routes lived in such a file reported zero routes with only a class name as the reason.
     * Rewriting the token as {@code ${project.version}} keeps the meaning — a value only the build or
     * the environment knows — while leaving a document the parser accepts.
     * </p>
     */
    private static final Pattern MAVEN_FILTER = Pattern.compile("@([A-Za-z0-9_.\\-]+)@");

    /**
     * Reads the routes.
     *
     * @param root project root
     * @param includeTests whether {@code src/test/resources} is scanned too
     * @param modulePaths modules of this project, used to resolve {@code lb://name} to a directory
     * @param codeRoutes routes declared as {@code RouteLocator} beans, already resolved to routes
     * @return routes plus warnings, never {@code null}
     */
    public GatewayRoutes report(Path root, boolean includeTests, List<String> modulePaths,
            List<GatewayRoutes.Route> codeRoutes) {
        return analyze(root, includeTests, modulePaths, codeRoutes);
    }

    private GatewayRoutes analyze(Path root, boolean includeTests, List<String> modulePaths,
            List<GatewayRoutes.Route> codeRoutes) {
        List<String> warnings = new ArrayList<>();
        List<Path> files = findConfigFiles(root, includeTests, warnings);

        List<GatewayRoutes.Route> routes = new ArrayList<>();
        boolean locatorEnabled = false;
        boolean sawGatewaySection = false;

        for (Path file : files) {
            JsonNode doc = read(file, root, warnings);
            if (doc == null) {
                continue;
            }
            JsonNode gateway = doc.path("spring").path("cloud").path("gateway");
            if (gateway.isMissingNode() || !gateway.isObject()) {
                continue;
            }
            sawGatewaySection = true;
            locatorEnabled |= locatorEnabled(gateway);
            collect(gateway, file, root, modulePaths, routes);
            collect(gateway.path("server").path("webflux"), file, root, modulePaths, routes);
            collect(gateway.path("server").path("webmvc"), file, root, modulePaths, routes);
        }

        routes.addAll(codeRoutes);

        if (sawGatewaySection && routes.isEmpty()) {
            warnings.add("配置里有 spring.cloud.gateway，但没有找到 routes，也没有找到 RouteLocator 代码路由；"
                    + "确认路由是否定义在未索引的模块或外部配置中心");
        }
        if (!codeRoutes.isEmpty()) {
            warnings.add(codeRoutes.size() + " 条路由来自代码中的 RouteLocator（Java/Kotlin DSL），file 字段指向声明处");
            long unresolvedUris = codeRoutes.stream()
                .filter(route -> route.uri() == null || !route.uri().contains("://"))
                .count();
            if (unresolvedUris > 0) {
                warnings.add(unresolvedUris + " 条代码路由的 uri 不是字面量（变量或拼接），已按原文返回");
            }
        }
        if (locatorEnabled) {
            warnings.add("discovery.locator.enabled=true：除了下面列出的路由，还有 /{serviceId}/** 形式的隐式路由未列出");
        }

        return new GatewayRoutes(routes, routes.size(), locatorEnabled, warnings);
    }

    // ------------------------------------------------------------------ routes

    private void collect(JsonNode node, Path file, Path root, List<String> modulePaths,
            List<GatewayRoutes.Route> out) {
        JsonNode routes = node.path("routes");
        if (!routes.isArray()) {
            return;
        }
        String relative = relative(root, file);
        for (JsonNode route : routes) {
            if (!route.isObject()) {
                continue;
            }
            String uri = text(route, "uri");
            String serviceId = serviceId(uri);
            List<String> predicates = expressions(route.path("predicates"));
            List<String> filters = expressions(route.path("filters"));
            out.add(new GatewayRoutes.Route(text(route, "id"), uri, serviceId, predicates, filters,
                    pathPrefixes(predicates), stripPrefix(filters), moduleOf(serviceId, modulePaths), relative));
        }
    }

    /**
     * @param uri route target
     * @return the service name for a {@code lb://name} target, {@code null} otherwise
     */
    static String serviceId(String uri) {
        if (uri == null) {
            return null;
        }
        String lower = uri.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("lb:")) {
            return null;
        }
        String rest = uri.substring(uri.indexOf(':') + 1);
        while (rest.startsWith("//")) {
            rest = rest.substring(2);
        }
        return rest.isBlank() ? null : rest.trim();
    }

    /**
     * Resolves a service id to a module directory of this project.
     *
     * @param serviceId service name from {@code lb://}, may be {@code null}
     * @param modulePaths module paths such as {@code mall-admin} or {@code services/order}
     * @return the matching module path, or {@code null} when the service is not in this project
     */
    static String moduleOf(String serviceId, List<String> modulePaths) {
        if (serviceId == null || modulePaths == null) {
            return null;
        }
        for (String module : modulePaths) {
            int slash = module.lastIndexOf('/');
            String leaf = slash < 0 ? module : module.substring(slash + 1);
            if (leaf.equalsIgnoreCase(serviceId)) {
                return module;
            }
        }
        return null;
    }

    /**
     * Renders predicate/filter entries as text.
     *
     * <p>
     * The short form ({@code Path=/x/**}) is what people write; the long form ({@code name: Path} plus
     * {@code args:}) is what documentation shows. Both are reported as written instead of being
     * normalised, because a caller comparing against {@code application.yml} wants the same text back.
     * </p>
     *
     * @param array declared entries
     * @return readable expressions, empty when absent
     */
    private static List<String> expressions(JsonNode array) {
        if (!array.isArray()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (JsonNode node : array) {
            if (node.isTextual()) {
                out.add(node.asText());
            }
            else if (node.isObject()) {
                String name = text(node, "name");
                JsonNode args = node.path("args");
                out.add(name == null ? node.toString() : name + (args.isObject() ? args.toString() : ""));
            }
            else {
                out.add(node.asText());
            }
        }
        return out;
    }

    /**
     * @param predicates declared predicate expressions
     * @return the values of {@code Path=} predicates, e.g. {@code /mall-admin/**}
     */
    static List<String> pathPrefixes(List<String> predicates) {
        List<String> out = new ArrayList<>();
        for (String predicate : predicates) {
            String trimmed = predicate.trim();
            if (trimmed.regionMatches(true, 0, "Path=", 0, "Path=".length())) {
                for (String value : trimmed.substring("Path=".length()).split(",")) {
                    String path = value.trim();
                    if (!path.isEmpty()) {
                        out.add(path);
                    }
                }
            }
        }
        return out;
    }

    /**
     * @param filters declared filter expressions
     * @return the segment count from {@code StripPrefix=N}, or {@code null} when absent
     */
    static Integer stripPrefix(List<String> filters) {
        for (String filter : filters) {
            String trimmed = filter.trim();
            if (trimmed.regionMatches(true, 0, "StripPrefix=", 0, "StripPrefix=".length())) {
                try {
                    return Integer.valueOf(trimmed.substring("StripPrefix=".length()).trim());
                }
                catch (NumberFormatException e) {
                    // A non-numeric value is a broken config, not something to guess about.
                    return null;
                }
            }
        }
        return null;
    }

    private static boolean locatorEnabled(JsonNode gateway) {
        return gateway.path("discovery").path("locator").path("enabled").asBoolean(false)
                || gateway.path("server").path("webflux").path("discovery").path("locator").path("enabled")
                    .asBoolean(false)
                || gateway.path("server").path("webmvc").path("discovery").path("locator").path("enabled")
                    .asBoolean(false);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() && !value.asText().isBlank() ? value.asText() : null;
    }

    // ------------------------------------------------------------------ files

    /**
     * Finds gateway config candidates.
     *
     * <p>
     * Only {@code application*} and {@code bootstrap*} YAML, main resources by default. Everything else
     * would mean parsing every YAML in the repository to find the one that has a gateway section.
     * </p>
     *
     * @param root project root
     * @param includeTests whether test resources are included
     * @param warnings sink for scan problems
     * @return candidate files, sorted
     */
    private List<Path> findConfigFiles(Path root, boolean includeTests, List<String> warnings) {
        Set<Path> out = new LinkedHashSet<>();
        try (Stream<Path> stream = Files.walk(root, MAX_DEPTH)) {
            stream.filter(Files::isRegularFile)
                .filter(path -> CONFIG_FILE.matcher(path.getFileName().toString()).matches())
                .filter(path -> includeTests || !isUnderTest(path, root))
                .filter(path -> !isUnderBuildOutput(path, root))
                .sorted()
                .forEach(out::add);
        }
        catch (IOException e) {
            warnings.add("扫描网关配置失败: " + e.getMessage());
        }
        return List.copyOf(out);
    }

    private static boolean isUnderTest(Path file, Path root) {
        String rel = relative(root, file);
        return rel.startsWith("src/test/") || rel.contains("/src/test/");
    }

    private static boolean isUnderBuildOutput(Path file, Path root) {
        String rel = relative(root, file);
        for (String skip : new String[] { "target/", "build/", "out/", ".git/" }) {
            if (rel.startsWith(skip)) {
                return true;
            }
        }
        return false;
    }

    private static String relative(Path root, Path file) {
        return root.relativize(file).toString().replace('\\', '/');
    }

    private JsonNode read(Path file, Path root, List<String> warnings) {
        try {
            // quoteReplacement: the replacement is itself a template, and "${logging.level}" would
            // otherwise be read as a back-reference to a group called logging.level.
            JsonNode node = YAML.readTree(MAVEN_FILTER.matcher(SourceText.read(file).text())
                .replaceAll(match -> Matcher.quoteReplacement("${" + match.group(1) + "}")));
            return node == null || node.isNull() ? null : node;
        }
        catch (IOException | RuntimeException e) {
            // A malformed YAML file is a note, not a failure: the other modules' routes still count.
            warnings.add("网关配置解析失败: " + relative(root, file) + " (" + describe(e) + ")");
            return null;
        }
    }

    /**
     * Turns a parser exception into one line worth reading.
     *
     * <p>
     * The class name alone is useless: {@code MarkedYAMLException} does not say which line, and the
     * usual cause — a Maven placeholder the parser refuses — is invisible in a stack trace.
     * </p>
     *
     * @param e parser failure
     * @return location and reason, or the class name when there is no YAML position to report
     */
    private static String describe(Exception e) {
        if (e instanceof JsonProcessingException parse) {
            String reason = lastLine(parse.getOriginalMessage());
            JsonLocation where = parse.getLocation();
            if (where != null && where.getLineNr() > 0) {
                return "第 " + where.getLineNr() + " 行: " + reason;
            }
            return reason;
        }
        return e.getClass().getSimpleName() + ": " + e.getMessage();
    }

    private static String lastLine(String message) {
        if (message == null || message.isBlank()) {
            return "无法解析";
        }
        String[] lines = message.strip().split("\n");
        return lines[lines.length - 1].trim();
    }
}

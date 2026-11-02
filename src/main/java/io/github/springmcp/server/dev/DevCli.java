package io.github.springmcp.server.dev;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import io.github.springmcp.core.IndexRegistry;
import io.github.springmcp.server.ProjectRoots;
import io.github.springmcp.spi.ErrorCode;
import io.github.springmcp.spi.ToolFailure;
import io.github.springmcp.spi.api.IndexHandle;
import io.github.springmcp.spi.api.IndexOptions;
import io.github.springmcp.spi.api.QueryScope;
import io.github.springmcp.spi.model.Annotations;
import io.github.springmcp.spi.model.Controllers;
import io.github.springmcp.spi.model.Dependencies;
import io.github.springmcp.spi.model.FeignClients;
import io.github.springmcp.spi.model.Mappers;
import io.github.springmcp.spi.model.ServiceCalls;
import io.github.springmcp.spi.model.Symbols;
import io.github.springmcp.spi.model.TypeHierarchy;

/**
 * Offline CLI used to inspect results without going through an MCP client.
 *
 * <p>
 * Every analysis the server exposes can be run from a shell and printed as JSON. When a tool returns
 * something surprising, the fastest way to find out whether the problem is in the analysis or in the
 * protocol layer is to run the same query here.
 * </p>
 *
 * <p>
 * Output goes to the real stdout (handed in explicitly), so results can be piped into {@code jq}
 * even though the process later replaces {@code System.out} to protect the protocol channel.
 * </p>
 */
public final class DevCli {

    private final IndexRegistry registry;

    private final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    /** Working copies of the baseline repositories; outside target/ so mvn clean does not force a re-clone. */
    static final String DEFAULT_BASELINE_WORK = ".baseline";

    private final PrintStream out;

    /**
     * @param registry analyzer to query
     */
    public DevCli(IndexRegistry registry) {
        this(registry, System.out);
    }

    /**
     * @param registry analyzer to query
     * @param out stream to write JSON to
     */
    public DevCli(IndexRegistry registry, PrintStream out) {
        this.registry = registry;
        this.out = out;
    }

    /**
     * Runs one command.
     * @param args command followed by {@code --key value} pairs
     * @return process exit code: 0 on success, 1 on analysis failure, 2 on usage error
     */
    public int run(List<String> args) {
        if (args.isEmpty()) {
            System.err.println("缺少 --dev 子命令。可用: index-info, controllers, callers, mappers, annotations, deps");
            return 2;
        }
        String command = args.get(0);
        Map<String, String> options = parseOptions(args.subList(1, args.size()));

        try {
            Object result = switch (command) {
                case "index-info" -> indexInfo(options);
                case "controllers" -> controllers(options);
                case "callers" -> callers(options);
                case "gateway" -> gateway(options);
                case "feign" -> feign(options);
                case "implementations" -> implementations(options);
                case "symbols" -> symbols(options);
                case "mappers" -> mappers(options);
                case "annotations" -> annotations(options);
                case "deps" -> deps(options);
                case "modules" -> modules(options);
                case "baseline" -> baseline(options);
                default -> null;
            };
            if (result == null) {
                System.err.println("未知子命令: " + command);
                return 2;
            }
            out.println(mapper.writeValueAsString(result));
            out.flush();
            return 0;
        }
        catch (ToolFailure f) {
            return fail(f.code(), f.getMessage(), f.hint());
        }
        catch (Exception e) {
            return fail(ErrorCode.INTERNAL_ERROR, String.valueOf(e), null);
        }
    }

    private Object indexInfo(Map<String, String> options) throws ToolFailure {
        // Metadata is exactly what you want when a directory has no source: this is the one command
        // that must answer "fileCount=0, modules=[], root=..." instead of refusing.
        IndexHandle index = openUnchecked(options);
        return Map.of("metadata", index.metadata(), "root", index.root().toString());
    }

    private Object controllers(Map<String, String> options) throws ToolFailure {
        IndexHandle index = open(options);
        return index.controllers(new Controllers.Query(flag(options, "include-tests"),
                options.get("base-path"), options.get("http-method")));
    }

    private Object callers(Map<String, String> options) throws ToolFailure {
        IndexHandle index = open(options);
        String service = options.get("service");
        if (service == null) {
            throw ToolFailure.of(ErrorCode.INVALID_ARGUMENT, "缺少 --service", "例如 --service OrderService");
        }
        return index.findServiceCallers(new ServiceCalls.Query(service, options.get("method"), true, true,
                flag(options, "low-confidence"), intOption(options, "limit", 500)));
    }

    private Object symbols(Map<String, String> options) throws ToolFailure {
        IndexHandle index = open(options);
        String name = options.get("name");
        if (name == null) {
            throw ToolFailure.of(ErrorCode.INVALID_ARGUMENT, "缺少 --name", "例如 --name OrderController");
        }
        return index.searchSymbols(new Symbols.Query(name, Set.of(), intOption(options, "limit", 50)));
    }

    private Object gateway(Map<String, String> options) throws ToolFailure {
        IndexHandle index = open(options);
        return index.gatewayRoutes();
    }

    private Object feign(Map<String, String> options) throws ToolFailure {
        IndexHandle index = open(options);
        return index.feignClients(new FeignClients.Query(options.get("name")));
    }

    private Object implementations(Map<String, String> options) throws ToolFailure {
        IndexHandle index = open(options);
        String type = options.get("type");
        if (type == null) {
            throw ToolFailure.of(ErrorCode.INVALID_ARGUMENT, "缺少 --type", "例如 --type OrderService");
        }
        return index.implementations(new TypeHierarchy.Query(type, flag(options, "include-abstract"),
                flag(options, "include-interfaces")));
    }

    private Object mappers(Map<String, String> options) throws ToolFailure {
        IndexHandle index = open(options);
        return index.mappers(new Mappers.Query(enumSet(options.get("kinds"), Mappers.Kind.class),
                options.get("name")));
    }

    private Object annotations(Map<String, String> options) throws ToolFailure {
        IndexHandle index = open(options);
        String annotation = options.get("annotation");
        if (annotation == null) {
            throw ToolFailure.of(ErrorCode.INVALID_ARGUMENT, "缺少 --annotation", "例如 --annotation Transactional");
        }
        return index.findByAnnotation(new Annotations.Query(annotation,
                enumSet(options.get("kinds"), Annotations.ElementKind.class), flag(options, "strict"),
                intOption(options, "limit", 200)));
    }

    private Object deps(Map<String, String> options) throws ToolFailure {
        IndexHandle index = open(options);
        String file = options.get("file");
        if (file == null) {
            throw ToolFailure.of(ErrorCode.INVALID_ARGUMENT, "缺少 --file", "例如 --file src/main/java/A.java");
        }
        return index.fileDependencies(Path.of(file), flag(options, "same-package"));
    }

    /**
     * Runs the regression baseline for one repository.
     *
     * <p>
     * Two modes, and the split is deliberate: {@code --regenerate} writes the golden file, plain
     * invocation only compares. Making the test regenerate would mean either the test writes into the
     * source tree (surprising) or the golden silently follows whatever the code does (useless).
     * </p>
     *
     * @param options {@code repo}, optional {@code manifest}, {@code work}, {@code golden},
     * {@code regenerate}, {@code include-tests}
     * @return a summary document
     * @throws ToolFailure when the repository is not in the manifest
     */
    private Object baseline(Map<String, String> options) throws Exception {
        String repo = options.get("repo");
        if (repo == null) {
            throw ToolFailure.of(ErrorCode.INVALID_ARGUMENT, "缺少 --repo",
                    "例如 --repo=spring-petclinic；可选值见 fixtures/baseline/manifest.json");
        }
        Path manifest = Path.of(options.getOrDefault("manifest", "fixtures/baseline/manifest.json"));
        BaselineSpec spec = BaselineSpec.load(manifest)
            .stream()
            .filter(s -> s.name().equals(repo))
            .findFirst()
            .orElseThrow(() -> ToolFailure.of(ErrorCode.INVALID_ARGUMENT,
                    "manifest 中没有仓库: " + repo, "检查 " + manifest.toAbsolutePath()));

        Path work = Path.of(options.getOrDefault("work", DEFAULT_BASELINE_WORK));
        Path goldenDir = Path.of(options.getOrDefault("golden", "fixtures/baseline/golden"));
        Path repoDir = spec.ensure(work);

        BaselineRunner runner = new BaselineRunner(registry, flag(options, "include-tests"));
        long startedAt = System.currentTimeMillis();
        var snapshots = runner.run(spec, repoDir);
        long elapsed = System.currentTimeMillis() - startedAt;

        Path file = goldenDir.resolve(repo + ".json");
        if (flag(options, "regenerate")) {
            runner.write(runner.toJson(spec, snapshots), file);
            return Map.of("repo", repo, "mode", "regenerate", "golden", file.toString(),
                    "tools", snapshots.size(), "elapsedMs", elapsed);
        }

        if (!Files.isRegularFile(file)) {
            throw ToolFailure.of(ErrorCode.PATH_NOT_FOUND, "golden 不存在: " + file,
                    "先运行 --dev baseline --repo=" + repo + " --regenerate 生成基线");
        }
        JsonNode golden = mapper.readTree(Files.readString(file, StandardCharsets.UTF_8));
        List<String> differences = BaselineRunner.compare(golden, snapshots);
        return Map.of("repo", repo, "mode", "compare", "ok", differences.isEmpty(),
                "differences", differences, "tools", snapshots.size(), "elapsedMs", elapsed);
    }

    /**
     * Opens a snapshot for an analysis command, refusing a directory that holds no source.
     *
     * <p>
     * The same guard the MCP tool surface applies, and for the same reason: an empty answer is
     * indistinguishable from a project that exposes nothing. It matters more here, not less — the CLI
     * is where someone goes to find out whether the analysis or the client is wrong, so answering
     * {@code "endpoints": []} with exit code 0 for a mistyped {@code --root} sends them looking in the
     * wrong place.
     * </p>
     *
     * @param options {@code root}, optionally {@code scope}
     * @return the snapshot
     * @throws ToolFailure when the directory holds no Java/Kotlin source
     */
    private IndexHandle open(Map<String, String> options) throws ToolFailure {
        IndexHandle handle = openUnchecked(options);
        return ProjectRoots.requireSources(handle.root(), handle);
    }

    private IndexHandle openUnchecked(Map<String, String> options) throws ToolFailure {
        String root = options.get("root");
        if (root == null) {
            throw ToolFailure.of(ErrorCode.INVALID_ARGUMENT, "缺少 --root", "例如 --root ./fixtures/demo-web");
        }
        IndexOptions opts = IndexOptions.defaults().withIncludeTests(flag(options, "include-tests"));
        IndexHandle handle = registry.open(Path.of(root), opts);

        String scope = options.get("scope");
        if (scope == null || scope.isBlank()) {
            return handle;
        }
        List<String> modules = new ArrayList<>();
        for (String part : scope.split(",")) {
            if (!part.isBlank()) {
                modules.add(part.trim());
            }
        }
        if (modules.isEmpty()) {
            return handle;
        }
        if (handle.modules().isEmpty()) {
            throw ToolFailure.of(ErrorCode.INVALID_ARGUMENT, "该工程未识别到多模块结构，--scope 不可用",
                    "多模块工程才支持作用域；单模块工程直接查即可");
        }
        return handle.scoped(QueryScope.of(modules, List.of()));
    }

    /**
     * Lists detected modules, so a caller can pick values for {@code --scope}.
     * @param options {@code root}
     * @return module list with dependency edges
     */
    private Object modules(Map<String, String> options) throws ToolFailure {
        IndexHandle index = open(options);
        return Map.of("modules", index.modules(), "dependencies", index.moduleDependencies(),
                "root", index.root().toString());
    }

    private int fail(ErrorCode code, String message, String hint) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", false);
        body.put("code", code.name());
        body.put("message", message);
        body.put("hint", hint == null ? "" : hint);
        try {
            out.println(mapper.writeValueAsString(body));
        }
        catch (Exception e) {
            System.err.println("[" + code + "] " + message);
        }
        out.flush();
        return 1;
    }

    /**
     * Parses {@code --key value}, {@code --key=value} and bare {@code --flag} tokens.
     *
     * <p>
     * Both separator styles are accepted because the server launcher takes {@code --project=<dir>},
     * and a user who just configured that will type {@code --repo=name} here. Rejecting one form
     * because the other exists is the kind of inconsistency that costs a debugging session.
     * </p>
     *
     * <p>
     * A token starting with {@code --} without {@code =} gets the value {@code "true"} when the next
     * token is another key or absent, which is how boolean switches are expressed.
     * </p>
     */
    private static Map<String, String> parseOptions(List<String> tokens) {
        Map<String, String> out = new LinkedHashMap<>();
        for (int i = 0; i < tokens.size(); i++) {
            String token = tokens.get(i);
            if (!token.startsWith("--")) {
                continue;
            }
            String body = token.substring(2);
            int equals = body.indexOf('=');
            if (equals > 0) {
                out.put(body.substring(0, equals), body.substring(equals + 1));
                continue;
            }
            String value = "true";
            if (i + 1 < tokens.size() && !tokens.get(i + 1).startsWith("--")) {
                value = tokens.get(++i);
            }
            out.put(body, value);
        }
        return out;
    }

    private static boolean flag(Map<String, String> options, String key) {
        String v = options.get(key);
        return v != null && !"false".equalsIgnoreCase(v);
    }

    private static int intOption(Map<String, String> options, String key, int fallback) {
        String v = options.get(key);
        if (v == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(v);
        }
        catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static <E extends Enum<E>> Set<E> enumSet(String raw, Class<E> type) throws ToolFailure {
        if (raw == null || raw.isBlank()) {
            return Set.of();
        }
        Set<E> out = new LinkedHashSet<>();
        for (String part : raw.split(",")) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            try {
                out.add(Enum.valueOf(type, trimmed.toUpperCase(Locale.ROOT)));
            }
            catch (IllegalArgumentException e) {
                throw ToolFailure.of(ErrorCode.INVALID_ARGUMENT,
                        "未知取值: " + trimmed + "（可选: " + String.join(", ", names(type)) + "）", null);
            }
        }
        return out;
    }

    private static <E extends Enum<E>> List<String> names(Class<E> type) {
        List<String> out = new ArrayList<>();
        for (E e : type.getEnumConstants()) {
            out.add(e.name());
        }
        return out;
    }
}

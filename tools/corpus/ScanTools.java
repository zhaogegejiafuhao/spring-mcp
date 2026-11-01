import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardOpenOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.springmcp.core.IndexRegistry;
import io.github.springmcp.server.ProjectCatalog;
import io.github.springmcp.server.builtin.BuiltinPlugin;
import io.github.springmcp.server.plugin.DefaultPluginContext;
import io.github.springmcp.spi.PluginContext;
import io.github.springmcp.spi.PluginLogger;
import io.github.springmcp.spi.ToolInvocation;
import io.github.springmcp.spi.ToolOutcome;
import io.github.springmcp.spi.ToolProvider;
import io.github.springmcp.spi.api.IndexOptions;
import io.github.springmcp.spi.model.Controllers;
import io.github.springmcp.spi.model.FeignClients;
import io.github.springmcp.spi.model.GatewayRoutes;
import io.github.springmcp.spi.model.Mappers;

/**
 * Runs the shipped tools over a directory of checkouts, one JSON line per repository.
 *
 * <p>
 * Deliberately goes through {@link ToolProvider#invoke} rather than the analyzer: what a caller sees
 * is the tool surface, and that is what has to hold up on repositories nobody tuned for. A fresh
 * registry is built per repository so nothing is reused and one huge project cannot pin the heap of
 * the next.
 * </p>
 *
 * <p>
 * Usage: {@code ScanTools <repos-dir> <results.jsonl> <endpoints.jsonl> [include-list]}
 * </p>
 */
public final class ScanTools {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]);
        Path resultFile = Path.of(args[1]);
        Path endpointsFile = Path.of(args[2]);

        // Optional include-list: without it, pointing this at a directory that also holds other
        // checkouts silently scans them all, which is how a "11 repositories" check turned into a
        // hundred-repository run.
        Set<String> only = readIncludeList(args.length > 3 ? Path.of(args[3]) : null);
        Set<String> done = alreadyScanned(resultFile);
        List<Path> dirs = new ArrayList<>();
        try (Stream<Path> stream = Files.list(root)) {
            stream.filter(Files::isDirectory).sorted()
                    .filter(dir -> !done.contains(dir.getFileName().toString().replace("__", "/")))
                    .filter(dir -> only.isEmpty() || only.contains(dir.getFileName().toString().replace("__", "/")))
                    .forEach(dirs::add);
        }
        System.err.println("todo " + dirs.size() + ", already done " + done.size());

        PrintStream results = write(resultFile);
        PrintStream endpoints = write(endpointsFile);
        try (results; endpoints) {
            for (Path dir : dirs) {
                String name = dir.getFileName().toString().replace("__", "/");
                long started = System.currentTimeMillis();
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("name", name);
                try {
                    Controllers.Report controllers = scan(dir, row);
                    row.put("status", "OK");
                    row.put("ms", System.currentTimeMillis() - started);
                    results.println(MAPPER.writeValueAsString(row));

                    Map<String, Object> detail = new LinkedHashMap<>();
                    detail.put("repo", name);
                    detail.put("endpoints", controllers.endpoints().stream().map(e -> {
                        Map<String, Object> one = new LinkedHashMap<>();
                        one.put("verbs", e.httpMethods());
                        one.put("path", e.path());
                        one.put("resolved", e.pathResolved());
                        one.put("file", e.file());
                        one.put("line", e.line());
                        return one;
                    }).toList());
                    endpoints.println(MAPPER.writeValueAsString(detail));
                }
                catch (Exception e) {
                    row.put("status", "FAIL");
                    row.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
                    row.put("ms", System.currentTimeMillis() - started);
                    results.println(MAPPER.writeValueAsString(row));
                }
                results.flush();
                endpoints.flush();
            }
        }
        System.err.println("scanned " + dirs.size() + " directories");
    }

    /**
     * Runs the four surface tools over one checkout and fills {@code row} with their counts.
     *
     * @param dir project root
     * @param row output row, mutated in place
     * @return the controller report, kept for the caller to dump per-endpoint detail
     * @throws Exception when a tool fails
     */
    private static Controllers.Report scan(Path dir, Map<String, Object> row) throws Exception {
        IndexRegistry registry = new IndexRegistry(IndexRegistry.Revalidation.NEVER, 60_000);
        PluginContext context = new DefaultPluginContext(registry, PluginLogger.noop(),
                Path.of(System.getProperty("java.io.tmpdir")), null);
        BuiltinPlugin plugin = new BuiltinPlugin(IndexOptions.defaults(), ProjectCatalog.empty());
        plugin.initialize(context);

        Map<String, ToolProvider> byName = new LinkedHashMap<>();
        for (ToolProvider provider : plugin.tools()) {
            byName.put(provider.descriptor().name(), provider);
        }

        Map<String, Object> args = Map.of("projectRoot", dir.toString());
        Controllers.Report controllers = (Controllers.Report) call(byName, context, "list-controllers", args);
        Mappers.Report mappers = (Mappers.Report) call(byName, context, "list-mappers", args);
        FeignClients.Report feign = (FeignClients.Report) call(byName, context, "list-feign-clients", args);
        GatewayRoutes routes = (GatewayRoutes) call(byName, context, "list-gateway-routes", args);

        row.put("controllers", controllers.controllerCount());
        row.put("endpoints", controllers.endpoints().size());
        row.put("unresolved", controllers.endpoints().stream().filter(e -> !e.pathResolved()).count());
        row.put("mappers", mappers.mappers().size());
        row.put("feignClients", feign.clients().size());
        row.put("gatewayRoutes", routes.count());
        row.put("warnings", controllers.warnings().size());
        return controllers;
    }

    private static Object call(Map<String, ToolProvider> byName, PluginContext context, String tool,
            Map<String, Object> args) throws Exception {
        ToolProvider provider = byName.get(tool);
        if (provider == null) {
            throw new IllegalStateException("tool not registered: " + tool);
        }
        ToolOutcome outcome = provider.invoke(new ToolInvocation(args, context));
        if (outcome instanceof ToolOutcome.Success success) {
            return success.structured();
        }
        var failure = ((ToolOutcome.Failure) outcome).failure();
        throw new IllegalStateException(failure.code() + ": " + failure.getMessage());
    }

    /**
     * @param file results file from an earlier run, may not exist
     * @return repository names already measured, so a timeout loses one repository at most
     */
    private static Set<String> readIncludeList(Path file) throws IOException {
        if (file == null || !Files.exists(file)) {
            return Set.of();
        }
        Set<String> names = new HashSet<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String name = line.strip();
            if (name.isEmpty() || name.startsWith("#")) {
                continue;
            }
            names.add(name.indexOf('	') >= 0 ? name.substring(0, name.indexOf('	')).strip() : name);
        }
        return names;
    }

    private static Set<String> alreadyScanned(Path file) throws IOException {
        Set<String> done = new HashSet<>();
        if (!Files.isRegularFile(file)) {
            return done;
        }
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            int at = line.indexOf("\"name\":\"");
            if (at < 0) {
                continue;
            }
            int start = at + 8;
            int end = line.indexOf('"', start);
            if (end > start) {
                done.add(line.substring(start, end));
            }
        }
        return done;
    }

    private static PrintStream write(Path file) throws IOException {
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        // Append, not truncate: a run that hits the time budget must not wipe the repositories an
        // earlier run already measured.
        return new PrintStream(Files.newOutputStream(file, StandardOpenOption.CREATE, StandardOpenOption.APPEND),
                false, StandardCharsets.UTF_8);
    }
}

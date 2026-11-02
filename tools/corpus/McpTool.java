import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

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

/**
 * Runs one shipped tool, once, through the same {@link ToolProvider} the MCP server registers.
 *
 * <p>
 * The point is the surface, not the analysis: parameter names, defaults and the error envelope have
 * to hold up exactly as a client sees them. {@code DevCli} deliberately goes around that layer — it
 * calls the index directly so a surprise can be attributed to the analysis — so it cannot stand in
 * for this. Anything measured through here is measured through what {@code tools/list} advertises.
 * </p>
 *
 * <p>
 * Usage: {@code McpTool <tool-name> [key=value ...]}, e.g.
 * {@code McpTool list-controllers projectRoot=D:/code/app}.
 * </p>
 *
 * <p>
 * Exit codes: 0 success, 1 the tool returned a failure, 2 the tool name is unknown.
 * </p>
 */
public final class McpTool {

    private static final ObjectMapper MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private McpTool() {
    }

    /**
     * @param args tool name followed by {@code key=value} pairs
     * @throws Exception when the registry cannot be built or output cannot be written
     */
    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("usage: McpTool <tool-name> [key=value ...]");
            System.exit(2);
        }

        String name = args[0];
        Map<String, Object> arguments = new LinkedHashMap<>();
        for (int i = 1; i < args.length; i++) {
            int equals = args[i].indexOf('=');
            if (equals <= 0) {
                System.err.println("参数必须是 key=value: " + args[i]);
                System.exit(2);
            }
            arguments.put(args[i].substring(0, equals), args[i].substring(equals + 1));
        }

        IndexRegistry registry = new IndexRegistry(IndexRegistry.Revalidation.NEVER, 60_000);
        PluginContext context = new DefaultPluginContext(registry, PluginLogger.noop(),
                Path.of(System.getProperty("java.io.tmpdir")), null);
        BuiltinPlugin plugin = new BuiltinPlugin(IndexOptions.defaults(), ProjectCatalog.empty());
        plugin.initialize(context);

        Map<String, ToolProvider> byName = new LinkedHashMap<>();
        for (ToolProvider provider : plugin.tools()) {
            byName.put(provider.descriptor().name(), provider);
        }

        ToolProvider provider = byName.get(name);
        if (provider == null) {
            System.err.println("没有这个工具: " + name);
            System.err.println("可用: " + String.join(", ", byName.keySet()));
            System.exit(2);
        }

        ToolOutcome outcome = provider.invoke(new ToolInvocation(arguments, context));
        if (outcome instanceof ToolOutcome.Success success) {
            System.out.println(MAPPER.writeValueAsString(success.structured()));
            return;
        }

        var failure = ((ToolOutcome.Failure) outcome).failure();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", false);
        body.put("code", failure.code().name());
        body.put("message", failure.getMessage());
        body.put("hint", failure.hint());
        System.out.println(MAPPER.writeValueAsString(body));
        System.exit(1);
    }
}

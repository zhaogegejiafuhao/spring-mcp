package io.github.springmcp.server.mcp;

import java.util.Optional;

import io.github.springmcp.spi.ErrorCode;
import io.github.springmcp.spi.PluginContext;
import io.github.springmcp.spi.ToolInvocation;
import io.github.springmcp.spi.ToolOutcome;
import io.github.springmcp.spi.ToolProvider;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;

/**
 * Turns a {@link ToolProvider} into an MCP tool specification, with the schema validation and the
 * exception firewall that keep one bad tool from taking down the server.
 *
 * <p>
 * All three layers of protection live here on purpose:
 * </p>
 * <ol>
 * <li>the descriptor's JSON Schema is validated once, at registration; an invalid schema drops that
 * one tool and logs a warning instead of failing startup</li>
 * <li>a returned {@link ToolOutcome.Failure} becomes a normal {@code isError=true} result</li>
 * <li>any {@code Throwable} escaping the tool — including a plugin's own bug — also becomes a
 * well-formed error result, because a plugin is untrusted code</li>
 * </ol>
 */
public final class ToolRegistrar {

    private final McpJsonMapper jsonMapper;

    private final ToolResults results;

    /**
     * @param jsonMapper SDK JSON binding, used to parse tool schemas
     * @param results result factory
     */
    public ToolRegistrar(McpJsonMapper jsonMapper, ToolResults results) {
        this.jsonMapper = jsonMapper;
        this.results = results;
    }

    /**
     * Builds a tool specification.
     * @param provider the tool
     * @param owner plugin id or {@code "builtin"}, used in diagnostics
     * @param context runtime context handed to the tool on each call
     * @return the specification, or empty when the tool is unusable
     */
    public Optional<McpServerFeatures.SyncToolSpecification> register(ToolProvider provider, String owner,
            PluginContext context) {
        String name;
        McpSchema.Tool tool;
        try {
            var d = provider.descriptor();
            name = d.name();
            tool = McpSchema.Tool.builder(d.name(), jsonMapper, d.inputSchemaJson())
                .title(d.title() == null || d.title().isBlank() ? d.name() : d.title())
                .description(d.description())
                .build();
        }
        catch (RuntimeException e) {
            // Most likely an invalid JSON Schema: the SDK validates it inside build().
            System.err.println("[warn] 跳过工具 (" + owner + "): 描述或 inputSchema 非法 — " + e.getMessage());
            return Optional.empty();
        }

        return Optional.of(McpServerFeatures.SyncToolSpecification.builder()
            .tool(tool)
            .callHandler((exchange, request) -> invoke(provider, owner, name, context, request))
            .build());
    }

    private McpSchema.CallToolResult invoke(ToolProvider provider, String owner, String toolName,
            PluginContext context, McpSchema.CallToolRequest request) {
        long startedAt = System.currentTimeMillis();
        try {
            var invocation = new ToolInvocation(request.arguments(), context);
            McpSchema.CallToolResult result = switch (provider.invoke(invocation)) {
                case ToolOutcome.Success s -> results.ok(s);
                case ToolOutcome.Failure f -> results.error(f.failure());
            };
            log(toolName, owner, startedAt, Boolean.TRUE.equals(result.isError()));
            return result;
        }
        catch (StackOverflowError | OutOfMemoryError e) {
            // Never swallow an Error: the JVM state is not trustworthy afterwards.
            throw e;
        }
        catch (RuntimeException | LinkageError e) {
            log(toolName, owner, startedAt, true);
            System.err.println("[error] 工具 '" + toolName + "' 执行异常: " + e);
            e.printStackTrace(System.err);
            return results.error(ErrorCode.INTERNAL_ERROR, "工具执行异常: " + e.getClass().getSimpleName(),
                    "这是 spring-mcp 或插件的缺陷，请附上输入参数反馈");
        }
    }

    private static void log(String toolName, String owner, long startedAt, boolean failed) {
        long elapsed = System.currentTimeMillis() - startedAt;
        String status = failed ? "failed" : "ok";
        System.err.println("[tool] " + toolName + " (" + owner + ") " + status + " in " + elapsed + "ms");
    }
}

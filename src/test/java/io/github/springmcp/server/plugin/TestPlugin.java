package io.github.springmcp.server.plugin;

import java.util.List;

import io.github.springmcp.spi.JsonSchemas;
import io.github.springmcp.spi.PluginContext;
import io.github.springmcp.spi.SpringMcpPlugin;
import io.github.springmcp.spi.ToolDescriptor;
import io.github.springmcp.spi.ToolInvocation;
import io.github.springmcp.spi.ToolOutcome;
import io.github.springmcp.spi.ToolProvider;

/**
 * Stand-in for a third-party plugin. Lives on the test classpath so a directory-based plugin
 * (a {@code META-INF/services} file plus a parent-first classloader) can resolve it without building
 * a real jar.
 */
public class TestPlugin implements SpringMcpPlugin {

    static int initializeCount;

    @Override
    public String id() {
        return "test.example";
    }

    @Override
    public String version() {
        return "1.0";
    }

    @Override
    public void initialize(PluginContext context) {
        initializeCount++;
        if (context == null || context.analyzer() == null) {
            throw new IllegalStateException("context must carry an analyzer");
        }
    }

    @Override
    public List<ToolProvider> tools() {
        return List.of(new ToolProvider() {
            @Override
            public ToolDescriptor descriptor() {
                return ToolDescriptor.builder("example-echo")
                    .description("Echoes the supplied text back. Used by the plugin loader tests.")
                    .inputSchema(JsonSchemas.object().string("text", "要回显的文本", true, null))
                    .build();
            }

            @Override
            public ToolOutcome invoke(ToolInvocation invocation) {
                return ToolOutcome.ok(java.util.Map.of("echo", invocation.args().raw().getOrDefault("text", "")),
                        "echoed");
            }
        });
    }
}

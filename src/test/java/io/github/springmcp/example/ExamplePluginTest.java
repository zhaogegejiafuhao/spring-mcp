package io.github.springmcp.example;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.springmcp.core.IndexRegistry;
import io.github.springmcp.server.plugin.DefaultPluginContext;
import io.github.springmcp.spi.PluginContext;
import io.github.springmcp.spi.PluginLogger;
import io.github.springmcp.spi.ToolInvocation;
import io.github.springmcp.spi.ToolOutcome;
import io.github.springmcp.spi.ToolProvider;

/**
 * Keeps the plugin sample honest: the code a reader copies must actually compile and work against
 * the SPI it claims to demonstrate.
 */
class ExamplePluginTest {

    private static Path fixture(String name) {
        var url = ExamplePluginTest.class.getResource("/fixtures/" + name);
        assertThat(url).as("fixture %s", name).isNotNull();
        try {
            return Path.of(url.toURI());
        }
        catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("the sample plugin initialises and exposes one tool")
    void pluginIsUsable() throws Exception {
        IndexRegistry registry = new IndexRegistry(IndexRegistry.Revalidation.NEVER, 60_000);
        PluginContext context = new DefaultPluginContext(registry, PluginLogger.noop(), fixture("demo-web"), null);

        ExamplePlugin plugin = new ExamplePlugin();
        plugin.initialize(context);

        List<ToolProvider> tools = plugin.tools();
        assertThat(tools).hasSize(1);
        assertThat(tools.get(0).descriptor().name()).isEqualTo("list-scheduled-jobs");
        assertThat(tools.get(0).descriptor().inputSchemaJson()).contains("\"type\":\"object\"");
    }

    @Test
    @DisplayName("the sample tool finds the @Scheduled method in the fixture")
    void findsScheduledJob() throws Exception {
        IndexRegistry registry = new IndexRegistry(IndexRegistry.Revalidation.NEVER, 60_000);
        PluginContext context = new DefaultPluginContext(registry, PluginLogger.noop(), fixture("layered-callers"),
                null);

        ExamplePlugin plugin = new ExamplePlugin();
        plugin.initialize(context);

        ToolOutcome outcome = plugin.tools()
            .get(0)
            .invoke(new ToolInvocation(Map.of("projectRoot", fixture("layered-callers").toString()), context));

        assertThat(outcome).isInstanceOf(ToolOutcome.Success.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) ((ToolOutcome.Success) outcome).structured();

        assertThat(payload.get("count")).isEqualTo(1);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> jobs = (List<Map<String, Object>>) payload.get("jobs");
        assertThat(jobs).singleElement().satisfies(job -> {
            assertThat(job.get("type")).isEqualTo("com.acme.job.CleanupJob");
            assertThat(job.get("method")).isEqualTo("cleanup");
        });
    }

    @Test
    @DisplayName("the sample tool reports a missing project root instead of throwing")
    void missingProjectRoot() throws Exception {
        IndexRegistry registry = new IndexRegistry(IndexRegistry.Revalidation.NEVER, 60_000);
        PluginContext context = new DefaultPluginContext(registry, PluginLogger.noop(), fixture("demo-web"), null);

        ExamplePlugin plugin = new ExamplePlugin();
        plugin.initialize(context);

        ToolOutcome outcome = plugin.tools().get(0).invoke(new ToolInvocation(Map.of(), context));
        assertThat(outcome).isInstanceOf(ToolOutcome.Failure.class);
        assertThat(((ToolOutcome.Failure) outcome).failure().code())
            .isEqualTo(io.github.springmcp.spi.ErrorCode.INVALID_ARGUMENT);
    }
}

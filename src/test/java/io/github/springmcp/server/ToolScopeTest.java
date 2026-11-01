package io.github.springmcp.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.springmcp.core.IndexRegistry;
import io.github.springmcp.server.builtin.BuiltinPlugin;
import io.github.springmcp.server.builtin.ListControllersTool;
import io.github.springmcp.server.plugin.DefaultPluginContext;
import io.github.springmcp.spi.PluginContext;
import io.github.springmcp.spi.PluginLogger;
import io.github.springmcp.spi.ToolInvocation;
import io.github.springmcp.spi.ToolOutcome;
import io.github.springmcp.spi.ToolProvider;
import io.github.springmcp.spi.api.IndexOptions;
import io.github.springmcp.spi.model.Controllers;

/**
 * The {@code scope} argument, as a caller sees it.
 *
 * <p>
 * Two behaviours matter and neither is enforced by the core tests: a scoped "0 results" must say it
 * was scoped (otherwise it reads as "nothing exists"), and asking for a scope on a project without
 * modules must fail loudly instead of returning an empty answer.
 * </p>
 */
class ToolScopeTest {

    private static Map<String, ToolProvider> tools;

    private static PluginContext context;

    @BeforeAll
    static void setUp() throws Exception {
        IndexRegistry registry = new IndexRegistry(IndexRegistry.Revalidation.NEVER, 60_000);
        context = new DefaultPluginContext(registry, PluginLogger.noop(), fixture("multi-module"), fixture("multi-module"));

        BuiltinPlugin plugin = new BuiltinPlugin(IndexOptions.defaults());
        plugin.initialize(context);
        tools = new LinkedHashMap<>();
        for (ToolProvider provider : plugin.tools()) {
            tools.put(provider.descriptor().name(), provider);
        }
    }

    @Test
    @DisplayName("scope narrows the answer and the summary says which scope was applied")
    void scopedSummaryNamesTheScope() {
        ToolOutcome outcome = tools.get(ListControllersTool.NAME)
            .invoke(new ToolInvocation(Map.of("scope", List.of("billing")), context));

        assertThat(outcome).isInstanceOf(ToolOutcome.Success.class);
        ToolOutcome.Success success = (ToolOutcome.Success) outcome;
        Controllers.Report report = (Controllers.Report) success.structured();

        assertThat(report.endpoints()).extracting(Controllers.Endpoint::path).containsExactly("/billing");
        assertThat(success.humanSummary()).contains("作用域").contains("billing");
        assertThat(success.warnings()).anyMatch(w -> w.contains("限定在作用域内"));
    }

    @Test
    @DisplayName("scope on a single-module project fails with an actionable message")
    void scopeOnSingleModuleProjectFails() throws Exception {
        IndexRegistry registry = new IndexRegistry(IndexRegistry.Revalidation.NEVER, 60_000);
        PluginContext single = new DefaultPluginContext(registry, PluginLogger.noop(), fixture("demo-web"), fixture("demo-web"));

        BuiltinPlugin plugin = new BuiltinPlugin(IndexOptions.defaults());
        plugin.initialize(single);
        ToolProvider tool = plugin.tools()
            .stream()
            .filter(t -> ListControllersTool.NAME.equals(t.descriptor().name()))
            .findFirst()
            .orElseThrow();

        ToolOutcome outcome = tool.invoke(new ToolInvocation(Map.of("scope", List.of("core")), single));

        assertThat(outcome).isInstanceOf(ToolOutcome.Failure.class);
        var failure = ((ToolOutcome.Failure) outcome).failure();
        assertThat(failure.code().name()).isEqualTo("INVALID_ARGUMENT");
        assertThat(failure.getMessage()).contains("未识别到多模块结构");
        assertThat(failure.hint()).contains("单模块");
    }

    @Test
    @DisplayName("every built-in tool advertises scope in its schema, so a model can discover it")
    void schemaAdvertisesScope() {
        for (ToolProvider tool : tools.values()) {
            assertThat(tool.descriptor().inputSchemaJson())
                .as("%s schema", tool.descriptor().name())
                .contains("\"scope\"");
        }
    }

    private static Path fixture(String name) throws Exception {
        var url = ToolScopeTest.class.getResource("/fixtures/" + name);
        assertThat(url).as("fixture %s must be on the test classpath", name).isNotNull();
        return Path.of(url.toURI());
    }
}

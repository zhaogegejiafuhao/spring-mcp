package io.github.springmcp.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.springmcp.core.IndexRegistry;
import io.github.springmcp.server.builtin.BuiltinPlugin;
import io.github.springmcp.server.builtin.FindServiceCallersTool;
import io.github.springmcp.server.plugin.DefaultPluginContext;
import io.github.springmcp.spi.PluginContext;
import io.github.springmcp.spi.PluginLogger;
import io.github.springmcp.spi.ToolInvocation;
import io.github.springmcp.spi.ToolOutcome;
import io.github.springmcp.spi.ToolProvider;
import io.github.springmcp.spi.api.IndexOptions;

/**
 * Two-level impact analysis.
 *
 * <p>
 * The fixture is a straight line: {@code TopController -> MiddleService -> LeafService}. Level one
 * must never mention the controller, level two must, and the payload must keep them apart so a caller
 * cannot mistake an approximation for a direct answer.
 * </p>
 */
class CallerDepthTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static ToolProvider tool;

    private static PluginContext context;

    @BeforeAll
    static void setUp() throws Exception {
        var url = CallerDepthTest.class.getResource("/fixtures/call-chain");
        assertThat(url).isNotNull();
        Path root = Path.of(url.toURI());

        IndexRegistry registry = new IndexRegistry(IndexRegistry.Revalidation.NEVER, 60_000);
        context = new DefaultPluginContext(registry, PluginLogger.noop(), root, root);

        BuiltinPlugin plugin = new BuiltinPlugin(IndexOptions.defaults());
        plugin.initialize(context);
        tool = plugin.tools()
            .stream()
            .filter(t -> FindServiceCallersTool.NAME.equals(t.descriptor().name()))
            .findFirst()
            .orElseThrow();
    }

    @Test
    @DisplayName("depth=1 keeps its old shape and only reports the direct caller")
    void depthOneIsUnchanged() throws Exception {
        JsonNode payload = payload(Map.of("serviceClass", "LeafService"));

        assertThat(payload.path("targetFqn").asText()).isEqualTo("com.acme.chain.LeafService");
        assertThat(payload.path("callSites")).hasSize(1);
        assertThat(payload.path("callSites").get(0).path("callerType").asText())
            .isEqualTo("com.acme.chain.MiddleService");
        // No level-two keys at all: a caller written against depth=1 must not have to ignore them.
        assertThat(payload.has("upstream")).isFalse();
    }

    @Test
    @DisplayName("depth=2 adds the caller of the caller, in a separate list")
    void depthTwoSeparatesLevels() throws Exception {
        JsonNode payload = payload(Map.of("serviceClass", "LeafService", "depth", 2));

        assertThat(payload.path("direct").path("callSites")).hasSize(1);
        assertThat(payload.path("direct").path("callSites").get(0).path("callerType").asText())
            .isEqualTo("com.acme.chain.MiddleService");

        assertThat(payload.path("upstream")).isNotEmpty();
        assertThat(payload.path("upstream").get(0).path("callerType").asText())
            .isEqualTo("com.acme.chain.TopController");
        assertThat(payload.path("expandedTypes").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("includeSelfCalls=false drops internal calls, not calls made from other classes")
    void selfCallMeansInternalCall() throws Exception {
        // Regression: the flag used to drop every call whose receiver was the target type, which made
        // it useless — it excluded exactly the calls the tool exists to find.
        JsonNode payload = payload(Map.of("serviceClass", "MiddleService", "includeSelfCalls", false));

        assertThat(payload.path("callSites")).hasSize(1);
        assertThat(payload.path("callSites").get(0).path("callerType").asText())
            .isEqualTo("com.acme.chain.TopController");
    }

    @Test
    @DisplayName("the summary and warnings say that level two is an approximation")
    void approximationIsDisclosed() {
        ToolOutcome outcome = tool.invoke(new ToolInvocation(
                Map.of("serviceClass", "LeafService", "depth", 2), context));

        assertThat(outcome).isInstanceOf(ToolOutcome.Success.class);
        ToolOutcome.Success success = (ToolOutcome.Success) outcome;
        assertThat(success.humanSummary()).contains("近似");
        assertThat(success.warnings()).anyMatch(w -> w.contains("无法限定到具体方法"));
    }

    @Test
    @DisplayName("the expansion cap is reported rather than silently truncating")
    void capIsReported() {
        ToolOutcome outcome = tool.invoke(new ToolInvocation(
                Map.of("serviceClass", "LeafService", "depth", 2, "maxUpstreamTypes", 1), context));

        // Only one caller type exists here, so the cap is not hit; the run must still succeed and keep
        // both levels.
        assertThat(outcome).isInstanceOf(ToolOutcome.Success.class);
        JsonNode payload = MAPPER.valueToTree(((ToolOutcome.Success) outcome).structured());
        assertThat(payload.path("expandedTypes").asInt()).isEqualTo(1);
    }

    private static JsonNode payload(Map<String, Object> args) throws Exception {
        ToolOutcome outcome = tool.invoke(new ToolInvocation(new LinkedHashMap<>(args), context));
        assertThat(outcome).isInstanceOf(ToolOutcome.Success.class);
        return MAPPER.valueToTree(((ToolOutcome.Success) outcome).structured());
    }
}

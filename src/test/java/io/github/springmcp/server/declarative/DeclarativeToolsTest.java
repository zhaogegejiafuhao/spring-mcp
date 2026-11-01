package io.github.springmcp.server.declarative;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.springmcp.core.IndexRegistry;
import io.github.springmcp.server.ProjectCatalog;
import io.github.springmcp.server.builtin.BuiltinTools;
import io.github.springmcp.spi.ToolProvider;
import io.github.springmcp.spi.api.IndexOptions;

/**
 * Schema derivation and output shaping are where a declarative tool can go quietly wrong: a tool that
 * cannot receive a required argument, or one whose shaping hides most of the answer.
 */
class DeclarativeToolsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static Map<String, ToolProvider> builtins;

    private static Path fixture;

    @BeforeAll
    static void setUp() {
        var url = DeclarativeToolsTest.class.getResource("/fixtures/demo-web");
        assertThat(url).isNotNull();
        try {
            fixture = Path.of(url.toURI());
        }
        catch (Exception e) {
            throw new IllegalStateException(e);
        }
        IndexRegistry registry = new IndexRegistry(IndexRegistry.Revalidation.NEVER, 60_000);
        builtins = BuiltinTools.byName(registry, ProjectCatalog.single(fixture), IndexOptions.defaults());
    }

    private static DeclarativeConfig.ToolDef tool(String name, String query, Map<String, Object> fixed,
            List<DeclarativeConfig.Param> expose, DeclarativeConfig.Output output) {
        return new DeclarativeConfig.ToolDef(name, name, "测试用工具。不适用：其他情况。", query, fixed, expose, output,
                null);
    }

    @Test
    @DisplayName("fixed arguments leave the schema and stop being required")
    void fixedArgumentsAreRemoved() throws Exception {
        String schema = DeclarativeTools.previewSchema(
                tool("controllers-under-prefix", "list-controllers", Map.of("basePathFilter", "/owners"),
                        List.of(new DeclarativeConfig.Param("httpMethod", null)),
                        new DeclarativeConfig.Output(null, List.of(), null, List.of(), null, null)),
                builtins);

        JsonNode parsed = MAPPER.readTree(schema);
        assertThat(parsed.path("properties").has("basePathFilter")).isFalse();
        assertThat(parsed.path("properties").has("httpMethod")).isTrue();
        assertThat(parsed.path("properties").has("projectRoot")).isFalse();
        assertThat(parsed.path("required")).isEmpty();
    }

    @Test
    @DisplayName("a required base argument that is neither exposed nor fixed is rejected")
    void missingRequiredArgumentIsRejected() {
        DeclarativeConfig.ToolDef def = tool("callers", "find-service-callers", Map.of(),
                List.of(new DeclarativeConfig.Param("methodName", null)), null);

        var result = DeclarativeTools.build(new DeclarativeConfig(List.of(def), List.of()), builtins, fixture, fixture, false);

        assertThat(result.tools()).isEmpty();
        assertThat(result.problems()).singleElement()
            .satisfies(problem -> assertThat(problem).contains("serviceClass").contains("既未 expose 也未 fixed"));
    }

    @Test
    @DisplayName("fixing the required argument lets the tool through")
    void satisfyRequiredArgument() {
        DeclarativeConfig.ToolDef def = tool("who-calls-order-service", "find-service-callers",
                Map.of("serviceClass", "OrderService"), List.of(), null);

        var result = DeclarativeTools.build(new DeclarativeConfig(List.of(def), List.of()), builtins, fixture, fixture, false);

        assertThat(result.problems()).isEmpty();
        assertThat(result.tools()).hasSize(1);
        assertThat(result.tools().get(0).descriptor().description()).contains("不适用");
    }

    @Test
    @DisplayName("an unknown query name is rejected with the available names")
    void unknownQueryIsRejected() {
        DeclarativeConfig.ToolDef def = tool("bad", "list-nothing", Map.of(), List.of(), null);

        var result = DeclarativeTools.build(new DeclarativeConfig(List.of(def), List.of()), builtins, fixture, fixture, false);

        assertThat(result.tools()).isEmpty();
        assertThat(result.problems()).singleElement().satisfies(problem -> assertThat(problem)
            .contains("list-nothing")
            .contains("list-controllers"));
    }

    @Test
    @DisplayName("an unknown fixed argument is rejected rather than silently ignored")
    void unknownFixedArgumentIsRejected() {
        DeclarativeConfig.ToolDef def = tool("typo", "list-controllers", Map.of("basePathFiltr", "/x"), List.of(),
                null);

        var result = DeclarativeTools.build(new DeclarativeConfig(List.of(def), List.of()), builtins, fixture, fixture, false);

        assertThat(result.tools()).isEmpty();
        assertThat(result.problems()).singleElement()
            .satisfies(problem -> assertThat(problem).contains("basePathFiltr"));
    }

    @Test
    @DisplayName("a tool name that is not kebab-case is rejected")
    void badNameIsRejected() {
        DeclarativeConfig.ToolDef def = tool("ListControllers", "list-controllers", Map.of(), List.of(), null);

        var result = DeclarativeTools.build(new DeclarativeConfig(List.of(def), List.of()), builtins, fixture, fixture, false);

        assertThat(result.tools()).isEmpty();
        assertThat(result.problems()).isNotEmpty();
    }

    @Test
    @DisplayName("external tools are skipped by default and reported once")
    void externalSkippedWithoutSwitch() {
        var def = new DeclarativeConfig.ExternalDef("probe", null, "跑个脚本。", List.of("node", "x.js"), null, null,
                null, List.of(), null);

        var result = DeclarativeTools.build(new DeclarativeConfig(List.of(), List.of(def)), builtins, fixture, fixture, false);

        assertThat(result.tools()).isEmpty();
        assertThat(result.problems()).singleElement()
            .satisfies(problem -> assertThat(problem).contains("--allow-external-commands"));
    }

    @Test
    @DisplayName("a shell as command[0] is refused")
    void shellIsRefused() {
        var def = new DeclarativeConfig.ExternalDef("probe", null, "跑个脚本。", List.of("bash", "-c", "rm -rf /"),
                null, null, null, List.of(), null);

        var result = DeclarativeTools.build(new DeclarativeConfig(List.of(), List.of(def)), builtins, fixture, fixture, true);

        assertThat(result.tools()).isEmpty();
        assertThat(result.problems()).singleElement()
            .satisfies(problem -> assertThat(problem).contains("shell"));
    }

    @Test
    @DisplayName("shaping keeps only the selected fields and truncates with a counts summary")
    void shapingSelectsAndTruncates() {
        ToolProvider delegate = builtins.get("list-controllers");
        var outcome = delegate.invoke(new io.github.springmcp.spi.ToolInvocation(
                Map.of("projectRoot", fixture.toString()), null));

        ResultShaper shaper = new ResultShaper();
        ObjectNode shaped = shaper.shape(((io.github.springmcp.spi.ToolOutcome.Success) outcome).structured(),
                new DeclarativeConfig.Output("endpoints", List.of("path", "handlerMethod"), "controllerFqn",
                        List.of(new DeclarativeConfig.Sort("path", Boolean.FALSE)), 5, 2));

        assertThat(shaped.path("source").asText()).isEqualTo("endpoints");
        assertThat(shaped.path("total").asInt()).isEqualTo(8);
        assertThat(shaped.path("returned").asInt()).isEqualTo(5);
        assertThat(shaped.path("truncated").asBoolean()).isTrue();

        JsonNode first = shaped.path("items").get(0);
        assertThat(first.fieldNames()).toIterable()
            .containsExactlyInAnyOrder("path", "handlerMethod", "controllerFqn");
    }

    @Test
    @DisplayName("an empty config builds no tools and reports nothing")
    void emptyConfig() {
        var result = DeclarativeTools.build(DeclarativeConfig.empty(), builtins, fixture, fixture, false);

        assertThat(result.tools()).isEmpty();
        assertThat(result.problems()).isEmpty();
    }
}

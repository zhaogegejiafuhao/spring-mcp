package io.github.springmcp.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.springmcp.core.IndexRegistry;
import io.github.springmcp.server.builtin.BuiltinPlugin;
import io.github.springmcp.server.plugin.DefaultPluginContext;
import io.github.springmcp.spi.PluginContext;
import io.github.springmcp.spi.PluginLogger;
import io.github.springmcp.spi.ToolInvocation;
import io.github.springmcp.spi.ToolOutcome;
import io.github.springmcp.spi.ToolProvider;
import io.github.springmcp.spi.api.IndexOptions;
import io.github.springmcp.spi.model.Controllers;
import io.github.springmcp.spi.model.Mappers;
import io.github.springmcp.spi.model.ServiceCalls;

/**
 * Contract tests for the shipped tools: argument handling, success shape and failure mapping.
 *
 * <p>
 * These call {@link ToolProvider#invoke} directly, which is the same entry point the MCP layer uses.
 * That keeps the tests fast while still covering everything except JSON-RPC framing.
 * </p>
 */
class BuiltinToolsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static Map<String, ToolProvider> tools;

    private static PluginContext context;

    private static Path fixture(String name) {
        var url = BuiltinToolsTest.class.getResource("/fixtures/" + name);
        assertThat(url).as("fixture %s must be on the test classpath", name).isNotNull();
        try {
            return Path.of(url.toURI());
        }
        catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @BeforeAll
    static void setUp() throws Exception {
        IndexRegistry registry = new IndexRegistry(IndexRegistry.Revalidation.NEVER, 60_000);
        context = new DefaultPluginContext(registry, PluginLogger.noop(), fixture("demo-web"), null);

        BuiltinPlugin plugin = new BuiltinPlugin(IndexOptions.defaults());
        plugin.initialize(context);
        tools = new LinkedHashMap<>();
        for (ToolProvider provider : plugin.tools()) {
            tools.put(provider.descriptor().name(), provider);
        }
    }

    private static ToolOutcome invoke(String tool, Map<String, Object> args) {
        return tools.get(tool).invoke(new ToolInvocation(args, context));
    }

    private static <T> T success(ToolOutcome outcome, Class<T> type) {
        assertThat(outcome).isInstanceOf(ToolOutcome.Success.class);
        Object structured = ((ToolOutcome.Success) outcome).structured();
        return type.cast(structured);
    }

    @Test
    @DisplayName("exactly the documented tools are registered, under kebab-case names")
    void toolRegistry() {
        assertThat(tools.keySet()).containsExactlyInAnyOrder("list-controllers", "find-service-callers",
                "find-implementations", "list-mappers", "search-symbol", "search-by-annotation",
                "get-file-dependencies", "list-gateway-routes", "list-feign-clients");

        for (ToolProvider provider : tools.values()) {
            var d = provider.descriptor();
            assertThat(d.name()).matches("[a-z][a-z0-9]*(-[a-z0-9]+)*");
            assertThat(d.description()).isNotBlank();
            // The description is the main lever on tool selection, so it must say when NOT to use it.
            assertThat(d.description()).containsIgnoringCase("不适用");
        }
    }

    @Test
    @DisplayName("every input schema is a parseable JSON object")
    void schemasAreValidJson() throws Exception {
        for (ToolProvider provider : tools.values()) {
            String json = provider.descriptor().inputSchemaJson();
            Map<String, Object> parsed = MAPPER.readValue(json, new TypeReference<>() {
            });
            assertThat(parsed.get("type")).as("tool %s", provider.descriptor().name()).isEqualTo("object");
            assertThat(parsed).containsKey("properties");
        }
    }

    @Test
    @DisplayName("list-controllers returns the endpoint report")
    void listControllers() {
        Controllers.Report report = success(
                invoke("list-controllers", Map.of("projectRoot", fixture("demo-web").toString())),
                Controllers.Report.class);
        assertThat(report.endpoints()).hasSize(8);
        assertThat(report.controllerCount()).isEqualTo(4);
    }

    @Test
    @DisplayName("list-controllers paginates without pretending the totals shrank")
    void listControllersPagination() {
        String root = fixture("demo-web").toString();

        ToolOutcome first = invoke("list-controllers", Map.of("projectRoot", root, "limit", 3));
        Controllers.Report page = success(first, Controllers.Report.class);
        assertThat(page.endpoints()).hasSize(3);
        // 分页只切列表，统计数字仍是全量 —— 否则调用方会以为这个工程只有 3 个端点
        assertThat(page.controllerCount()).isEqualTo(4);
        assertThat(page.methodCount()).isEqualTo(7);
        assertThat(((ToolOutcome.Success) first).warnings())
                .anyMatch(w -> w.contains("已分页") && w.contains("共 8 条"));

        Controllers.Report tail = success(
                invoke("list-controllers", Map.of("projectRoot", root, "offset", 6)),
                Controllers.Report.class);
        assertThat(tail.endpoints()).hasSize(2);

        // 小工程不受默认分页影响：默认上一页 200 条，8 条全在页内
        Controllers.Report all = success(
                invoke("list-controllers", Map.of("projectRoot", root)), Controllers.Report.class);
        assertThat(all.endpoints()).hasSize(8);
    }

    @Test
    @DisplayName("stats count the whole match, never the current page")
    void statsIgnorePaging() {
        String root = fixture("demo-web").toString();

        Controllers.Report full = success(
                invoke("list-controllers", Map.of("projectRoot", root)), Controllers.Report.class);
        // 8 条端点、9 个「动词×端点」映射：/api/orders/legacy 同时是 GET 和 POST
        assertThat(full.stats().byMethod()).containsEntry("GET", 7).containsEntry("POST", 2);
        // 一条 handler 映射两个 URL，所以端点比 handler 多一个
        assertThat(full.stats().byController()).containsEntry("OrderController", 4);
        assertThat(full.stats().unresolved()).isEqualTo(1);
        assertThat(full.stats().inherited()).isZero();

        Controllers.Report page = success(
                invoke("list-controllers", Map.of("projectRoot", root, "limit", 2)), Controllers.Report.class);
        assertThat(page.endpoints()).hasSize(2);
        // 分页不改统计：否则模型会说这个工程只有 2 个 GET
        assertThat(page.stats().byMethod()).isEqualTo(full.stats().byMethod());
        assertThat(page.stats().byController()).isEqualTo(full.stats().byController());
    }

    @Test
    @DisplayName("find-service-callers returns resolved and unresolved call sites")
    void findServiceCallers() {
        ServiceCalls.Report report = success(invoke("find-service-callers",
                Map.of("projectRoot", fixture("layered-callers").toString(), "serviceClass", "OrderService")),
                ServiceCalls.Report.class);
        assertThat(report.resolvedCount()).isEqualTo(5);
        assertThat(report.unresolvedCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("list-mappers accepts a kinds filter")
    void listMappers() {
        Mappers.Report report = success(
                invoke("list-mappers",
                        Map.of("projectRoot", fixture("jpa-repos").toString(), "kinds", List.of("JPA"))),
                Mappers.Report.class);
        assertThat(report.mappers()).hasSize(1);
        assertThat(report.mappers().get(0).kind()).isEqualTo(Mappers.Kind.JPA);
    }

    @Test
    @DisplayName("search-by-annotation requires the annotation argument")
    void searchByAnnotationNeedsAnnotation() {
        ToolOutcome outcome = invoke("search-by-annotation", Map.of("projectRoot", fixture("demo-web").toString()));
        assertThat(outcome).isInstanceOf(ToolOutcome.Failure.class);
        var failure = ((ToolOutcome.Failure) outcome).failure();
        assertThat(failure.code()).isEqualTo(io.github.springmcp.spi.ErrorCode.INVALID_ARGUMENT);
        assertThat(failure.getMessage()).contains("annotation");
    }

    @Test
    @DisplayName("get-file-dependencies rejects a path outside the index with a clear code")
    void fileDependenciesOutsideIndex() {
        ToolOutcome outcome = invoke("get-file-dependencies",
                Map.of("projectRoot", fixture("demo-web").toString(), "file", "src/main/java/com/acme/Nope.java"));
        assertThat(outcome).isInstanceOf(ToolOutcome.Failure.class);
        assertThat(((ToolOutcome.Failure) outcome).failure().code())
            .isEqualTo(io.github.springmcp.spi.ErrorCode.NOT_A_SOURCE_FILE);
    }

    @Test
    @DisplayName("an unknown class is reported, not thrown")
    void unknownServiceClass() {
        ToolOutcome outcome = invoke("find-service-callers",
                Map.of("projectRoot", fixture("layered-callers").toString(), "serviceClass", "Ghost"));
        assertThat(outcome).isInstanceOf(ToolOutcome.Failure.class);
        assertThat(((ToolOutcome.Failure) outcome).failure().code())
            .isEqualTo(io.github.springmcp.spi.ErrorCode.CLASS_NOT_FOUND);
    }

    @Test
    @DisplayName("with no projectRoot and no default, the error explains how to fix it")
    void missingProjectRoot() {
        ToolOutcome outcome = invoke("list-controllers", Map.of());
        assertThat(outcome).isInstanceOf(ToolOutcome.Failure.class);
        var failure = ((ToolOutcome.Failure) outcome).failure();
        assertThat(failure.code()).isEqualTo(io.github.springmcp.spi.ErrorCode.INVALID_ARGUMENT);
        assertThat(failure.hint()).contains("--project");
    }

    @Test
    @DisplayName("a directory without sources is refused instead of answering 'zero endpoints'")
    void directoryWithoutSources(@TempDir Path root) throws Exception {
        // Pointing at the front-end half of a monorepo, or at a parent directory, is the easiest way to
        // use these tools wrongly. "0 endpoints" for such a path is indistinguishable from a service
        // that really exposes nothing, so the tool has to say which one it is.
        Files.writeString(root.resolve("package.json"), "{ \"name\": \"admin-ui\" }");

        ToolOutcome outcome = invoke("list-controllers", Map.of("projectRoot", root.toString()));

        assertThat(outcome).isInstanceOf(ToolOutcome.Failure.class);
        var failure = ((ToolOutcome.Failure) outcome).failure();
        assertThat(failure.code()).isEqualTo(io.github.springmcp.spi.ErrorCode.INVALID_ARGUMENT);
        assertThat(failure.getMessage()).contains("没有 Java/Kotlin 源码");
        assertThat(failure.hint()).contains("list-modules");
    }

    @Test
    @DisplayName("a configured default project root is used when the argument is omitted")
    void defaultProjectRoot() throws Exception {
        IndexRegistry registry = new IndexRegistry(IndexRegistry.Revalidation.NEVER, 60_000);
        PluginContext defaulted = new DefaultPluginContext(registry, PluginLogger.noop(), fixture("demo-web"),
                fixture("demo-web"));

        BuiltinPlugin plugin = new BuiltinPlugin(IndexOptions.defaults());
        plugin.initialize(defaulted);
        ToolProvider controllers = plugin.tools().get(0);

        ToolOutcome outcome = controllers.invoke(new ToolInvocation(Map.of(), defaulted));
        assertThat(outcome).isInstanceOf(ToolOutcome.Success.class);
        assertThat(((Controllers.Report) ((ToolOutcome.Success) outcome).structured()).endpoints()).hasSize(8);
    }
}

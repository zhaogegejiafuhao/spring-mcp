package io.github.springmcp.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.springmcp.core.index.SourceIndex;
import io.github.springmcp.spi.ToolFailure;
import io.github.springmcp.spi.api.IndexOptions;
import io.github.springmcp.spi.api.QueryScope;
import io.github.springmcp.spi.model.GatewayRoutes;

/**
 * The gateway route table is the only description of a microservice system's public surface that a
 * source analysis cannot infer.
 *
 * <p>
 * Both property paths are covered because they moved between releases: {@code spring.cloud.gateway.routes}
 * in 3.x and {@code spring.cloud.gateway.server.webflux.routes} in 4.x, which is what mall-swarm uses.
 * Getting this wrong would return "no gateway" for every up-to-date project.
 * </p>
 */
class GatewayRoutesTest {

    private static IndexRegistry registry;

    @BeforeAll
    static void setUp() {
        registry = new IndexRegistry(IndexRegistry.Revalidation.NEVER, 60_000);
    }

    private static GatewayRoutes routes() throws ToolFailure {
        return routes(false);
    }

    private static GatewayRoutes routes(boolean includeTests) throws ToolFailure {
        SourceIndex index = (SourceIndex) registry.open(Fixtures.path("gateway"),
                IndexOptions.defaults().withIncludeTests(includeTests));
        return index.gatewayRoutes();
    }

    @Test
    @DisplayName("both the 3.x and the 4.x property paths are read")
    void bothPropertyPathsAreRead() throws ToolFailure {
        assertThat(routes().routes()).extracting(GatewayRoutes.Route::id)
            .contains("orders", "notifications", "legacy", "self");
    }

    @Test
    @DisplayName("lb:// targets resolve to a module, absolute URIs do not")
    void serviceIdsResolveToModules() throws ToolFailure {
        assertThat(routes().routes()).filteredOn(route -> "orders".equals(route.id()))
            .singleElement()
            .satisfies(route -> {
                assertThat(route.serviceId()).isEqualTo("order-service");
                assertThat(route.targetModule()).isEqualTo("order-service");
                assertThat(route.pathPrefixes()).containsExactly("/api/orders/**");
                assertThat(route.stripPrefix()).isEqualTo(1);
                assertThat(route.file()).isEqualTo("gateway-app/src/main/resources/application.yml");
            });

        // An absolute URI is a real route to somewhere else; reporting a module for it would invent one.
        assertThat(routes().routes()).filteredOn(route -> "legacy".equals(route.id()))
            .singleElement()
            .satisfies(route -> {
                assertThat(route.serviceId()).isNull();
                assertThat(route.targetModule()).isNull();
                assertThat(route.stripPrefix()).isNull();
            });
    }

    @Test
    @DisplayName("the implicit discovery routes are announced, not silently dropped")
    void discoveryLocatorIsReported() throws ToolFailure {
        assertThat(routes().discoveryLocatorEnabled()).isTrue();
        assertThat(routes().warnings()).anyMatch(warning -> warning.contains("discovery.locator.enabled"));
    }

    @Test
    @DisplayName("test resources follow the includeTests flag")
    void testResourcesFollowTheFlag() throws ToolFailure {
        assertThat(routes(false).routes()).extracting(GatewayRoutes.Route::id).doesNotContain("test-only");
        assertThat(routes(true).routes()).extracting(GatewayRoutes.Route::id).contains("test-only");
    }

    @Test
    @DisplayName("scope narrows routes by their target module, keeping unresolvable ones")
    void scopeFiltersByTargetModule() throws ToolFailure {
        SourceIndex index = (SourceIndex) registry.open(Fixtures.path("gateway"), IndexOptions.defaults());
        GatewayRoutes scoped = index.scoped(QueryScope.of(List.of("order-service"), List.of())).gatewayRoutes();

        // orders and self point at order-service; legacy points outside the project and is kept, because
        // scoping says nothing about a target that is not part of this repository.
        assertThat(scoped.routes()).extracting(GatewayRoutes.Route::id)
            .containsExactlyInAnyOrder("orders", "self", "legacy", "code-orders", "code-external");
        assertThat(scoped.warnings()).anyMatch(warning -> warning.contains("作用域"));
    }

    @Test
    @DisplayName("routes defined as a RouteLocator bean are read, in both Java and Kotlin")
    void routesDeclaredInCodeAreRead() throws ToolFailure {
        assertThat(routes().routes()).filteredOn(route -> "code-orders".equals(route.id()))
            .singleElement()
            .satisfies(route -> {
                assertThat(route.uri()).isEqualTo("lb://order-service");
                assertThat(route.serviceId()).isEqualTo("order-service");
                assertThat(route.targetModule()).isEqualTo("order-service");
                assertThat(route.pathPrefixes()).containsExactly("/code/orders/**");
                assertThat(route.stripPrefix()).isEqualTo(2);
                assertThat(route.file()).isEqualTo("gateway-app/src/main/java/com/acme/gateway/CodeRoutes.java");
            });

        assertThat(routes().routes()).filteredOn(route -> "kt-notify".equals(route.id()))
            .singleElement()
            .satisfies(route -> {
                assertThat(route.serviceId()).isEqualTo("notify-service");
                assertThat(route.targetModule()).isEqualTo("notify-service");
                assertThat(route.pathPrefixes()).containsExactly("/kt/notify/**");
                assertThat(route.stripPrefix()).isEqualTo(1);
                assertThat(route.file()).isEqualTo("gateway-app/src/main/kotlin/com/acme/gateway/KotlinRoutes.kt");
            });

        // A route to somewhere outside the project keeps a null module instead of inventing one.
        assertThat(routes().routes()).filteredOn(route -> "code-external".equals(route.id()))
            .singleElement()
            .satisfies(route -> {
                // The value came from a local literal variable; an unresolved expression would keep
                // the variable name here instead.
                assertThat(route.uri()).isEqualTo("https://example.com");
                assertThat(route.serviceId()).isNull();
                assertThat(route.targetModule()).isNull();
            });

        assertThat(routes().warnings()).anyMatch(warning -> warning.contains("RouteLocator"));
    }

    @Test
    @DisplayName("a project without a gateway reports zero routes and says why")
    void noGatewayIsNotAnError() throws ToolFailure {
        SourceIndex index = (SourceIndex) registry.open(Fixtures.path("demo-web"), IndexOptions.defaults());

        GatewayRoutes report = index.gatewayRoutes();
        assertThat(report.routes()).isEmpty();
        assertThat(report.count()).isZero();
    }

    @Test
    @DisplayName("route order is stable across snapshots")
    void orderIsStable() throws ToolFailure {
        Set<String> first = Set.copyOf(routes().routes().stream().map(GatewayRoutes.Route::id).toList());
        Set<String> second = Set.copyOf(routes().routes().stream().map(GatewayRoutes.Route::id).toList());
        assertThat(first).isEqualTo(second);
    }

    @Test
    @DisplayName("a Maven filter placeholder does not cost the whole file its routes")
    void mavenPlaceholderIsNotFatal(@TempDir Path root) throws Exception {
        // Spring Boot's parent POM filters resources, so @project.version@ and friends sit in
        // application.yml in plain text. YAML forbids a plain scalar starting with '@', so the parser
        // rejected the document and every route in it disappeared behind a warning.
        write(root, "gateway-app/src/main/resources/application.yml", """
                spring:
                  application:
                    name: gateway-app
                  cloud:
                    gateway:
                      routes:
                        - id: orders
                          uri: lb://order-service
                          predicates:
                            - Path=/api/orders/**
                          filters:
                            - StripPrefix=1
                logging:
                  level:
                    com.acme: @logging.level@
                """);

        GatewayRoutes report = open(root).gatewayRoutes();

        assertThat(report.routes()).extracting(GatewayRoutes.Route::id).containsExactly("orders");
        assertThat(report.warnings()).noneMatch(warning -> warning.contains("解析失败"));
    }

    @Test
    @DisplayName("a genuinely broken file is reported with the line that broke it")
    void brokenYamlNamesTheLine(@TempDir Path root) throws Exception {
        // A tab used as indentation: YAML forbids it, and the parser stops at that line rather than
        // at the end of the document.
        write(root, "gateway-app/src/main/resources/application.yml",
                "spring:\n\tcloud:\n\t\tgateway:\n\t\t\troutes:\n\t\t\t\t- id: orders\n");

        GatewayRoutes report = open(root).gatewayRoutes();

        assertThat(report.routes()).isEmpty();
        assertThat(report.warnings()).filteredOn(warning -> warning.contains("解析失败"))
            .singleElement()
            .satisfies(warning -> {
                assertThat(warning).contains("application.yml");
                assertThat(warning).contains("行");
            });
    }

    private static SourceIndex open(Path root) throws ToolFailure {
        return (SourceIndex) new IndexRegistry(IndexRegistry.Revalidation.NEVER, 60_000)
            .open(root, IndexOptions.defaults());
    }

    private static void write(Path root, String relative, String content) throws Exception {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }
}

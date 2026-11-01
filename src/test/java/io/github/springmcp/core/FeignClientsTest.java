package io.github.springmcp.core;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.springmcp.core.index.SourceIndex;
import io.github.springmcp.spi.ToolFailure;
import io.github.springmcp.spi.api.QueryScope;
import io.github.springmcp.spi.model.FeignClients;

/**
 * Feign clients are the outbound edge of the service graph.
 *
 * <p>
 * The gateway fixture describes who can reach this system; these clients describe who the system
 * reaches. Neither alone is a dependency graph, so this fixture pairs the two shapes that matter: a
 * client pointing at a module of the same repository, and a client pointing outside it.
 * </p>
 */
class FeignClientsTest {

    private static SourceIndex index;

    @BeforeAll
    static void setUp() throws ToolFailure {
        index = (SourceIndex) new IndexRegistry(IndexRegistry.Revalidation.NEVER, 60_000)
            .open(Fixtures.path("feign-cloud"));
    }

    @Test
    @DisplayName("a client naming a module of this project resolves to that module")
    void clientResolvesToModule() throws ToolFailure {
        FeignClients.Report report = index.feignClients(new FeignClients.Query(null));

        assertThat(report.clients()).filteredOn(client -> "notify-service".equals(client.name()))
            .singleElement()
            .satisfies(client -> {
                assertThat(client.targetModule()).isEqualTo("notify-service");
                assertThat(client.pathPrefix()).isEqualTo("/api/notify");
                assertThat(client.url()).isNull();
                assertThat(client.endpoints())
                    .extracting(endpoint -> String.join(",", endpoint.httpMethods()) + " " + endpoint.path())
                    .containsExactly("POST /send", "GET /status/{id}");
            });
    }

    @Test
    @DisplayName("a client with a URL points outside, so no module is invented")
    void externalClientHasNoModule() throws ToolFailure {
        FeignClients.Report report = index.feignClients(new FeignClients.Query("partner"));

        assertThat(report.total()).isEqualTo(1);
        FeignClients.Client client = report.clients().get(0);
        assertThat(client.url()).isEqualTo("https://partner.example.com");
        assertThat(client.targetModule()).isNull();
        // A bare @RequestMapping restricts no verb, and saying GET would be a guess.
        assertThat(client.endpoints().get(0).httpMethods()).containsExactly("ANY");
    }

    @Test
    @DisplayName("a client with no mapped method is reported, not counted as an empty contract")
    void clientWithoutMethodsWarns() throws ToolFailure {
        FeignClients.Report report = index.feignClients(new FeignClients.Query(null));

        assertThat(report.warnings()).anyMatch(warning -> warning.contains("EmptyClient")
                && warning.contains("没有映射注解"));
    }

    @Test
    @DisplayName("the module roll-up groups clients by the service they call")
    void byModuleCounts() throws ToolFailure {
        FeignClients.Report report = index.feignClients(new FeignClients.Query(null));

        assertThat(report.total()).isEqualTo(3);
        assertThat(report.byModule()).containsEntry("notify-service", 1).hasSize(1);
    }

    @Test
    @DisplayName("scoping narrows the declaring module, not the target")
    void scopeNarrowsDeclarations() throws ToolFailure {
        FeignClients.Report scoped = index.scoped(QueryScope.of(java.util.List.of("notify-service"), java.util.List.of()))
            .feignClients(new FeignClients.Query(null));

        // Every client lives in order-service, which is out of scope; the targets are irrelevant.
        assertThat(scoped.total()).isZero();
    }
}

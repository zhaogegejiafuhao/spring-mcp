package io.github.springmcp.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.springmcp.core.index.SourceIndex;
import io.github.springmcp.spi.ToolFailure;
import io.github.springmcp.spi.model.Controllers;

/**
 * Functional endpoints are endpoints.
 *
 * <p>
 * {@code RouterFunctions.route().GET("/x", handler)} has no annotation to find, so an
 * annotation-only reader reports the module as having none — and the answer looks complete. The
 * fixture covers all three shapes, including the one whose predicate is a parameter and therefore
 * cannot be named.
 * </p>
 */
class FunctionalRoutesTest {

    private static SourceIndex index;

    @BeforeAll
    static void setUp() throws ToolFailure {
        index = (SourceIndex) new IndexRegistry(IndexRegistry.Revalidation.NEVER, 60_000)
            .open(Fixtures.path("functional-web"));
    }

    @Test
    @DisplayName("all three declaration shapes are read, in Java and in Kotlin")
    void routesAreRead() {
        Controllers.Report report = index.controllers(new Controllers.Query(false, null, null));

        assertThat(report.endpoints())
            .extracting(endpoint -> String.join(",", endpoint.httpMethods()) + " " + endpoint.path())
            .containsExactlyInAnyOrder("GET /person/{id}", "POST /person", "DELETE /person/{id}", "GET /ping",
                    "POST /pong", "GET /kt/person/{id}", "POST /kt/person", "GET /kt/ping");
        // No @Controller anywhere: the endpoints exist without one.
        assertThat(report.controllerCount()).isZero();
        assertThat(report.methodCount()).isEqualTo(8);
    }

    @Test
    @DisplayName("the handler is the last argument, not the predicate before it")
    void handlerIsTheLastArgument() {
        Controllers.Report report = index.controllers(new Controllers.Query(false, null, null));

        assertThat(report.endpoints())
            .filteredOn(endpoint -> "/person".equals(endpoint.path()))
            .singleElement()
            .satisfies(endpoint -> {
                assertThat(endpoint.handlerMethod()).isEqualTo("createPerson");
                assertThat(endpoint.signature()).isEqualTo("handler::createPerson");
            });
    }

    @Test
    @DisplayName("a route whose predicate is a parameter is counted, not invented")
    void undecidableRouteIsCounted() {
        Controllers.Report report = index.controllers(new Controllers.Query(false, null, null));

        assertThat(report.endpoints()).extracting(Controllers.Endpoint::path)
            .doesNotContain("predicate");
        assertThat(report.warnings())
            .anyMatch(warning -> warning.contains("1 个 RouterFunctions.route(...) 没有字面量路径"));
        assertThat(report.warnings())
            .anyMatch(warning -> warning.contains("8 个端点来自 RouterFunctions"));
    }

    @Test
    @DisplayName("the httpMethod filter also applies to functional endpoints")
    void methodFilterApplies() {
        Controllers.Report report = index.controllers(new Controllers.Query(false, null, "POST"));

        assertThat(report.endpoints()).isNotEmpty()
            .allSatisfy(endpoint -> assertThat(endpoint.httpMethods()).contains("POST"));
        assertThat(report.endpoints()).extracting(Controllers.Endpoint::path)
            .containsExactlyInAnyOrder("/person", "/pong", "/kt/person");
    }

    @Test
    @DisplayName("functional endpoints carry the declaring class and a usable signature")
    void endpointCarriesProvenance() {
        List<Controllers.Endpoint> endpoints = index.controllers(new Controllers.Query(false, null, null))
            .endpoints();

        assertThat(endpoints).allSatisfy(endpoint -> {
            assertThat(endpoint.file()).isNotBlank();
            assertThat(endpoint.line()).isPositive();
            assertThat(endpoint.controllerFqn()).contains("com.acme.functional.");
            assertThat(endpoint.pathResolved()).isTrue();
        });
    }
}

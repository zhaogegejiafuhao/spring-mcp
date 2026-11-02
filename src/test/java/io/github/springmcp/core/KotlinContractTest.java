package io.github.springmcp.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.springmcp.spi.ToolFailure;
import io.github.springmcp.spi.api.IndexOptions;
import io.github.springmcp.spi.model.Controllers;

/**
 * Reconciles the Kotlin fixture against a table transcribed from its source, by hand.
 *
 * <p>
 * The Java side of this project has an independent oracle: thousands of URLs written by other people
 * in front-end API files and integration tests, which no one wrote with this tool in mind. Kotlin has
 * no such corpus. {@code KotlinParsingTest} proved the parser does not crash and
 * {@code KotlinConstantPathTest} proved two folding rules work; neither answers "read these .kt files
 * and tell me every URL they serve".
 * </p>
 *
 * <p>
 * So the oracle here is a reading of `fixtures/kotlin-web`, written down endpoint by endpoint with
 * the reason each path comes out the way it does. It is weaker than a real corpus and it is the
 * strongest thing available today; if a change to the Kotlin path breaks a rule, this test says which
 * URL and what it expected, rather than failing somewhere downstream with a different count.
 * </p>
 *
 * <p>
 * The rules exercised below, all of which the Java implementation shares: class prefix + method path
 * join, constant folding through {@code object} members, placeholders staying unresolved, regex
 * constraints passing through untouched, and a class-level prefix inherited from an abstract base.
 * </p>
 */
class KotlinContractTest {

    /**
     * One row of the expected table.
     *
     * @param verbs HTTP methods, comma-joined, in declaration order
     * @param path the served path, or the raw text when it could not be resolved
     * @param resolved whether the path is a static literal
     * @param handler handler method name
     * @param declaredIn fully qualified type that declared the handler
     */
    private record Row(String verbs, String path, boolean resolved, String handler, String declaredIn) {

        static Row of(Controllers.Endpoint endpoint) {
            return new Row(String.join(",", endpoint.httpMethods()), endpoint.path(), endpoint.pathResolved(),
                    endpoint.handlerMethod(), endpoint.handlerDeclaredIn());
        }
    }

    private static Controllers.Report analyseKotlinFixture() throws ToolFailure {
        return new IndexRegistry(IndexRegistry.Revalidation.NEVER, 60_000)
            .open(Fixtures.path("kotlin-web"), IndexOptions.defaults())
            .controllers(new Controllers.Query(false, null, null));
    }

    @Test
    @DisplayName("the Kotlin fixture serves exactly the eight endpoints read off its source")
    void everyDeclaredMappingIsReported() throws ToolFailure {
        Controllers.Report report = analyseKotlinFixture();

        assertThat(report.endpoints().stream().map(Row::of).toList()).containsExactlyInAnyOrder(
                // AbstractCrudController.kt: class @RequestMapping("/api") + @GetMapping("/ping")
                new Row("GET", "/api/ping", true, "ping", "com.acme.kotlin.AbstractCrudController"),
                // PingController.kt: own @GetMapping("/pong"), prefix inherited from the abstract base
                new Row("GET", "/api/pong", true, "pong", "com.acme.kotlin.PingController"),
                // OrderController.kt: @RequestMapping("/orders") + @GetMapping("/{id}")
                new Row("GET", "/orders/{id}", true, "detail", "com.acme.kotlin.OrderController"),
                // @PostMapping with no path argument: the class prefix alone is the URL
                new Row("POST", "/orders", true, "create", "com.acme.kotlin.OrderController"),
                // @RequestMapping(method = [PUT], path = ["/bulk"]): a single-element path array
                new Row("PUT", "/orders/bulk", true, "bulkUpdate", "com.acme.kotlin.OrderController"),
                new Row("DELETE", "/orders/{id}", true, "remove", "com.acme.kotlin.OrderController"),
                // ApiPaths.BASE + "/summary" folds through an `object`'s const val to /api/summary
                new Row("GET", "/orders/api/summary", true, "summary", "com.acme.kotlin.OrderController"),
                // "/echo/$id" is a Kotlin string template: the value exists only at runtime
                new Row("GET", "/orders/echo/${id}", false, "echo", "com.acme.kotlin.OrderController"));
    }

    @Test
    @DisplayName("the one template path is marked unresolved with a reason, not quietly cleaned up")
    void templatePathCarriesItsReason() throws ToolFailure {
        Controllers.Report report = analyseKotlinFixture();

        Controllers.Endpoint echo = report.endpoints()
            .stream()
            .filter(endpoint -> "echo".equals(endpoint.handlerMethod()))
            .findFirst()
            .orElseThrow();

        assertThat(echo.pathResolved()).isFalse();
        assertThat(echo.unresolvedReason()).isEqualTo("PLACEHOLDER");
        // The template survives verbatim: rewriting it to "/orders/echo/{id}" would invent a URL that
        // the running application never serves.
        assertThat(echo.path()).isEqualTo("/orders/echo/${id}");
    }

    @Test
    @DisplayName("inherited handlers are counted and attributed to the declaring type")
    void inheritedHandlerIsNamedAndBounded() throws ToolFailure {
        Controllers.Report report = analyseKotlinFixture();

        assertThat(report.controllerCount()).isEqualTo(2);
        // Three declared handlers on OrderController + two on PingController, one of which
        // (ping) is declared on the base class and therefore reached through inheritance.
        assertThat(report.methodCount()).isEqualTo(8);
        assertThat(report.warnings())
            .anyMatch(warning -> warning.contains("1 个 handler 继承自父类或接口"))
            .anyMatch(warning -> warning.contains("1 个 handler 的映射路径无法静态求值"));
    }
}

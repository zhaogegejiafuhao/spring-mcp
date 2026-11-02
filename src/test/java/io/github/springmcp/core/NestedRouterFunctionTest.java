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
 * {@code nest(...)} decides whether a reported path is the URL a client calls.
 *
 * <p>
 * Measured on halo: 198 functional endpoints, every one of them reported without the
 * {@code /apis/<group>/<version>} prefix that {@code CustomEndpointsBuilder} adds at registration,
 * and no warning anywhere in the output. A path that looks right, is served nowhere, and carries no
 * marker is the worst answer this project can give — worse than no answer, because the reader has no
 * way to tell.
 * </p>
 *
 * <p>
 * So two rules, both tested here: a literal prefix is applied to the routes inside the nesting and to
 * nothing else; a prefix that cannot be read leaves those routes marked unresolved instead of
 * complete.
 * </p>
 */
class NestedRouterFunctionTest {

    private static SourceIndex index;

    @BeforeAll
    static void setUp() throws ToolFailure {
        index = (SourceIndex) new IndexRegistry(IndexRegistry.Revalidation.NEVER, 60_000)
            .open(Fixtures.path("functional-nested"));
    }

    @Test
    @DisplayName("a literal nest prefix is applied to the routes inside it")
    void literalPrefixIsApplied() {
        Controllers.Report report = index.controllers(new Controllers.Query(false, null, null));

        assertThat(report.endpoints())
            .extracting(endpoint -> String.join(",", endpoint.httpMethods()) + " " + endpoint.path())
            .contains("GET /apis/console/v1alpha1/posts", "POST /apis/console/v1alpha1/posts");
        assertThat(report.endpoints())
            .filteredOn(endpoint -> endpoint.path().endsWith("/posts"))
            .allSatisfy(endpoint -> {
                assertThat(endpoint.pathResolved()).isTrue();
                assertThat(endpoint.classPathResolved()).isTrue();
                assertThat(endpoint.classPathRaw()).isEqualTo("/apis/console/v1alpha1");
            });
    }

    @Test
    @DisplayName("a sibling of the nest does not inherit its prefix")
    void siblingIsNotNested() {
        Controllers.Endpoint health = index.controllers(new Controllers.Query(false, null, null))
            .endpoints()
            .stream()
            .filter(endpoint -> "health".equals(endpoint.handlerMethod()))
            .findFirst()
            .orElseThrow();

        // Containment is what decides: the prefix belongs to the router supplier, not to the file.
        assertThat(health.path()).isEqualTo("/health");
        assertThat(health.pathResolved()).isTrue();
        assertThat(health.classPathRaw()).isEmpty();
    }

    @Test
    @DisplayName("an unreadable nest prefix leaves the route unresolved instead of looking complete")
    void unreadablePrefixIsMarked() {
        Controllers.Report report = index.controllers(new Controllers.Query(false, null, null));

        Controllers.Endpoint tags = report.endpoints()
            .stream()
            .filter(endpoint -> "listTags".equals(endpoint.handlerMethod()))
            .findFirst()
            .orElseThrow();

        assertThat(tags.pathResolved()).isFalse();
        assertThat(tags.unresolvedReason()).isEqualTo("NESTED_PREFIX");
        // The path stays exactly as written: appending a guess would be the failure, not the fix.
        assertThat(tags.path()).isEqualTo("/tags");
    }

    @Test
    @DisplayName("a nest wrapping a router built elsewhere is reported, not silently ignored")
    void structuralNestIsReported() {
        Controllers.Report report = index.controllers(new Controllers.Query(false, null, null));

        // halo's shape: CustomEndpointsBuilder nests what each CustomEndpoint builds. No lexical walk
        // can attach that prefix, but staying quiet would leave every path looking callable.
        assertThat(report.warnings())
            .anyMatch(warning -> warning.contains("1 处 nest(...) 的前缀作用在别处"))
            .anyMatch(warning -> warning.contains("RegisteredRoutesConfig.java"));
    }

    @Test
    @DisplayName("the report says how many endpoints are missing a prefix, and where to look")
    void warningNamesTheNesting() {
        Controllers.Report report = index.controllers(new Controllers.Query(false, null, null));

        assertThat(report.warnings())
            .anyMatch(warning -> warning.contains("1 个端点位于 nest(...) 之下"))
            .anyMatch(warning -> warning.contains("NestedRoutesConfig.java")
                    && warning.contains("/apis/"));

        List<Controllers.Endpoint> resolved = report.endpoints()
            .stream()
            .filter(Controllers.Endpoint::pathResolved)
            .toList();
        // Three of the four routes are fully resolved; only the dynamic one is not.
        assertThat(resolved).hasSize(3);
    }
}

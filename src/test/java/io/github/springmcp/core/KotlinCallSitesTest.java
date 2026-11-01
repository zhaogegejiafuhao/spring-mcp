package io.github.springmcp.core;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.springmcp.core.index.SourceIndex;
import io.github.springmcp.spi.ToolFailure;
import io.github.springmcp.spi.model.ResolutionRule;
import io.github.springmcp.spi.model.ServiceCalls;

/**
 * A pure-Kotlin project gets call sites, not a shrug.
 *
 * <p>
 * The receiver rules are the whole story: a constructor property, a method parameter and a local
 * {@code val} are all typed from syntax, and each one is a different rule in the answer so a reader
 * can tell how the type was found. Anything else — a chained call, a lambda receiver — stays
 * unresolved rather than being guessed.
 * </p>
 */
class KotlinCallSitesTest {

    private static SourceIndex index;

    @BeforeAll
    static void setUp() throws ToolFailure {
        index = (SourceIndex) new IndexRegistry(IndexRegistry.Revalidation.NEVER, 60_000)
            .open(Fixtures.path("kotlin-web"));
    }

    private static ServiceCalls.Report callers(String type, String method) throws ToolFailure {
        return index.findServiceCallers(new ServiceCalls.Query(type, method, false, true, true, 100));
    }

    @Test
    @DisplayName("a constructor property, a parameter and a local val are each resolved by their own rule")
    void receiverRules() throws ToolFailure {
        ServiceCalls.Report report = callers("OrderService", null);

        assertThat(report.callSites()).extracting(ServiceCalls.CallSite::rule)
            .contains(ResolutionRule.FIELD, ResolutionRule.METHOD_PARAM, ResolutionRule.LOCAL_VAR);
        assertThat(report.callSites()).extracting(ServiceCalls.CallSite::receiverExpression)
            .contains("service", "target", "local");
    }

    @Test
    @DisplayName("call sites carry the enclosing Kotlin function and a readable signature")
    void callSitesCarryContext() throws ToolFailure {
        ServiceCalls.Report report = callers("OrderService", "detail");

        assertThat(report.callSites()).isNotEmpty().allSatisfy(site -> {
            assertThat(site.file()).endsWith(".kt");
            assertThat(site.callerMethod()).isNotBlank();
            assertThat(site.codeSnippet()).contains(".detail(");
        });
        assertThat(report.callSites()).extracting(ServiceCalls.CallSite::callerMethod)
            .contains("detail", "viaParam", "viaLocal");
    }

    @Test
    @DisplayName("an implementation receiver is reported as such, not as an exact match")
    void implementationMatchKind() throws ToolFailure {
        ServiceCalls.Report report = callers("OrderService", "detail");

        assertThat(report.callSites())
            .filteredOn(site -> "local".equals(site.receiverExpression()))
            .singleElement()
            .satisfies(site -> {
                assertThat(site.receiverType()).isEqualTo("com.acme.kotlin.service.OrderServiceImpl");
                assertThat(site.matchKind()).isEqualTo(ServiceCalls.MatchKind.IMPLEMENTATION);
            });
    }

    @Test
    @DisplayName("a call the scanner cannot type is reported as unresolved, never as a wrong receiver")
    void unresolvedIsExplicit() throws ToolFailure {
        // `local.detail(id).id` is typed, but nothing here calls through a chain, so the unresolved
        // list stays empty rather than being filled with guesses.
        assertThat(callers("OrderService", null).unresolved()).isEmpty();
    }
}

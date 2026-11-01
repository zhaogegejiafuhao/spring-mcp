package io.github.springmcp.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.springmcp.spi.api.IndexHandle;
import io.github.springmcp.spi.api.IndexOptions;
import io.github.springmcp.spi.api.QueryScope;
import io.github.springmcp.spi.model.Annotations;
import io.github.springmcp.spi.model.Controllers;
import io.github.springmcp.spi.model.Mappers;
import io.github.springmcp.spi.model.ServiceCalls;

/**
 * Query scoping against a real two-module fixture.
 *
 * <p>
 * The fixture is deliberately built so that the interesting call crosses a module boundary:
 * {@code billing} calls {@code OrderService}, which lives in {@code ordering}. Scoping to
 * {@code billing} must still resolve that type — that is the property the whole "filter results, not
 * the index" design exists to preserve, and the one a naive implementation (walk-time pruning) would
 * silently break.
 * </p>
 */
class ModuleScopeTest {

    private static IndexRegistry registry;

    private static IndexHandle full;

    @BeforeAll
    static void index() throws Exception {
        registry = new IndexRegistry();
        full = registry.open(Fixtures.path("multi-module"), IndexOptions.defaults());
    }

    @Test
    @DisplayName("the fixture is recognised as two modules with one dependency edge")
    void exposesModules() {
        assertThat(full.modules()).containsExactly("billing", "ordering");
        assertThat(full.moduleDependencies()).containsEntry("billing", List.of("ordering"));
    }

    @Test
    @DisplayName("an unscoped query sees both modules")
    void unscopedSeesEverything() {
        Controllers.Report report = full.controllers(new Controllers.Query(false, null, null));

        assertThat(report.endpoints()).extracting(Controllers.Endpoint::path)
            .containsExactlyInAnyOrder("/orders", "/billing");
    }

    @Test
    @DisplayName("scoping narrows controllers to the named module")
    void scopesControllers() {
        IndexHandle billing = full.scoped(QueryScope.of(List.of("billing"), List.of()));

        Controllers.Report report = billing.controllers(new Controllers.Query(false, null, null));

        assertThat(report.endpoints()).extracting(Controllers.Endpoint::path).containsExactly("/billing");
        assertThat(billing.scope().describe()).isEqualTo("仅 billing");
    }

    @Test
    @DisplayName("a scoped caller search still resolves a service from another module")
    void scopeKeepsCrossModuleResolution() throws Exception {
        IndexHandle billing = full.scoped(QueryScope.of(List.of("billing"), List.of()));

        // OrderService is declared in `ordering`; the scope only restricts where callers live.
        ServiceCalls.Report report = billing.findServiceCallers(
                new ServiceCalls.Query("OrderService", null, true, true, false, 100));

        assertThat(report.targetFqn()).isEqualTo("com.acme.ordering.OrderService");
        assertThat(report.callSites()).hasSize(1);
        assertThat(report.callSites().get(0).callerType()).isEqualTo("com.acme.billing.BillingController");
    }

    @Test
    @DisplayName("scoping to the module that declares the service excludes the cross-module caller")
    void scopeExcludesOtherModules() throws Exception {
        IndexHandle ordering = full.scoped(QueryScope.of(List.of("ordering"), List.of()));

        ServiceCalls.Report report = ordering.findServiceCallers(
                new ServiceCalls.Query("OrderService", null, true, true, false, 100));

        // The constructor parameter is a field of OrderController, so a self call is excluded but the
        // controller's own use of the service is still reported; what must not appear is billing.
        assertThat(report.callSites()).allMatch(site -> !site.callerType().startsWith("com.acme.billing"));
    }

    @Test
    @DisplayName("scoping filters annotations and mappers through the same view")
    void scopesAnnotationsAndMappers() throws Exception {
        IndexHandle billing = full.scoped(QueryScope.of(List.of("billing"), List.of()));

        Annotations.Report controllers = billing.findByAnnotation(
                new Annotations.Query("RestController", java.util.Set.of(), false, 100));
        assertThat(controllers.hits()).extracting(Annotations.Hit::ownerType)
            .containsExactly("com.acme.billing.BillingController");

        assertThat(billing.mappers(new Mappers.Query(java.util.Set.of(), null)).mappers()).isEmpty();
    }

    @Test
    @DisplayName("a scope for a single-module project is ignored rather than emptying the answer")
    void scopeIgnoredWhenNoModules() throws Exception {
        IndexHandle demo = registry.open(Fixtures.path("demo-web"), IndexOptions.defaults());

        IndexHandle scoped = demo.scoped(QueryScope.of(List.of("nope"), List.of()));

        // Ignoring the scope keeps the answer useful; returning nothing would look like "no endpoints".
        assertThat(scoped).isSameAs(demo);
        assertThat(scoped.controllers(new Controllers.Query(false, null, null)).endpoints()).isNotEmpty();
    }

    @Test
    @DisplayName("scoping twice intersects instead of widening back to everything")
    void doubleScopeNarrows() {
        IndexHandle billing = full.scoped(QueryScope.of(List.of("billing"), List.of()));
        IndexHandle ordering = billing.scoped(QueryScope.of(List.of("ordering"), List.of()));

        assertThat(ordering.controllers(new Controllers.Query(false, null, null)).endpoints()).isEmpty();
    }

    @Test
    @DisplayName("file dependencies keep outgoing edges and filter only incoming ones")
    void scopesIncomingOnly() throws Exception {
        var serviceFile = Fixtures.path("multi-module").resolve("ordering/src/main/java/com/acme/ordering/OrderService.java");
        // OrderService lives in `ordering` and is used from `billing`, so scoping to `ordering`
        // removes the only inbound edge while leaving the outbound side untouched.
        IndexHandle ordering = full.scoped(QueryScope.of(List.of("ordering"), List.of()));

        var everything = full.fileDependencies(serviceFile, false);
        var scoped = ordering.fileDependencies(serviceFile, false);

        assertThat(scoped.outgoing()).hasSameSizeAs(everything.outgoing());
        assertThat(everything.incoming()).anyMatch(edge -> edge.fromFile().contains("billing"));
        // The remaining edges are inside the scope; the cross-module one is gone.
        assertThat(scoped.incoming()).isNotEmpty()
            .allMatch(edge -> edge.fromFile().contains("ordering"))
            .hasSizeLessThan(everything.incoming().size());
        assertThat(scoped.warnings()).anyMatch(w -> w.contains("作用域"));
    }

    @Test
    @DisplayName("an unknown module name produces an empty, explainable result rather than an error")
    void unknownModuleIsEmpty() {
        IndexHandle bogus = full.scoped(QueryScope.of(List.of("does-not-exist"), List.of()));

        Controllers.Report report = bogus.controllers(new Controllers.Query(false, null, null));

        assertThat(report.endpoints()).isEmpty();
        assertThat(bogus.scope().describe()).contains("does-not-exist");
    }
}

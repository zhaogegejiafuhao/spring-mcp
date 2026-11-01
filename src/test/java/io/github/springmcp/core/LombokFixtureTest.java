package io.github.springmcp.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.springmcp.core.index.SourceIndex;
import io.github.springmcp.spi.ToolFailure;
import io.github.springmcp.spi.model.Annotations;
import io.github.springmcp.spi.model.Controllers;
import io.github.springmcp.spi.model.ResolutionRule;
import io.github.springmcp.spi.model.ServiceCalls;

/**
 * Lombok does not have to be understood to be survived.
 *
 * <p>
 * Everything here is syntax that Lombok leaves alone: the annotations, the fields, the method bodies.
 * It generates constructors, getters and setters, none of which this analyzer reads. The one place
 * where that could have hurt is receiver resolution — a call-site analysis that worked from injected
 * constructors would see nothing in a {@code @RequiredArgsConstructor} class — so that case is
 * pinned here rather than assumed.
 * </p>
 *
 * <p>
 * Measured on {@code macrozheng/mall} (524 files, 21 {@code @Data}, 38 {@code @Getter}): 9 call sites
 * on {@code PmsBrandService}, all resolved as {@code FIELD}, 0 unresolved.
 * </p>
 */
class LombokFixtureTest {

    private static IndexRegistry registry;

    @BeforeAll
    static void setUp() {
        registry = new IndexRegistry(IndexRegistry.Revalidation.NEVER, 60_000);
    }

    private static SourceIndex index() throws ToolFailure {
        return (SourceIndex) registry.open(Fixtures.path("lombok-di"));
    }

    @Test
    @DisplayName("a @RequiredArgsConstructor controller still exposes its mappings")
    void lombokControllerIsVisible() throws ToolFailure {
        Controllers.Report report = index().controllers(new Controllers.Query(false, null, null));

        assertThat(report.endpoints()).singleElement().satisfies(endpoint -> {
            assertThat(endpoint.path()).isEqualTo("/lombok/orders");
            assertThat(endpoint.httpMethods()).containsExactly("POST");
        });
    }

    @Test
    @DisplayName("calls through a Lombok-injected final field are resolved")
    void callsThroughLombokFieldResolve() throws ToolFailure {
        // The constructor Lombok generates is nowhere in the source; the field is. Resolving from the
        // field is what makes this work, and it is also why nothing had to change for Lombok.
        ServiceCalls.Report report = index().findServiceCallers(new ServiceCalls.Query("OrderService", null,
                false, false, false, 100));

        assertThat(report.callSites()).singleElement().satisfies(site -> {
            assertThat(site.callerType()).isEqualTo("com.acme.lombok.OrderController");
            assertThat(site.rule()).isEqualTo(ResolutionRule.FIELD);
            assertThat(site.receiverExpression()).isEqualTo("orderService");
        });
        assertThat(report.unresolvedCount()).isZero();
    }

    @Test
    @DisplayName("Lombok annotations are reported like any other annotation")
    void lombokAnnotationsAreVisible() throws ToolFailure {
        Annotations.Report data = index().findByAnnotation(new Annotations.Query("Data", Set.of(), false, 100));
        Annotations.Report required = index()
            .findByAnnotation(new Annotations.Query("RequiredArgsConstructor", Set.of(), false, 100));

        assertThat(data.total()).isEqualTo(1);
        assertThat(data.hits()).extracting(Annotations.Hit::ownerType)
            .containsExactly("com.acme.lombok.OrderDto");
        assertThat(required.total()).isEqualTo(1);
        assertThat(required.hits()).extracting(Annotations.Hit::ownerType)
            .containsExactly("com.acme.lombok.OrderController");
    }
}

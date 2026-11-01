package io.github.springmcp.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.springmcp.core.index.MethodSummary;
import io.github.springmcp.core.index.SourceIndex;
import io.github.springmcp.core.index.TypeSummary;
import io.github.springmcp.spi.ToolFailure;
import io.github.springmcp.spi.model.Controllers;
import io.github.springmcp.spi.model.Dependencies;
import io.github.springmcp.spi.model.Mappers;
import io.github.springmcp.spi.model.Symbols;
import io.github.springmcp.spi.model.TypeHierarchy;

/**
 * A mixed Java/Kotlin project must give one answer, not two halves.
 *
 * <p>
 * The fixture pairs a Kotlin controller hierarchy with a Java implementation of the same Kotlin
 * interface, so every assertion here is really the same assertion: the index does not care which
 * language a declaration is written in, and neither does any tool built on it.
 * </p>
 */
class KotlinParsingTest {

    private static SourceIndex index;

    @BeforeAll
    static void setUp() throws ToolFailure {
        index = (SourceIndex) new IndexRegistry(IndexRegistry.Revalidation.NEVER, 60_000)
            .open(Fixtures.path("kotlin-web"));
    }

    @Test
    @DisplayName("Kotlin controllers, their class-level prefix and inherited handlers all land in the index")
    void controllers() throws Exception {
        Controllers.Report report = index.controllers(new Controllers.Query(false, null, null));

        assertThat(report.controllerCount()).isEqualTo(2);
        // ApiPaths.BASE is a `const val` in the same fixture, so its path is folded like a Java
        // constant — the tool has no language-specific path rules.
        assertThat(routes(report)).containsExactlyInAnyOrder("GET /orders/{id}", "POST /orders", "PUT /orders/bulk",
                "DELETE /orders/{id}", "GET /api/ping", "GET /api/pong", "GET /orders/api/summary");
    }

    @Test
    @DisplayName("a Kotlin path built from a constant is folded; an interpolation is reported, not invented")
    void unresolvedPathsAreFlagged() throws Exception {
        Controllers.Report report = index.controllers(new Controllers.Query(false, null, null));

        List<Controllers.Endpoint> unresolved = report.endpoints()
            .stream()
            .filter(endpoint -> !endpoint.pathResolved())
            .toList();

        assertThat(unresolved).hasSize(1);
        assertThat(unresolved).extracting(Controllers.Endpoint::unresolvedReason).containsExactly("PLACEHOLDER");
        assertThat(unresolved).extracting(Controllers.Endpoint::handlerMethod).containsExactly("echo");

        assertThat(report.endpoints())
            .filteredOn(endpoint -> "summary".equals(endpoint.handlerMethod()))
            .singleElement()
            .satisfies(endpoint -> {
                assertThat(endpoint.pathResolved()).isTrue();
                assertThat(endpoint.path()).isEqualTo("/orders/api/summary");
            });
    }

    @Test
    @DisplayName("a handler inherited from a Kotlin base class points at the declaring type")
    void inheritedHandlerKeepsItsOrigin() throws Exception {
        Controllers.Report report = index.controllers(new Controllers.Query(false, null, null));

        Controllers.Endpoint ping = report.endpoints()
            .stream()
            .filter(endpoint -> "/api/ping".equals(endpoint.path()))
            .findFirst()
            .orElseThrow();

        assertThat(ping.controllerFqn()).isEqualTo("com.acme.kotlin.PingController");
        assertThat(ping.handlerDeclaredIn()).isEqualTo("com.acme.kotlin.AbstractCrudController");
        assertThat(ping.file()).endsWith("AbstractCrudController.kt");
    }

    @Test
    @DisplayName("a Java class implementing a Kotlin interface is an implementation of it")
    void implementationsSpanBothLanguages() throws Exception {
        TypeHierarchy.Report report = index.implementations(new TypeHierarchy.Query("OrderService", false, false));

        assertThat(report.implementations()).extracting(TypeHierarchy.Implementation::simpleName)
            .containsExactlyInAnyOrder("OrderServiceImpl", "OrderAuditService");
        assertThat(report.implementations()).extracting(TypeHierarchy.Implementation::springStereotypes)
            .allSatisfy(stereotypes -> assertThat(stereotypes).contains("Service"));
    }

    @Test
    @DisplayName("a MyBatis-Plus mapper declared in Kotlin is found with its entity")
    void kotlinMapperIsFound() throws Exception {
        Mappers.Report report = index.mappers(new Mappers.Query(Set.of(), null));

        Mappers.Descriptor mapper = report.mappers()
            .stream()
            .filter(descriptor -> "com.acme.kotlin.mapper.OrderMapper".equals(descriptor.interfaceFqn()))
            .findFirst()
            .orElseThrow();

        assertThat(mapper.kind()).isEqualTo(Mappers.Kind.ANNOTATION);
        assertThat(mapper.entityType()).isEqualTo("Order");
        assertThat(mapper.declaredMethods()).contains("countByStatus");
    }

    @Test
    @DisplayName("a generic supertype is split at its own commas, not at the commas inside it")
    void genericArgumentsDoNotSplitDeclarations() throws Exception {
        Mappers.Report report = index.mappers(new Mappers.Query(Set.of(), null));

        Mappers.Descriptor repository = report.mappers()
            .stream()
            .filter(descriptor -> "com.acme.kotlin.repository.OrderRepository".equals(descriptor.interfaceFqn()))
            .findFirst()
            .orElseThrow();

        assertThat(repository.kind()).isEqualTo(Mappers.Kind.JPA);
        // `JpaRepository<Order, Long>` split on its comma yields a supertype named "Long>" and no
        // entity at all — the failure is silent unless it is asserted here.
        assertThat(repository.entityType()).isEqualTo("Order");
        assertThat(repository.idType()).isEqualTo("Long");
        assertThat(repository.declaredMethods()).containsExactly("findByStatus");
    }

    @Test
    @DisplayName("a parameter whose type contains commas stays one parameter")
    void genericParametersAreNotSplit() throws Exception {
        Symbols.Report report = index.searchSymbols(new Symbols.Query("OrderRepository", Set.of(), 5));
        assertThat(report.symbols()).extracting(Symbols.Symbol::typeFqn)
            .contains("com.acme.kotlin.repository.OrderRepository");

        TypeSummary repository = index.allTypes()
            .stream()
            .filter(type -> "OrderRepository".equals(type.simpleName()))
            .findFirst()
            .orElseThrow();
        MethodSummary method = repository.method("findByStatus");

        assertThat(method.parameters()).hasSize(1);
        assertThat(method.parameters().get(0).typeRaw()).isEqualTo("Map<String, List<String>>");
        assertThat(method.returnTypeRaw()).isEqualTo("List<Order>");
    }

    @Test
    @DisplayName("nested Kotlin declarations are indexed under their enclosing type")
    void nestedDeclarationsAreFlattened() throws Exception {
        Symbols.Report report = index.searchSymbols(new Symbols.Query("Status", Set.of(), 10));

        assertThat(report.symbols()).extracting(Symbols.Symbol::typeFqn)
            .contains("com.acme.kotlin.model.OrderView.Status");
    }

    @Test
    @DisplayName("a body-less Kotlin class does not swallow the class after it")
    void bodylessClassDoesNotEatTheNextOne() throws Exception {
        Symbols.Report report = index.searchSymbols(new Symbols.Query("OrderTotals", Set.of(), 10));

        Symbols.Symbol totals = report.symbols()
            .stream()
            .filter(symbol -> "com.acme.kotlin.model.OrderTotals".equals(symbol.typeFqn()))
            .findFirst()
            .orElseThrow();

        assertThat(totals.simpleName()).isEqualTo("OrderTotals");
        assertThat(index.searchSymbols(new Symbols.Query("OrderSummary", Set.of(), 10)).symbols())
            .extracting(Symbols.Symbol::typeFqn)
            .contains("com.acme.kotlin.model.OrderSummary");
    }

    @Test
    @DisplayName("file dependencies of a Kotlin file follow its imports")
    void dependenciesOfKotlinFile() throws ToolFailure {
        Dependencies.Report report = index.fileDependencies(
                Fixtures.file("kotlin-web", "src/main/kotlin/com/acme/kotlin/OrderController.kt"), false);

        assertThat(report.outgoing()).extracting(Dependencies.Edge::targetType)
            .contains("com.acme.kotlin.service.OrderService", "org.springframework.web.bind.annotation.RestController");
        assertThat(report.outgoing())
            .filteredOn(edge -> "com.acme.kotlin.service.OrderService".equals(edge.targetType()))
            .allSatisfy(edge -> assertThat(edge.internal()).isTrue());
    }

    @Test
    @DisplayName("the Kotlin warning states the coverage instead of claiming the other half is invisible")
    void warningDescribesCoverage() throws Exception {
        assertThat(index.metadata().warnings()).anyMatch(warning -> warning.contains("个 .kt 文件"))
            .anyMatch(warning -> warning.contains("链式调用"));
    }

    private static Set<String> routes(Controllers.Report report) {
        Set<String> routes = new LinkedHashSet<>();
        for (Controllers.Endpoint endpoint : report.endpoints()) {
            // Unresolved paths are asserted separately; here only the routes a reader can act on.
            if (endpoint.pathResolved()) {
                routes.add(endpoint.httpMethods().stream().collect(Collectors.joining(",")) + " " + endpoint.path());
            }
        }
        return routes;
    }
}

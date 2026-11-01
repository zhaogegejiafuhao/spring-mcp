package io.github.springmcp.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.springmcp.spi.api.IndexHandle;
import io.github.springmcp.spi.api.IndexOptions;
import io.github.springmcp.spi.model.Controllers;

/**
 * Endpoints inherited from base classes and interfaces.
 *
 * <p>
 * This exists because the analyser used to read only the declarations written on the controller
 * itself, so a {@code Controller extends AbstractCrudController<T, ID>} lost every inherited handler
 * — silently, with a normal-looking report. A missing endpoint is indistinguishable from an endpoint
 * that does not exist, which is the worst failure mode this tool can have.
 * </p>
 */
class InheritedMappingTest {

    private static IndexHandle index;

    @BeforeAll
    static void open() throws Exception {
        index = new IndexRegistry(IndexRegistry.Revalidation.NEVER, 60_000)
            .open(Fixtures.path("inherited-mappings"), IndexOptions.defaults());
    }

    @Test
    @DisplayName("a controller that declares no mappings still gets its parent's prefix")
    void prefixComesFromTwoLevelsUp() {
        Map<String, Controllers.Endpoint> admin = byPath();

        // AdminController -> AbstractAdminController("/admin") -> AbstractCrudController(no prefix)
        assertThat(admin.keySet()).filteredOn(path -> path.startsWith("/admin")).isNotEmpty();
        assertThat(admin).containsKeys("/admin", "/admin/{id}", "/admin/ping");
    }

    @Test
    @DisplayName("handlers declared in a base class are reported against the concrete controller")
    void inheritedHandlersBelongToTheSubclass() {
        List<Controllers.Endpoint> owners = endpointsOf("com.acme.owner.OwnerController");

        // Four handlers: one override plus three inherited. GET and DELETE share /owners/{id}.
        assertThat(owners).hasSize(4);
        assertThat(owners).extracting(Controllers.Endpoint::path)
            .containsExactlyInAnyOrder("/owners/search", "/owners", "/owners/{id}", "/owners/{id}");

        // The inherited ones point at the base class file, and say so.
        assertThat(owners).filteredOn(e -> e.handlerMethod().equals("get"))
            .singleElement()
            .satisfies(endpoint -> {
                assertThat(endpoint.handlerDeclaredIn()).isEqualTo("com.acme.crud.AbstractCrudController");
                assertThat(endpoint.file()).contains("AbstractCrudController.java");
            });
    }

    @Test
    @DisplayName("an override with its own mapping replaces the inherited one instead of duplicating it")
    void overrideWinsWithoutDuplicating() {
        List<Controllers.Endpoint> owners = endpointsOf("com.acme.owner.OwnerController");

        assertThat(owners).filteredOn(e -> e.handlerMethod().equals("list"))
            .singleElement()
            .satisfies(endpoint -> {
                assertThat(endpoint.path()).isEqualTo("/owners/search");
                assertThat(endpoint.handlerDeclaredIn()).isEqualTo("com.acme.owner.OwnerController");
            });
    }

    @Test
    @DisplayName("mappings declared on an interface are reported, with the interface as declarer")
    void mappingsFromInterface() {
        List<Controllers.Endpoint> pets = endpointsOf("com.acme.pet.PetController");

        assertThat(pets).extracting(Controllers.Endpoint::path)
            .containsExactlyInAnyOrder("/pet/{id}", "/pet/count");
        assertThat(pets).filteredOn(e -> e.path().equals("/pet/{id}"))
            .singleElement()
            .satisfies(endpoint -> assertThat(endpoint.handlerDeclaredIn()).isEqualTo("com.acme.pet.PetApi"));
    }

    @Test
    @DisplayName("the report says how many handlers were inherited, so the file/line is not a surprise")
    void inheritsAreCountedInWarnings() {
        Controllers.Report report = index.controllers(new Controllers.Query(false, null, null));

        assertThat(report.warnings()).anyMatch(w -> w.contains("继承自父类或接口"));
    }

    @Test
    @DisplayName("an abstract base class is never reported as a controller of its own")
    void baseClassesAreNotControllers() {
        Controllers.Report report = index.controllers(new Controllers.Query(false, null, null));

        assertThat(report.endpoints()).extracting(Controllers.Endpoint::controllerFqn)
            .doesNotContain("com.acme.crud.AbstractCrudController", "com.acme.crud.AbstractAdminController");
        assertThat(report.controllerCount()).isEqualTo(3);
    }

    private static List<Controllers.Endpoint> endpointsOf(String controllerFqn) {
        return index.controllers(new Controllers.Query(false, null, null))
            .endpoints()
            .stream()
            .filter(e -> e.controllerFqn().equals(controllerFqn))
            .toList();
    }

    private static Map<String, Controllers.Endpoint> byPath() {
        return index.controllers(new Controllers.Query(false, null, null))
            .endpoints()
            .stream()
            .collect(Collectors.toMap(Controllers.Endpoint::path, Function.identity(), (a, b) -> a));
    }
}

package io.github.springmcp.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.springmcp.spi.ToolFailure;
import io.github.springmcp.spi.api.IndexHandle;
import io.github.springmcp.spi.api.IndexOptions;
import io.github.springmcp.spi.model.TypeHierarchy;

/**
 * Subtype lookup, including the cases grep gets wrong.
 *
 * <p>
 * The fixture is built around three traps: an implementation whose class name has nothing to do with
 * the interface name, an implementation that only reaches the interface through an abstract base, and
 * a sub-interface. A text search for {@code implements PaymentGateway} finds one of the three.
 * </p>
 */
class TypeHierarchyTest {

    private static IndexHandle index;

    @BeforeAll
    static void open() throws Exception {
        index = new IndexRegistry(IndexRegistry.Revalidation.NEVER, 60_000)
            .open(Fixtures.path("type-hierarchy"), IndexOptions.defaults());
    }

    @Test
    @DisplayName("concrete implementations are found even when the class name does not match")
    void findsByTypeNotByName() throws Exception {
        TypeHierarchy.Report report = index.implementations(query("PaymentGateway"));

        assertThat(report.targetFqn()).isEqualTo("com.acme.pay.PaymentGateway");
        assertThat(report.implementations()).extracting(TypeHierarchy.Implementation::simpleName)
            .containsExactly("CardGateway", "CashGateway");
    }

    @Test
    @DisplayName("an implementation through an abstract base is reported with direct=false")
    void marksIndirectImplementations() throws Exception {
        TypeHierarchy.Report report = index.implementations(query("PaymentGateway"));

        assertThat(report.implementations()).filteredOn(i -> i.simpleName().equals("CardGateway"))
            .singleElement()
            .satisfies(implementation -> {
                assertThat(implementation.direct()).isFalse();
                assertThat(implementation.springStereotypes()).containsExactly("Service");
            });
        assertThat(report.implementations()).filteredOn(i -> i.simpleName().equals("CashGateway"))
            .singleElement()
            .satisfies(implementation -> {
                assertThat(implementation.direct()).isTrue();
                assertThat(implementation.springStereotypes()).containsExactly("Component");
            });
    }

    @Test
    @DisplayName("abstract bases are skipped by default and mentioned in the warnings")
    void abstractBasesAreOptIn() throws Exception {
        TypeHierarchy.Report report = index.implementations(query("PaymentGateway"));

        assertThat(report.implementations()).extracting(TypeHierarchy.Implementation::simpleName)
            .doesNotContain("AbstractGateway");
        assertThat(report.warnings()).anyMatch(w -> w.contains("抽象类"));

        TypeHierarchy.Report withBases = index.implementations(
                new TypeHierarchy.Query("PaymentGateway", true, false));
        assertThat(withBases.implementations()).extracting(TypeHierarchy.Implementation::simpleName)
            .contains("AbstractGateway");
        assertThat(withBases.implementations()).filteredOn(i -> i.simpleName().equals("AbstractGateway"))
            .singleElement()
            .satisfies(base -> assertThat(base.abstractType()).isTrue());
    }

    @Test
    @DisplayName("sub-interfaces are opt-in too")
    void subInterfacesAreOptIn() throws Exception {
        TypeHierarchy.Report report = index.implementations(query("PaymentGateway"));
        assertThat(report.warnings()).anyMatch(w -> w.contains("子接口"));

        TypeHierarchy.Report withInterfaces = index.implementations(
                new TypeHierarchy.Query("PaymentGateway", false, true));
        assertThat(withInterfaces.implementations()).extracting(TypeHierarchy.Implementation::simpleName)
            .contains("AuditableGateway");
    }

    @Test
    @DisplayName("a type with no subtypes says so instead of returning a bare empty list")
    void noImplementationsIsExplained() throws Exception {
        TypeHierarchy.Report report = index.implementations(query("CashGateway"));

        assertThat(report.implementations()).isEmpty();
        assertThat(report.count()).isZero();
        assertThat(report.warnings()).anyMatch(w -> w.contains("没有找到"));
    }

    @Test
    @DisplayName("an unknown or ambiguous type fails with the usual error codes")
    void resolvesTheTargetOrFails() {
        assertThatThrownBy(() -> index.implementations(query("NoSuchType")))
            .isInstanceOf(ToolFailure.class)
            .satisfies(failure -> assertThat(((ToolFailure) failure).code().name()).isEqualTo("CLASS_NOT_FOUND"));
    }

    @Test
    @DisplayName("a fully qualified name resolves the same way as a simple name")
    void acceptsFqn() throws Exception {
        TypeHierarchy.Report bySimple = index.implementations(query("PaymentGateway"));
        TypeHierarchy.Report byFqn = index.implementations(query("com.acme.pay.PaymentGateway"));

        assertThat(byFqn.implementations()).isEqualTo(bySimple.implementations());
    }

    @Test
    @DisplayName("results carry file and line so a caller can jump straight there")
    void carriesLocations() throws Exception {
        TypeHierarchy.Report report = index.implementations(query("PaymentGateway"));

        assertThat(report.implementations()).allSatisfy(implementation -> {
            assertThat(implementation.file()).endsWith(".java");
            assertThat(implementation.line()).isPositive();
            assertThat(implementation.kind()).isEqualTo("CLASS");
        });
    }

    private static TypeHierarchy.Query query(String type) {
        return new TypeHierarchy.Query(type, false, false);
    }
}

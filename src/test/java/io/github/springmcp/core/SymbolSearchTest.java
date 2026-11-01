package io.github.springmcp.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.springmcp.spi.api.IndexHandle;
import io.github.springmcp.spi.api.IndexOptions;
import io.github.springmcp.spi.api.QueryScope;
import io.github.springmcp.spi.model.Symbols;

/**
 * Type lookup by name.
 *
 * <p>
 * The interesting property is the ranking, not the matching: several declarations contain "Controller",
 * and the one the caller asked for has to be first — otherwise this tool is just a slower grep.
 * </p>
 */
class SymbolSearchTest {

    private static IndexHandle index;

    @BeforeAll
    static void open() throws Exception {
        index = new IndexRegistry(IndexRegistry.Revalidation.NEVER, 60_000)
            .open(Fixtures.path("demo-web"), IndexOptions.defaults());
    }

    @Test
    @DisplayName("an exact name ranks above everything that merely contains it")
    void exactMatchComesFirst() throws Exception {
        Symbols.Report report = index.searchSymbols(new Symbols.Query("OrderController", Set.of(), 50));

        assertThat(report.symbols()).isNotEmpty();
        assertThat(report.symbols().get(0).simpleName()).isEqualTo("OrderController");
        assertThat(report.symbols().get(0).typeFqn()).isEqualTo("com.acme.web.OrderController");
    }

    @Test
    @DisplayName("a fragment returns every declaration containing it")
    void fragmentMatchesSeveral() throws Exception {
        Symbols.Report report = index.searchSymbols(new Symbols.Query("controller", Set.of(), 50));

        assertThat(report.symbols()).extracting(Symbols.Symbol::simpleName)
            .contains("OrderController", "UserController", "AdminController", "PlaceholderController");
        assertThat(report.total()).isGreaterThanOrEqualTo(4);
        assertThat(report.truncated()).isFalse();
    }

    @Test
    @DisplayName("matching is case-insensitive and also covers the package")
    void caseInsensitiveAndPackageAware() throws Exception {
        Symbols.Report byLower = index.searchSymbols(new Symbols.Query("ordercontroller", Set.of(), 50));
        assertThat(byLower.symbols()).extracting(Symbols.Symbol::simpleName).contains("OrderController");

        Symbols.Report byPackage = index.searchSymbols(new Symbols.Query("com.acme.web", Set.of(), 50));
        assertThat(byPackage.symbols()).hasSizeGreaterThanOrEqualTo(4);
    }

    @Test
    @DisplayName("the kind filter narrows without changing the ranking")
    void kindFilter() throws Exception {
        Symbols.Report interfaces = index.searchSymbols(new Symbols.Query("Order", Set.of("INTERFACE"), 50));

        assertThat(interfaces.symbols()).isNotEmpty();
        assertThat(interfaces.symbols()).allSatisfy(symbol -> assertThat(symbol.kind()).isEqualTo("INTERFACE"));
        assertThat(interfaces.symbols()).extracting(Symbols.Symbol::simpleName).contains("OrderService");
    }

    @Test
    @DisplayName("hitting the limit is reported, not silently applied")
    void truncationIsReported() throws Exception {
        Symbols.Report report = index.searchSymbols(new Symbols.Query("e", Set.of(), 2));

        assertThat(report.symbols()).hasSize(2);
        assertThat(report.truncated()).isTrue();
        assertThat(report.total()).isGreaterThan(2);
        assertThat(report.warnings()).anyMatch(w -> w.contains("只返回前 2 个"));
    }

    @Test
    @DisplayName("no match says so instead of returning a bare empty list")
    void noMatchIsExplained() throws Exception {
        Symbols.Report report = index.searchSymbols(new Symbols.Query("NoSuchThing", Set.of(), 50));

        assertThat(report.symbols()).isEmpty();
        assertThat(report.warnings()).anyMatch(w -> w.contains("没有名字包含"));
    }

    @Test
    @DisplayName("symbols carry annotations and locations, which is what a caller needs next")
    void carriesAnnotationsAndLocations() throws Exception {
        Symbols.Report report = index.searchSymbols(new Symbols.Query("OrderController", Set.of(), 1));

        assertThat(report.symbols()).singleElement().satisfies(symbol -> {
            assertThat(symbol.annotations()).contains("RestController");
            assertThat(symbol.file()).endsWith("OrderController.java");
            assertThat(symbol.line()).isPositive();
            assertThat(symbol.packageName()).isEqualTo("com.acme.web");
        });
    }

    @Test
    @DisplayName("scope filters symbols by module")
    void scopeApplies(@org.junit.jupiter.api.io.TempDir java.nio.file.Path unused) throws Exception {
        IndexHandle multi = new IndexRegistry(IndexRegistry.Revalidation.NEVER, 60_000)
            .open(Fixtures.path("multi-module"), IndexOptions.defaults());

        Symbols.Report all = multi.searchSymbols(new Symbols.Query("Controller", Set.of(), 50));
        Symbols.Report billing = multi.scoped(QueryScope.of(List.of("billing"), List.of()))
            .searchSymbols(new Symbols.Query("Controller", Set.of(), 50));

        assertThat(all.symbols()).hasSize(2);
        assertThat(billing.symbols()).extracting(Symbols.Symbol::simpleName)
            .containsExactly("BillingController");
    }

    @Test
    @DisplayName("a blank query is rejected instead of matching everything")
    void blankQueryIsRejected() {
        assertThatThrownBy(() -> index.searchSymbols(new Symbols.Query("  ", Set.of(), 10)))
            .hasMessageContaining("缺少类型名");
    }
}

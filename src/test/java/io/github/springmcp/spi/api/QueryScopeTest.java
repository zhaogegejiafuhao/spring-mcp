package io.github.springmcp.spi.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Scope matching rules.
 *
 * <p>
 * The rules look trivial and are not: whether {@code services} covers {@code services/order}, and
 * whether a file outside every module is kept, decide if a scoped answer is empty for a reason the
 * caller can understand.
 * </p>
 */
class QueryScopeTest {

    @Test
    @DisplayName("unrestricted keeps everything, including files outside any module")
    void unrestrictedKeepsEverything() {
        QueryScope scope = QueryScope.unrestricted();

        assertThat(scope.isUnrestricted()).isTrue();
        assertThat(scope.includes("services/order")).isTrue();
        assertThat(scope.includes("")).isTrue();
    }

    @Test
    @DisplayName("an include list matches the module itself and everything below it")
    void includeMatchesPrefix() {
        QueryScope scope = QueryScope.of(List.of("services"), List.of());

        assertThat(scope.includes("services")).isTrue();
        assertThat(scope.includes("services/order")).isTrue();
        assertThat(scope.includes("services/payment")).isTrue();
        assertThat(scope.includes("web")).isFalse();
        // A shared prefix that is not a path segment must not match.
        assertThat(scope.includes("services-common")).isFalse();
    }

    @Test
    @DisplayName("files outside every module are excluded by an include list unless named")
    void rootFilesNeedExplicitNaming() {
        assertThat(QueryScope.of(List.of("services"), List.of()).includes("")).isFalse();
        assertThat(QueryScope.of(List.of("."), List.of()).includes("")).isTrue();
        assertThat(QueryScope.of(List.of("*"), List.of()).includes("")).isTrue();
    }

    @Test
    @DisplayName("exclude wins over include")
    void excludeWins() {
        QueryScope scope = QueryScope.of(List.of("services"), List.of("services/legacy"));

        assertThat(scope.includes("services/order")).isTrue();
        assertThat(scope.includes("services/legacy")).isFalse();
        assertThat(scope.includes("services/legacy/old")).isFalse();
    }

    @Test
    @DisplayName("intersecting two scopes narrows instead of widening")
    void intersectionNarrows() {
        QueryScope left = QueryScope.of(List.of("services"), List.of());
        QueryScope right = QueryScope.of(List.of("services/order"), List.of());

        QueryScope both = left.and(right);

        assertThat(both.includes("services/order")).isTrue();
        assertThat(both.includes("services/payment")).isFalse();
    }

    @Test
    @DisplayName("intersecting with an unrestricted scope changes nothing")
    void intersectionWithUnrestricted() {
        QueryScope scope = QueryScope.of(List.of("services"), List.of());

        assertThat(scope.and(QueryScope.unrestricted())).isSameAs(scope);
        assertThat(QueryScope.unrestricted().and(scope)).isSameAs(scope);
    }

    @Test
    @DisplayName("blank and duplicate entries are dropped, separators are normalised")
    void normalisesInput() {
        QueryScope scope = QueryScope.of(List.of(" services/ ", "", "services", ".\\web"), List.of());

        assertThat(scope.includeModules()).containsExactly("services", "web");
    }

    @Test
    @DisplayName("describe names the modules, and is empty when nothing is scoped")
    void describes() {
        assertThat(QueryScope.unrestricted().describe()).isEmpty();
        assertThat(QueryScope.of(List.of("a"), List.of("b")).describe()).isEqualTo("仅 a；排除 b");
    }
}

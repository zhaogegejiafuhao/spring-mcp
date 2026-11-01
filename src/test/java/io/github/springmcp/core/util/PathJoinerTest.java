package io.github.springmcp.core.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Path composition is small but easy to get subtly wrong, and a wrong URL is exactly the kind of
 * confident-but-false answer this server must not produce.
 */
class PathJoinerTest {

    @Test
    @DisplayName("empty fragments collapse to the root")
    void emptyFragments() {
        assertThat(PathJoiner.join(null, null)).isEqualTo("/");
        assertThat(PathJoiner.join("", "")).isEqualTo("/");
        assertThat(PathJoiner.join("/api", "")).isEqualTo("/api");
        assertThat(PathJoiner.join("", "/x")).isEqualTo("/x");
    }

    @Test
    @DisplayName("a missing leading slash on the method path is added")
    void addsLeadingSlash() {
        assertThat(PathJoiner.join("/api", "orders")).isEqualTo("/api/orders");
    }

    @Test
    @DisplayName("duplicate slashes are collapsed")
    void collapsesDuplicates() {
        assertThat(PathJoiner.join("/api/", "/orders")).isEqualTo("/api/orders");
        assertThat(PathJoiner.join("//api//", "//orders//")).isEqualTo("/api/orders");
    }

    @Test
    @DisplayName("a root prefix stays the root instead of producing a double slash")
    void rootSegments() {
        // The pair everyone writes: @RequestMapping("/") on the class, @GetMapping("/test") on the
        // method. Spring resolves it to /test; joining naively produced //test, a URL that 404s.
        assertThat(PathJoiner.join("/", "/test")).isEqualTo("/test");
        assertThat(PathJoiner.join("/", "/")).isEqualTo("/");
        assertThat(PathJoiner.join("/api", "/")).isEqualTo("/api");
        assertThat(PathJoiner.combinations(List.of("/"), List.of("/test", "/list")))
            .containsExactly("/test", "/list");
    }

    @Test
    @DisplayName("a backslash in a user-supplied filter is tolerated")
    void normalisesBackslashes() {
        assertThat(PathJoiner.cleanFilter("\\api\\orders")).isEqualTo("/api/orders");
    }

    @Test
    @DisplayName("a backslash inside a regex path constraint is kept")
    void keepsRegexConstraint() {
        // @GetMapping("/detail/{id:\\d+}") is a real URL pattern; rewriting the backslash turned it
        // into {id:/d+}, which matches nothing the application serves.
        assertThat(PathJoiner.join("/article", "/detail/{id:\\d+}")).isEqualTo("/article/detail/{id:\\d+}");
        assertThat(PathJoiner.clean("/detail/{id:\\d+}")).isEqualTo("/detail/{id:\\d+}");
    }

    @Test
    @DisplayName("every prefix/path pair is combined exactly once")
    void combinations() {
        List<String> joined = PathJoiner.combinations(List.of("/u", "/users"), List.of("/list"));
        assertThat(joined).containsExactly("/u/list", "/users/list");

        List<String> deduped = PathJoiner.combinations(List.of("/a", "/a/"), List.of("/b"));
        assertThat(deduped).containsExactly("/a/b");
    }

    @Test
    @DisplayName("unresolved fragments are concatenated without pretending to be a URL")
    void rawConcat() {
        assertThat(PathJoiner.rawConcat("ApiConst.BASE", "/ping")).isEqualTo("ApiConst.BASE/ping");
        assertThat(PathJoiner.rawConcat("", "/ping")).isEqualTo("/ping");
        assertThat(PathJoiner.rawConcat("ApiConst.BASE", "")).isEqualTo("ApiConst.BASE");
        assertThat(PathJoiner.rawConcat("", "")).isEqualTo("/");
    }
}

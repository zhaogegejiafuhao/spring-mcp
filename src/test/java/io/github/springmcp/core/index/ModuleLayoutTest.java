package io.github.springmcp.core.index;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.springmcp.core.Fixtures;

/**
 * Module detection, on the fixture projects and on throwaway trees.
 *
 * <p>
 * The interesting cases are the ones that make detection <em>wrong</em>: a single-module project that
 * must not gain a scope, a Gradle settings file containing a wildcard, a module nested inside another
 * module. Each of those turned into a real bug during development, so each one is a test.
 * </p>
 */
class ModuleLayoutTest {

    @Test
    @DisplayName("Maven <modules> is read and nested module paths stay relative to the root")
    void detectsMavenModules() {
        ModuleLayout layout = ModuleLayout.detect(Fixtures.path("multi-module"));

        assertThat(layout.modules()).containsExactly("billing", "ordering");
        assertThat(layout.isEmpty()).isFalse();
    }

    @Test
    @DisplayName("a declared dependency becomes a module edge, resolved through artifact ids")
    void readsMavenDependencies() {
        ModuleLayout layout = ModuleLayout.detect(Fixtures.path("multi-module"));

        assertThat(layout.dependencies()).containsEntry("billing", List.of("ordering"));
        assertThat(layout.withDependencies(List.of("billing"))).containsExactlyInAnyOrder("billing", "ordering");
    }

    @Test
    @DisplayName("a single-module project reports no modules, so no scope is offered")
    void singleModuleProjectHasNoModules() {
        // demo-web has src/main/java at its root: a perfectly normal single-module layout, and the one
        // where inventing a module called "" would break every scoped query.
        assertThat(ModuleLayout.detect(Fixtures.path("demo-web")).modules()).isEmpty();
        assertThat(ModuleLayout.detect(Fixtures.path("layered-callers")).isEmpty()).isTrue();
    }

    @Test
    @DisplayName("moduleOf picks the deepest matching module")
    void moduleOfUsesLongestPrefix(@TempDir Path root) throws Exception {
        // outer is a module, and outer/inner is a module inside it.
        write(root, "pom.xml", """
                <project><modules><module>outer</module></modules></project>
                """);
        write(root, "outer/pom.xml", """
                <project><artifactId>outer</artifactId><modules><module>inner</module></modules></project>
                """);
        write(root, "outer/inner/pom.xml", "<project><artifactId>inner</artifactId></project>");
        write(root, "outer/src/main/java/a/A.java", "package a; class A {}");
        write(root, "outer/inner/src/main/java/b/B.java", "package b; class B {}");

        ModuleLayout layout = ModuleLayout.detect(root);

        assertThat(layout.modules()).containsExactly("outer", "outer/inner");
        assertThat(layout.moduleOf(root.resolve("outer/src/main/java/a/A.java"))).isEqualTo("outer");
        assertThat(layout.moduleOf(root.resolve("outer/inner/src/main/java/b/B.java"))).isEqualTo("outer/inner");
        assertThat(layout.moduleOf(root.resolve("README.md"))).isEmpty();
    }

    @Test
    @DisplayName("a Gradle settings file containing a wildcard does not break detection")
    void ignoresGradleGlobs(@TempDir Path root) throws Exception {
        // A commented-out glob is common in real settings.gradle files, and Path.of("**/*.gradle")
        // throws InvalidPathException on Windows — which used to abort the whole index build.
        write(root, "settings.gradle", """
                rootProject.name = 'acme'
                // include '**/*.gradle'
                include ':core', ':web'
                """);
        write(root, "core/src/main/java/a/A.java", "package a; class A {}");
        write(root, "web/src/main/java/b/B.java", "package b; class B {}");

        ModuleLayout layout = ModuleLayout.detect(root);

        assertThat(layout.modules()).containsExactly("core", "web");
    }

    @Test
    @DisplayName("Gradle project dependencies are mapped to module paths")
    void readsGradleDependencies(@TempDir Path root) throws Exception {
        write(root, "settings.gradle", "include ':core', ':web'");
        write(root, "core/src/main/java/a/A.java", "package a; class A {}");
        write(root, "web/build.gradle", "dependencies { implementation project(':core') }");
        write(root, "web/src/main/java/b/B.java", "package b; class B {}");

        ModuleLayout layout = ModuleLayout.detect(root);

        assertThat(layout.dependencies()).containsEntry("web", List.of("core"));
        assertThat(layout.withDependencies(List.of("web"))).containsExactlyInAnyOrder("web", "core");
    }

    @Test
    @DisplayName("without build files, directories holding src/main/java are the modules")
    void fallsBackToPathConvention(@TempDir Path root) throws Exception {
        write(root, "svc-a/src/main/java/a/A.java", "package a; class A {}");
        write(root, "svc-b/src/main/java/b/B.java", "package b; class B {}");

        ModuleLayout layout = ModuleLayout.detect(root);

        assertThat(layout.modules()).containsExactly("svc-a", "svc-b");
        // No build files means no edges; the caller must not be told a dependency exists.
        assertThat(layout.dependencies()).isEmpty();
    }

    @Test
    @DisplayName("withDependencies follows edges transitively without looping")
    void withDependenciesIsTransitive() {
        ModuleLayout layout = ModuleLayout.detect(Fixtures.path("multi-module"));

        Set<String> expanded = layout.withDependencies(List.of("billing"));

        assertThat(expanded).containsExactlyInAnyOrder("billing", "ordering");
        // Ordering has no outgoing edges, so asking for it must not pull billing back in.
        assertThat(layout.withDependencies(List.of("ordering"))).containsExactly("ordering");
    }

    @Test
    @DisplayName("resolveName accepts both a relative module path and an absolute directory")
    void resolvesBothNameForms() {
        Path root = Fixtures.path("multi-module");
        ModuleLayout layout = ModuleLayout.detect(root);

        assertThat(layout.resolveName("billing")).isEqualTo("billing");
        assertThat(layout.resolveName("./billing/")).isEqualTo("billing");
        assertThat(layout.resolveName(root.resolve("billing").toString())).isEqualTo("billing");
        assertThat(layout.resolveName("nope")).isNull();
    }

    private static void write(Path root, String relative, String content) throws Exception {
        Path file = root.resolve(relative);
        java.nio.file.Files.createDirectories(file.getParent());
        java.nio.file.Files.writeString(file, content);
    }
}

package io.github.springmcp.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.springmcp.spi.api.IndexHandle;
import io.github.springmcp.spi.api.IndexOptions;
import io.github.springmcp.spi.model.Controllers;

/**
 * Kotlin sources are indexed, and the coverage is stated rather than assumed.
 *
 * <p>
 * Before Kotlin support, spring-boot's 438 {@code .kt} files were invisible and "158 endpoints" read
 * as a fact rather than as half a fact. Now they are parsed, and the warning that remains describes
 * the two things still not covered — expression-level type inference and call-site location — so a
 * reader knows exactly how far the answer goes.
 * </p>
 */
class KotlinPresenceTest {

    @Test
    @DisplayName("a Kotlin controller in the same project as Java ones is indexed beside them")
    void kotlinControllerIsIndexed(@TempDir Path root) throws Exception {
        write(root, "src/main/java/a/A.java", """
                package a;

                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.RestController;

                @RestController
                public class A {

                    @GetMapping("/a")
                    public String a() {
                        return "a";
                    }
                }
                """);
        write(root, "src/main/kotlin/b/B.kt", """
                package b

                import org.springframework.web.bind.annotation.GetMapping
                import org.springframework.web.bind.annotation.RestController

                @RestController
                class B {
                    @GetMapping("/b")
                    fun b(): String = "b"
                }
                """);

        IndexHandle index = new IndexRegistry(IndexRegistry.Revalidation.NEVER, 60_000)
            .open(root, IndexOptions.defaults());

        assertThat(index.metadata().fileCount()).as("both languages are sources").isEqualTo(2);
        assertThat(index.controllers(new Controllers.Query(false, null, null)).endpoints())
            .extracting(Controllers.Endpoint::path)
            .containsExactlyInAnyOrder("/a", "/b");
    }

    @Test
    @DisplayName("the warning names the documented limits, not a missing feature")
    void warningDescribesLimits(@TempDir Path root) throws Exception {
        write(root, "src/main/kotlin/b/B.kt", """
                package b

                class B
                """);

        IndexHandle index = new IndexRegistry(IndexRegistry.Revalidation.NEVER, 60_000)
            .open(root, IndexOptions.defaults());

        assertThat(index.metadata().warnings()).anyMatch(warning -> warning.contains("1 个 .kt 文件"))
            .anyMatch(warning -> warning.contains("链式调用"));
    }

    @Test
    @DisplayName("a Java-only project gets no Kotlin warning")
    void javaOnlyProjectHasNoWarning() throws Exception {
        IndexHandle index = new IndexRegistry(IndexRegistry.Revalidation.NEVER, 60_000)
            .open(Fixtures.path("demo-web"), IndexOptions.defaults());

        assertThat(index.metadata().warnings()).noneMatch(w -> w.contains(".kt"));
    }

    @Test
    @DisplayName("build.gradle.kts is not counted as a source file")
    void gradleScriptsAreNotCounted(@TempDir Path root) throws Exception {
        write(root, "src/main/java/a/A.java", "package a; class A {}");
        write(root, "build.gradle.kts", "plugins { java }");

        IndexHandle index = new IndexRegistry(IndexRegistry.Revalidation.NEVER, 60_000)
            .open(root, IndexOptions.defaults());

        // A build script is not application code; counting it would overstate the project's size.
        assertThat(index.metadata().warnings()).noneMatch(w -> w.contains(".kt"));
    }

    private static void write(Path root, String relative, String content) throws Exception {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}

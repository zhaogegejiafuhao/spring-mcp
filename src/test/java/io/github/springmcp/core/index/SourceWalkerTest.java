package io.github.springmcp.core.index;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.springmcp.spi.api.IndexOptions;

/** Exclusion rules decide whether the answers are about the project or about its build output. */
class SourceWalkerTest {

    private final SourceWalker walker = new SourceWalker();

    private static void write(Path root, String relative, String content) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    @Test
    @DisplayName("build output, VCS metadata and dependency folders are pruned")
    void prunesNoiseDirectories(@TempDir Path root) throws Exception {
        write(root, "src/main/java/a/A.java", "package a; class A {}");
        write(root, "target/classes/a/A.java", "package a; class A {}");
        write(root, "build/generated/B.java", "package b; class B {}");
        write(root, "node_modules/x/C.java", "package c; class C {}");
        write(root, ".git/hooks/D.java", "package d; class D {}");
        write(root, "out/E.java", "package e; class E {}");

        SourceWalker.WalkResult result = walker.walk(root, IndexOptions.defaults());

        assertThat(result.files()).extracting(f -> root.relativize(f.path()).toString().replace('\\', '/'))
            .containsExactly("src/main/java/a/A.java");
    }

    @Test
    @DisplayName("test fixture directories are not application sources")
    void excludesTestFixtures(@TempDir Path root) throws Exception {
        // Parsers and IDE plugins ship deliberately broken samples under testdata/. Indexing them
        // contributed 12 "parse failure" entries and 7 Feign clients that exist nowhere in the
        // application (explyt/spring-plugin in the regression corpus).
        write(root, "src/main/java/a/A.java", "package a; class A {}");
        write(root, "src/test/resources/testdata/Broken.java", "package t; class {");
        write(root, "modules/x/testdata/D.java", "package d; class D {}");
        write(root, "src/testFixtures/java/e/E.java", "package e; class E {}");

        SourceWalker.WalkResult result = walker.walk(root, IndexOptions.defaults().withIncludeTests(true));

        assertThat(result.files()).extracting(f -> root.relativize(f.path()).toString().replace('\\', '/'))
            .containsExactly("src/main/java/a/A.java");
    }

    @Test
    @DisplayName("test sources are excluded unless asked for")
    void excludesTestSources(@TempDir Path root) throws Exception {
        write(root, "src/main/java/a/A.java", "package a; class A {}");
        write(root, "src/test/java/a/ATest.java", "package a; class ATest {}");

        assertThat(walker.walk(root, IndexOptions.defaults()).files()).hasSize(1);
        assertThat(walker.walk(root, IndexOptions.defaults().withIncludeTests(true)).files()).hasSize(2);
    }

    @Test
    @DisplayName("test sources of every module are excluded, not just the root one")
    void excludesNestedTestSources(@TempDir Path root) throws Exception {
        // The shape of every multi-module Maven project. Checking only <root>/src/test indexed
        // 114 of 115 files in mybatis-spring-boot while just 46 are main sources, and test
        // controllers and mappers leaked into answers about the application.
        write(root, "src/main/java/a/A.java", "package a; class A {}");
        write(root, "src/test/java/a/ATest.java", "package a; class ATest {}");
        write(root, "order-service/src/main/java/b/B.java", "package b; class B {}");
        write(root, "order-service/src/test/java/b/BTest.java", "package b; class BTest {}");
        write(root, "order-service/src/test/kotlin/b/K.kt", "class K");

        var result = walker.walk(root, IndexOptions.defaults());

        // Compared as Paths: they are separator-agnostic, unlike string replacement.
        assertThat(result.files()).extracting(fp -> root.relativize(fp.path()))
            .containsExactlyInAnyOrder(Path.of("src/main/java/a/A.java"),
                    Path.of("order-service/src/main/java/b/B.java"));
        assertThat(result.kotlin().files()).as("test Kotlin is not counted either").isZero();
    }

    @Test
    @DisplayName("oversized files are skipped with a warning instead of failing")
    void skipsOversizedFiles(@TempDir Path root) throws Exception {
        write(root, "src/main/java/a/A.java", "class A{}");
        write(root, "src/main/java/a/Big.java", "class Big{" + " ".repeat(200) + "}");

        IndexOptions small = new IndexOptions(false, false, List.of(), 30, 100, java.time.Duration.ofSeconds(10));
        SourceWalker.WalkResult result = walker.walk(root, small);

        assertThat(result.files()).hasSize(1);
        assertThat(result.skippedLarge()).isEqualTo(1);
        assertThat(result.warnings()).anyMatch(w -> w.contains("超大文件"));
    }

    @Test
    @DisplayName("the file cap is enforced while walking, not after")
    void enforcesFileCap(@TempDir Path root) throws Exception {
        for (int i = 0; i < 5; i++) {
            write(root, "src/main/java/a/A" + i + ".java", "package a; class A" + i + " {}");
        }
        IndexOptions capped = new IndexOptions(false, false, List.of(), 1_000_000, 2, java.time.Duration.ofSeconds(10));

        try {
            walker.walk(root, capped);
            org.assertj.core.api.Assertions.fail("expected TooManyFilesException");
        }
        catch (SourceWalker.TooManyFilesException e) {
            // SourceIndex.Builder maps this to ToolFailure(INDEX_TOO_LARGE); here we only assert the
            // walk stopped early rather than collecting everything first.
            assertThat(e.getMessage()).contains("超过上限 2");
        }
    }

    @Test
    @DisplayName("extra excludes and .mcpignore both work")
    void mcpIgnore(@TempDir Path root) throws Exception {
        write(root, "src/main/java/a/A.java", "package a; class A {}");
        write(root, "src/main/java/legacy/Old.java", "package legacy; class Old {}");
        write(root, "src/main/java/tmp/Scratch.java", "package tmp; class Scratch {}");
        Files.writeString(root.resolve(".mcpignore"), """
                # generated scratch space
                tmp/
                *.skip
                """);

        SourceWalker.WalkResult result = walker.walk(root,
                IndexOptions.defaults().withExtraExcludes(List.of("legacy")));

        assertThat(result.files()).extracting(f -> f.path().getFileName().toString()).containsExactly("A.java");
    }

    @Test
    @DisplayName("results are sorted so repeated runs are byte-identical")
    void deterministicOrdering(@TempDir Path root) throws Exception {
        write(root, "src/main/java/z/Z.java", "package z; class Z {}");
        write(root, "src/main/java/a/A.java", "package a; class A {}");
        write(root, "src/main/java/m/M.java", "package m; class M {}");

        List<String> first = walker.walk(root, IndexOptions.defaults())
            .files()
            .stream()
            .map(f -> f.path().toString())
            .toList();
        List<String> second = walker.walk(root, IndexOptions.defaults())
            .files()
            .stream()
            .map(f -> f.path().toString())
            .toList();

        assertThat(first).isEqualTo(second).isSorted();
    }
}

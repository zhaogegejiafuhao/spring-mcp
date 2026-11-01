package io.github.springmcp.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.springmcp.spi.ErrorCode;
import io.github.springmcp.spi.ToolFailure;
import io.github.springmcp.spi.api.IndexHandle;
import io.github.springmcp.spi.api.IndexOptions;

/**
 * Concurrency and cache invalidation. These are the properties that decide whether the server is
 * usable under an agent that fires several tool calls at once, so they get explicit tests rather
 * than being assumed.
 */
class IndexRegistryTest {

    private static void copyTree(Path from, Path to) throws IOException {
        try (Stream<Path> stream = Files.walk(from)) {
            for (Path source : (Iterable<Path>) stream::iterator) {
                Path target = to.resolve(from.relativize(source).toString());
                if (Files.isDirectory(source)) {
                    Files.createDirectories(target);
                }
                else {
                    Files.createDirectories(target.getParent());
                    Files.copy(source, target);
                }
            }
        }
    }

    @Test
    @DisplayName("twenty concurrent opens build the index exactly once")
    void concurrentOpensBuildOnce() throws Exception {
        Path root = Fixtures.path("demo-web");
        IndexRegistry registry = new IndexRegistry(IndexRegistry.Revalidation.NEVER, 60_000);

        ExecutorService pool = Executors.newFixedThreadPool(20);
        try {
            List<Callable<IndexHandle>> tasks = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                tasks.add(() -> registry.open(root, IndexOptions.defaults()));
            }
            List<Future<IndexHandle>> futures = pool.invokeAll(tasks, 30, TimeUnit.SECONDS);

            int ok = 0;
            for (Future<IndexHandle> f : futures) {
                IndexHandle handle = f.get();
                assertThat(handle.metadata().fileCount()).isEqualTo(6);
                ok++;
            }
            assertThat(ok).isEqualTo(20);
        }
        finally {
            pool.shutdownNow();
        }

        assertThat(registry.buildCount(root)).isEqualTo(1);
    }

    @Test
    @DisplayName("invalidateAll forces a rebuild with a new generation")
    void invalidateForcesRebuild() throws Exception {
        Path root = Fixtures.path("demo-web");
        IndexRegistry registry = new IndexRegistry(IndexRegistry.Revalidation.NEVER, 60_000);

        IndexHandle first = registry.open(root);

        registry.invalidateAll();
        IndexHandle second = registry.open(root);

        assertThat(second.metadata().generation()).isEqualTo(first.metadata().generation() + 1);
        assertThat(registry.buildCount(root)).isEqualTo(2);
    }

    @Test
    @DisplayName("a stat probe notices an added file after the throttle window")
    void statProbeDetectsNewFile(@TempDir Path temp) throws Exception {
        Path root = temp.resolve("project");
        Files.createDirectories(root);
        copyTree(Fixtures.path("demo-web"), root);

        // STAT mode with the default 2s probe throttle, so this is the only slow test.
        IndexRegistry registry = new IndexRegistry(IndexRegistry.Revalidation.STAT, 60_000);
        IndexHandle before = registry.open(root);
        int beforeCount = before.metadata().fileCount();

        Files.createDirectories(root.resolve("src/main/java/com/acme/web"));
        Files.writeString(root.resolve("src/main/java/com/acme/web/Extra.java"),
                "package com.acme.web;\npublic class Extra {\n}\n");

        Thread.sleep(2_200);

        IndexHandle after = registry.open(root);
        assertThat(after.metadata().fileCount()).isEqualTo(beforeCount + 1);
        assertThat(registry.buildCount(root)).isEqualTo(2);
    }

    @Test
    @DisplayName("changing options produces a new snapshot")
    void optionsChangeRebuilds() throws Exception {
        Path root = Fixtures.path("demo-web");
        IndexRegistry registry = new IndexRegistry(IndexRegistry.Revalidation.NEVER, 60_000);

        registry.open(root, IndexOptions.defaults());
        registry.open(root, IndexOptions.defaults().withIncludeTests(true));

        assertThat(registry.buildCount(root)).isEqualTo(2);
    }

    @Test
    @DisplayName("a missing directory maps to PATH_NOT_FOUND with a hint")
    void missingDirectory() {
        IndexRegistry registry = new IndexRegistry(IndexRegistry.Revalidation.NEVER, 60_000);
        try {
            registry.open(Path.of("definitely", "not", "here"));
            fail("expected ToolFailure");
        }
        catch (ToolFailure f) {
            assertThat(f.code()).isEqualTo(ErrorCode.PATH_NOT_FOUND);
            assertThat(f.hint()).isNotBlank();
        }
    }

    @Test
    @DisplayName("a regular file is rejected as a project root")
    void fileIsNotADirectory() {
        IndexRegistry registry = new IndexRegistry(IndexRegistry.Revalidation.NEVER, 60_000);
        Path file = Fixtures.file("demo-web", "src/main/java/com/acme/web/OrderService.java");
        try {
            registry.open(file);
            fail("expected ToolFailure");
        }
        catch (ToolFailure f) {
            assertThat(f.code()).isEqualTo(ErrorCode.PATH_NOT_DIRECTORY);
        }
    }

    @Test
    @DisplayName("readSource returns the file text and fails cleanly for a missing path")
    void readSource() throws Exception {
        IndexRegistry registry = new IndexRegistry(IndexRegistry.Revalidation.NEVER, 60_000);
        Path file = Fixtures.file("demo-web", "src/main/java/com/acme/web/OrderService.java");

        assertThat(registry.readSource(file)).contains("interface OrderService");

        try {
            registry.readSource(file.getParent().resolve("Nope.java"));
            fail("expected ToolFailure");
        }
        catch (ToolFailure f) {
            assertThat(f.code()).isEqualTo(ErrorCode.PATH_NOT_FOUND);
        }
    }
}

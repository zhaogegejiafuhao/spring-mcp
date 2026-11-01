package io.github.springmcp.core.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.springmcp.core.Fixtures;
import io.github.springmcp.core.IndexRegistry;
import io.github.springmcp.spi.api.IndexHandle;
import io.github.springmcp.spi.api.IndexOptions;
import io.github.springmcp.spi.model.Controllers;
import io.github.springmcp.spi.model.Mappers;

/**
 * Reuse of unchanged file summaries during a rebuild.
 *
 * <p>
 * The property is narrow and load-bearing: after one file changes, the others must not be parsed
 * again, and the resulting snapshot must be indistinguishable from a full build. Both halves matter —
 * a fast rebuild that answers differently would be worse than no incremental path at all.
 * </p>
 *
 * <p>
 * Lives in the cache package because rewriting the envelope (to simulate a foreign build) needs the
 * cache's own mapper, which is deliberately not public.
 * </p>
 */
class IncrementalIndexTest {

    private static final IndexOptions OPTIONS = IndexOptions.defaults();

    /** Longer than the probe throttle, so an edit is guaranteed to be noticed. */
    private static final long THROTTLE_WAIT_MILLIS = 1_200;

    @Test
    @DisplayName("editing one file re-parses only that file, in the same process")
    void rebuildReusesUnchangedFiles(@TempDir Path projectDir) throws Exception {
        Path project = copyFixture("multi-module", projectDir);
        IndexRegistry registry = new IndexRegistry(IndexRegistry.Revalidation.STAT, 60_000);
        IndexHandle first = registry.open(project, OPTIONS);
        int total = first.metadata().fileCount();
        assertThat(total).as("fixture must have more than one file").isGreaterThan(1);
        assertThat(registry.reusedFileCount(project)).isZero();

        Thread.sleep(THROTTLE_WAIT_MILLIS);
        Path controller = project.resolve("billing/src/main/java/com/acme/billing/BillingController.java");
        Files.writeString(controller, Files.readString(controller).replace("\"/billing\"", "\"/billing/v2\""));

        IndexHandle second = registry.open(project, OPTIONS);

        assertThat(registry.buildCount(project)).as("the rebuild must actually happen").isEqualTo(2);
        assertThat(registry.reusedFileCount(project)).as("everything but the edited file is reused")
            .isEqualTo(total - 1);
        assertThat(second.controllers(new Controllers.Query(false, null, null)).endpoints())
            .extracting(Controllers.Endpoint::path)
            .contains("/billing/v2");
    }

    @Test
    @DisplayName("an incremental rebuild answers exactly like a full build")
    void incrementalResultMatchesFullBuild(@TempDir Path projectDir) throws Exception {
        Path project = copyFixture("layered-callers", projectDir);
        IndexRegistry incremental = new IndexRegistry(IndexRegistry.Revalidation.STAT, 60_000);
        incremental.open(project, OPTIONS);

        Thread.sleep(THROTTLE_WAIT_MILLIS);
        Path service = project.resolve("src/main/java/com/acme/order/OrderService.java");
        Files.writeString(service, Files.readString(service) + "\n// touched\n");
        IndexHandle afterEdit = incremental.open(project, OPTIONS);

        // Same sources, nothing to reuse: a brand new registry parses every file.
        IndexRegistry fresh = new IndexRegistry(IndexRegistry.Revalidation.NEVER, 60_000);
        IndexHandle full = fresh.open(project, OPTIONS);

        assertThat(incremental.reusedFileCount(project)).as("some summaries must have been reused").isPositive();
        assertThat(afterEdit.metadata().fileCount()).isEqualTo(full.metadata().fileCount());
        assertThat(afterEdit.metadata().typeCount()).isEqualTo(full.metadata().typeCount());
        assertThat(afterEdit.controllers(new Controllers.Query(false, null, null)))
            .isEqualTo(full.controllers(new Controllers.Query(false, null, null)));
        assertThat(afterEdit.mappers(new Mappers.Query(Set.of(), null)))
            .isEqualTo(full.mappers(new Mappers.Query(Set.of(), null)));
    }

    @Test
    @DisplayName("a new process reuses the disk cache even though its digest no longer matches")
    void crossProcessReusesStaleCache(@TempDir Path cacheDir, @TempDir Path projectDir) throws Exception {
        Path project = copyFixture("multi-module", projectDir);
        IndexCacheStore store = IndexCacheStore.at(cacheDir);
        int total = new IndexRegistry(IndexRegistry.Revalidation.NEVER, 60_000, store)
            .open(project, OPTIONS)
            .metadata()
            .fileCount();

        Path controller = project.resolve("ordering/src/main/java/com/acme/ordering/OrderController.java");
        Files.writeString(controller, Files.readString(controller).replace("\"/orders\"", "\"/orders/v2\""));

        IndexRegistry second = new IndexRegistry(IndexRegistry.Revalidation.NEVER, 60_000, store);
        IndexHandle handle = second.open(project, OPTIONS);

        assertThat(second.buildCount(project)).isEqualTo(1);
        assertThat(second.reusedFileCount(project)).as("only the edited file is parsed again").isEqualTo(total - 1);
        assertThat(handle.controllers(new Controllers.Query(false, null, null)).endpoints())
            .extracting(Controllers.Endpoint::path)
            .contains("/orders/v2");
    }

    @Test
    @DisplayName("adding a file reuses the existing ones instead of re-parsing everything")
    void newFileIsParsedAlone(@TempDir Path cacheDir, @TempDir Path projectDir) throws Exception {
        Path project = copyFixture("multi-module", projectDir);
        IndexCacheStore store = IndexCacheStore.at(cacheDir);
        int total = new IndexRegistry(IndexRegistry.Revalidation.NEVER, 60_000, store)
            .open(project, OPTIONS)
            .metadata()
            .fileCount();

        Files.writeString(project.resolve("billing/src/main/java/com/acme/billing/ExtraController.java"), """
                package com.acme.billing;

                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.RestController;

                @RestController
                public class ExtraController {

                    @GetMapping("/extra")
                    public String extra() {
                        return "extra";
                    }
                }
                """);

        IndexRegistry second = new IndexRegistry(IndexRegistry.Revalidation.NEVER, 60_000, store);
        IndexHandle handle = second.open(project, OPTIONS);

        // The cached file count no longer matches, but the per-file payload is still valid.
        assertThat(second.reusedFileCount(project)).isEqualTo(total);
        assertThat(handle.metadata().fileCount()).isEqualTo(total + 1);
        assertThat(handle.controllers(new Controllers.Query(false, null, null)).endpoints())
            .extracting(Controllers.Endpoint::path)
            .contains("/extra");
    }

    @Test
    @DisplayName("a summary written by a different build is never reused")
    void foreignBuildStampDisablesReuse(@TempDir Path cacheDir, @TempDir Path projectDir) throws Exception {
        Path project = copyFixture("multi-module", projectDir);
        IndexCacheStore store = IndexCacheStore.at(cacheDir);
        new IndexRegistry(IndexRegistry.Revalidation.NEVER, 60_000, store).open(project, OPTIONS);

        var mapper = CacheMapper.create();
        Path cacheFile = store.fileFor(project);
        CacheEnvelope envelope = mapper.readValue(cacheFile.toFile(), CacheEnvelope.class);
        mapper.writeValue(cacheFile.toFile(), new CacheEnvelope(envelope.format(), "other-jar@1970-01-01T00:00:00Z",
                envelope.root(), envelope.optionsKey(), envelope.digest(), envelope.fileCount(),
                envelope.builtAtEpochMillis(), envelope.problems(), envelope.warnings(), envelope.summaries()));

        IndexRegistry second = new IndexRegistry(IndexRegistry.Revalidation.NEVER, 60_000, store);
        second.open(project, OPTIONS);

        assertThat(second.reusedFileCount(project))
            .as("summaries produced by another analyser are not interchangeable")
            .isZero();
    }

    private static Path copyFixture(String name, Path target) throws IOException {
        Path source = Fixtures.path(name);
        try (Stream<Path> stream = Files.walk(source)) {
            for (Path path : stream.toList()) {
                Path destination = target.resolve(source.relativize(path).toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination);
                }
                else {
                    Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        return target;
    }
}

package io.github.springmcp.core.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import io.github.springmcp.core.Fixtures;
import io.github.springmcp.core.IndexRegistry;
import io.github.springmcp.core.index.FileFingerprint;
import io.github.springmcp.spi.api.IndexHandle;
import io.github.springmcp.spi.api.IndexOptions;
import io.github.springmcp.spi.model.Controllers;

/**
 * Behaviour of the on-disk index cache.
 *
 * <p>
 * Both halves of the contract are tested: a hit must produce exactly the answers of a build, and
 * every kind of staleness must produce a miss instead of a stale answer. The second half is the one
 * that matters — a cache returning yesterday's index for today's source is worse than no cache,
 * because nothing downstream can tell it apart from the truth.
 * </p>
 */
class IndexCacheStoreTest {

    private static final IndexOptions OPTIONS = IndexOptions.defaults();

    @Test
    @DisplayName("a second process answers from the cache without parsing anything")
    void secondRegistryReadsFromCache(@TempDir Path cacheDir, @TempDir Path projectDir) throws Exception {
        Path project = copyFixture("multi-module", projectDir);

        IndexRegistry first = new IndexRegistry(IndexRegistry.Revalidation.NEVER, 60_000,
                IndexCacheStore.at(cacheDir));
        IndexHandle built = first.open(project, OPTIONS);
        assertThat(first.buildCount(project)).isEqualTo(1);
        // Never trust the write silently: a failed save shows up here as "why", not as an unrelated
        // FileNotFoundException three lines later.
        IndexCacheStore store = store(cacheDir);
        assertThat(store.fileFor(project))
            .describedAs("缓存未写入：%s", store.lastFailure())
            .exists();

        IndexRegistry second = new IndexRegistry(IndexRegistry.Revalidation.NEVER, 60_000,
                IndexCacheStore.at(cacheDir));
        IndexHandle restored = second.open(project, OPTIONS);

        // buildCount counts real builds only, so 0 here proves the summaries came from disk.
        assertThat(second.buildCount(project)).isZero();
        assertThat(restored.metadata().fileCount()).isEqualTo(built.metadata().fileCount());
        assertThat(restored.metadata().typeCount()).isEqualTo(built.metadata().typeCount());
        assertThat(restored.modules()).isEqualTo(built.modules());
        assertThat(paths(restored)).containsExactlyInAnyOrderElementsOf(paths(built));
    }

    @Test
    @DisplayName("editing a source file invalidates the cache")
    void changedFileInvalidates(@TempDir Path cacheDir, @TempDir Path projectDir) throws Exception {
        Path project = copyFixture("multi-module", projectDir);
        new IndexRegistry(IndexRegistry.Revalidation.NEVER, 60_000, store(cacheDir)).open(project, OPTIONS);

        Path controller = project.resolve("billing/src/main/java/com/acme/billing/BillingController.java");
        Files.writeString(controller, Files.readString(controller).replace("\"/billing\"", "\"/billing/v2\""));

        IndexRegistry second = new IndexRegistry(IndexRegistry.Revalidation.NEVER, 60_000, store(cacheDir));
        IndexHandle reopened = second.open(project, OPTIONS);

        assertThat(second.buildCount(project)).as("a changed file must force a real build").isEqualTo(1);
        assertThat(paths(reopened)).contains("/billing/v2");
    }

    @Test
    @DisplayName("a corrupt cache file is a miss, never an error")
    void corruptFileIsIgnored(@TempDir Path cacheDir, @TempDir Path projectDir) throws Exception {
        Path project = copyFixture("multi-module", projectDir);
        IndexCacheStore store = store(cacheDir);
        new IndexRegistry(IndexRegistry.Revalidation.NEVER, 60_000, store).open(project, OPTIONS);

        Files.writeString(store.fileFor(project), "{ this is not json");

        IndexRegistry second = new IndexRegistry(IndexRegistry.Revalidation.NEVER, 60_000, store);
        IndexHandle handle = second.open(project, OPTIONS);

        assertThat(second.buildCount(project)).isEqualTo(1);
        assertThat(handle.controllers(new Controllers.Query(false, null, null)).endpoints()).hasSize(2);
    }

    @Test
    @DisplayName("a cache from another format version is a miss")
    void foreignFormatVersionIsIgnored(@TempDir Path cacheDir, @TempDir Path projectDir) throws Exception {
        Path project = copyFixture("multi-module", projectDir);
        IndexCacheStore store = store(cacheDir);
        new IndexRegistry(IndexRegistry.Revalidation.NEVER, 60_000, store).open(project, OPTIONS);

        CacheEnvelope envelope = CacheMapper.create().readValue(store.fileFor(project).toFile(),
                CacheEnvelope.class);
        CacheEnvelope newer = new CacheEnvelope(IndexCacheFormat.VERSION + 1, envelope.codeStamp(),
                envelope.root(), envelope.optionsKey(), envelope.digest(), envelope.fileCount(),
                envelope.builtAtEpochMillis(), envelope.problems(), envelope.warnings(), envelope.summaries());
        CacheMapper.create().writeValue(store.fileFor(project).toFile(), newer);

        IndexRegistry second = new IndexRegistry(IndexRegistry.Revalidation.NEVER, 60_000, store);
        second.open(project, OPTIONS);

        assertThat(second.buildCount(project)).isEqualTo(1);
    }

    @Test
    @DisplayName("a cache written with different options is a miss")
    void differentOptionsAreIgnored(@TempDir Path cacheDir, @TempDir Path projectDir) throws Exception {
        Path project = copyFixture("multi-module", projectDir);
        IndexCacheStore store = store(cacheDir);
        new IndexRegistry(IndexRegistry.Revalidation.NEVER, 60_000, store).open(project, OPTIONS);

        IndexRegistry second = new IndexRegistry(IndexRegistry.Revalidation.NEVER, 60_000, store);
        second.open(project, OPTIONS.withIncludeTests(true));

        assertThat(second.buildCount(project)).as("test sources change what gets indexed").isEqualTo(1);
    }

    @Test
    @DisplayName("a disabled store writes nothing and always reports a miss")
    void disabledStoreDoesNothing(@TempDir Path projectDir) throws Exception {
        Path project = copyFixture("multi-module", projectDir);
        IndexCacheStore store = IndexCacheStore.disabled();

        assertThat(store.enabled()).isFalse();
        assertThat(store.load(project, OPTIONS, List.of()).hit()).isFalse();
        assertThat(store.load(project, OPTIONS, List.of()).reason()).isEqualTo("cache disabled");
    }

    @Test
    @DisplayName("recorded problems and warnings survive a round trip")
    void warningsSurviveRoundTrip(@TempDir Path cacheDir, @TempDir Path projectDir) throws Exception {
        Path project = copyFixture("broken-syntax", projectDir);
        IndexCacheStore store = store(cacheDir);

        IndexRegistry first = new IndexRegistry(IndexRegistry.Revalidation.NEVER, 60_000, store);
        IndexHandle built = first.open(project, OPTIONS);
        assertThat(built.metadata().warnings()).as("the fixture must produce a warning worth preserving")
            .isNotEmpty();

        IndexRegistry second = new IndexRegistry(IndexRegistry.Revalidation.NEVER, 60_000, store);
        IndexHandle restored = second.open(project, OPTIONS);

        assertThat(second.buildCount(project)).isZero();
        // A cache that dropped the "files failed to parse" warning would make a partial answer look
        // complete, which is exactly what the warning exists to prevent.
        assertThat(restored.metadata().warnings()).isEqualTo(built.metadata().warnings());
        assertThat(restored.metadata().problems()).isEqualTo(built.metadata().problems());
    }

    @Test
    @DisplayName("the options key ignores the build timeout")
    void optionsKeyIgnoresTimeout() {
        IndexOptions slow = IndexOptions.defaults();
        IndexOptions fast = new IndexOptions(false, false, List.of(), 1024L * 1024L, 20_000,
                Duration.ofSeconds(5));

        assertThat(IndexCacheStore.optionsKey(slow)).isEqualTo(IndexCacheStore.optionsKey(fast));
    }

    @Test
    @DisplayName("cache file names are stable per root and distinct between roots")
    void cacheFilesAreKeyedByRoot(@TempDir Path cacheDir) {
        IndexCacheStore store = store(cacheDir);

        Path a = cacheDir.resolve("a");
        Path b = cacheDir.resolve("b");

        assertThat(store.fileFor(a)).isEqualTo(store.fileFor(a.toAbsolutePath()));
        assertThat(store.fileFor(a)).isNotEqualTo(store.fileFor(b));
        assertThat(store.fileFor(a).getFileName().toString()).isEqualTo(IndexCacheFormat.FILE_NAME);
    }

    @Test
    @DisplayName("a stale entry can be removed explicitly")
    void invalidateDeletesTheEntry(@TempDir Path cacheDir, @TempDir Path projectDir) throws Exception {
        Path project = copyFixture("multi-module", projectDir);
        IndexCacheStore store = store(cacheDir);
        new IndexRegistry(IndexRegistry.Revalidation.NEVER, 60_000, store).open(project, OPTIONS);

        assertThat(store.fileFor(project))
            .describedAs("缓存未写入：%s", store.lastFailure())
            .exists();
        store.invalidate(project);
        assertThat(store.fileFor(project)).doesNotExist();
    }

    @Test
    @DisplayName("the cache lives under the user profile, never inside a project")
    void defaultDirectoryIsUserScoped() {
        Path directory = IndexCacheStore.defaultDirectory().toAbsolutePath();

        assertThat(directory.isAbsolute()).isTrue();
        assertThat(directory.toString()).contains("spring-mcp");
        assertThat(directory).isNotEqualTo(Fixtures.path("multi-module"));
    }

    @Test
    @DisplayName("the digest distinguishes order and content of fingerprints")
    void digestIsOrderSensitiveButStable(@TempDir Path dir) {
        FileFingerprint one = new FileFingerprint(dir.resolve("A.java"), 10, 100);
        FileFingerprint two = new FileFingerprint(dir.resolve("B.java"), 10, 100);

        assertThat(IndexCacheStore.digest(List.of(one, two))).isEqualTo(IndexCacheStore.digest(List.of(one, two)));
        // Order matters because the walker sorts by path; a different order means a different set.
        assertThat(IndexCacheStore.digest(List.of(one, two)))
            .isNotEqualTo(IndexCacheStore.digest(List.of(two, one)));
        assertThat(IndexCacheStore.digest(List.of(one, new FileFingerprint(dir.resolve("B.java"), 11, 100))))
            .isNotEqualTo(IndexCacheStore.digest(List.of(one, two)));
    }

    @Test
    @DisplayName("a project with no Java sources still caches, without looping")
    void emptyProjectStillCaches(@TempDir Path cacheDir, @TempDir Path projectDir) throws Exception {
        Path project = copyFixture("empty", projectDir);
        IndexCacheStore store = store(cacheDir);

        IndexRegistry first = new IndexRegistry(IndexRegistry.Revalidation.NEVER, 60_000, store);
        assertThat(first.open(project, OPTIONS).metadata().fileCount()).isZero();

        IndexRegistry second = new IndexRegistry(IndexRegistry.Revalidation.NEVER, 60_000, store);
        second.open(project, OPTIONS);

        assertThat(second.buildCount(project)).isZero();
    }

    private static IndexCacheStore store(Path cacheDir) {
        return IndexCacheStore.at(cacheDir);
    }

    private static List<String> paths(IndexHandle handle) {
        return handle.controllers(new Controllers.Query(false, null, null))
            .endpoints()
            .stream()
            .map(Controllers.Endpoint::path)
            .toList();
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

    @Test
    @EnabledOnOs(OS.WINDOWS)
    @DisplayName("the same directory in a different spelling is one cache entry")
    void aliasedRootIsOneEntry(@TempDir Path cacheDir, @TempDir Path projectDir) throws Exception {
        // CI runners hand out short names (RUNNER~1). Hashing the textual path put the write and the
        // read under different keys: the cache was written, never found, and no error was raised
        // anywhere — five unrelated-looking IO failures. Case is the alias every Windows box has.
        IndexCacheStore store = store(cacheDir);
        Path project = copyFixture("multi-module", projectDir);
        Path shouted = Path.of(project.toString().toUpperCase(java.util.Locale.ROOT));

        assertThat(store.fileFor(shouted)).isEqualTo(store.fileFor(project));

        new IndexRegistry(IndexRegistry.Revalidation.NEVER, 60_000, store).open(shouted, OPTIONS);
        assertThat(store.fileFor(project))
            .describedAs("换个大小写就找不到自己的缓存：%s", store.lastFailure())
            .exists();
    }
}

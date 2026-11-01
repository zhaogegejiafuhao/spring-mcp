package io.github.springmcp.baseline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.abort;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.springmcp.core.IndexRegistry;
import io.github.springmcp.server.dev.BaselineRunner;
import io.github.springmcp.server.dev.BaselineSpec;

/**
 * Compares current analysis output against the frozen golden files.
 *
 * <p>
 * Gated three ways on purpose: {@code @Tag("baseline")} so it can be selected explicitly,
 * {@code SPRING_MCP_BASELINE=true} because the first run clones four repositories (hundreds of
 * megabytes, minutes of wall clock) and no ordinary test run should pay that, and a git availability
 * check because the whole thing depends on checking out a pinned commit.
 * </p>
 *
 * <p>
 * Failures are collected across all repositories and tools rather than thrown on the first
 * difference: when a refactor changes something fundamental, seeing "all five tools on three
 * repositories moved" in one report is far more diagnostic than one line at a time.
 * </p>
 */
@Tag("baseline")
@EnabledIfEnvironmentVariable(named = "SPRING_MCP_BASELINE", matches = "true")
class BaselineTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Resolves a classpath resource to a real path.
     * @param resource absolute classpath location, e.g. {@code /fixtures/baseline/manifest.json}
     * @return the file on disk
     */
    private static Path resource(String resource) {
        var url = BaselineTest.class.getResource(resource);
        assertThat(url).as("classpath 资源缺失: %s（检查 testResources 是否复制了 fixtures/）", resource).isNotNull();
        try {
            return Path.of(url.toURI());
        }
        catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean gitAvailable() {
        try {
            Process process = new ProcessBuilder("git", "--version").redirectErrorStream(true).start();
            process.getInputStream().readAllBytes();
            return process.waitFor(20, TimeUnit.SECONDS) && process.exitValue() == 0;
        }
        catch (IOException e) {
            return false;
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @Test
    @DisplayName("所有 manifest 仓库的分析输出与冻结的 digest 一致")
    void matchesFrozenBaseline() throws Exception {
        if (!gitAvailable()) {
            abort("git 不可用，跳过基线比对");
        }

        List<BaselineSpec> specs = BaselineSpec.load(resource("/fixtures/baseline/manifest.json"));
        assertThat(specs).as("manifest 至少要有一个仓库").isNotEmpty();

        Path work = Path.of(".baseline").toAbsolutePath();
        IndexRegistry registry = new IndexRegistry(IndexRegistry.Revalidation.NEVER, 60_000);
        BaselineRunner runner = new BaselineRunner(registry, false);

        List<String> differences = new ArrayList<>();
        for (BaselineSpec spec : specs) {
            Path goldenFile = resource("/fixtures/baseline/golden/" + spec.name() + ".json");
            Path repo = spec.ensure(work);

            var snapshots = runner.run(spec, repo);
            JsonNode golden = MAPPER.readTree(Files.readString(goldenFile, StandardCharsets.UTF_8));

            if (!spec.commit().equals(golden.path("commit").asText())) {
                differences.add(spec.name() + ": golden 的 commit 与 manifest 不一致，请重新生成 golden");
                continue;
            }
            for (String difference : BaselineRunner.compare(golden, snapshots)) {
                differences.add(spec.name() + " / " + difference);
            }
        }

        assertThat(differences)
            .as("分析结果与基线不一致。确认是有意的改动后，用 --dev baseline --repo=<name> --regenerate 更新 golden")
            .isEmpty();
    }

    @Test
    @DisplayName("每个 manifest 仓库都有对应的 golden 文件")
    void everyRepoHasGolden() throws Exception {
        List<BaselineSpec> specs = BaselineSpec.load(resource("/fixtures/baseline/manifest.json"));
        List<String> missing = new ArrayList<>();
        for (BaselineSpec spec : specs) {
            if (BaselineTest.class.getResource("/fixtures/baseline/golden/" + spec.name() + ".json") == null) {
                missing.add(spec.name());
            }
        }
        assertThat(missing).as("缺少 golden，运行 --dev baseline --repo=<name> --regenerate").isEmpty();
    }
}

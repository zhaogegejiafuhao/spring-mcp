package io.github.springmcp.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.springmcp.core.Fixtures;
import io.github.springmcp.core.IndexRegistry;
import io.github.springmcp.server.dev.DevCli;

/**
 * The offline CLI and the MCP tool surface must agree on what an empty directory means.
 *
 * <p>
 * They did not. The guard lived in {@code builtin}, package-private, and only the tools called it;
 * {@code --dev controllers --root <empty>} answered {@code "endpoints": []} with exit code 0. That is
 * the worse half of the split to be wrong about: the CLI is what you reach for to decide whether the
 * analysis or the client is at fault, so a silently empty answer there costs a debugging session.
 * </p>
 *
 * <p>
 * {@code index-info} is deliberately exempt — metadata is the diagnosis you want when the answer is
 * "no source files", not an error to be refused.
 * </p>
 */
class DevCliTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * @param exitCode process exit code the CLI would have returned
     * @param body parsed JSON the CLI printed, {@code null} when it printed nothing
     */
    private record Result(int exitCode, JsonNode body) {
    }

    private static Result runDev(String... args) throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(buffer, true, StandardCharsets.UTF_8);
        DevCli cli = new DevCli(new IndexRegistry(IndexRegistry.Revalidation.NEVER, 60_000), out);

        int exitCode = cli.run(List.of(args));

        String printed = buffer.toString(StandardCharsets.UTF_8);
        return new Result(exitCode, printed.isBlank() ? null : MAPPER.readTree(printed));
    }

    @Test
    @DisplayName("--dev refuses a directory with no source, with the same code and hint as the tools")
    void analysisOnEmptyDirectoryFails() throws Exception {
        Result result = runDev("controllers", "--root", Fixtures.path("empty").toString());

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.body().get("ok").asBoolean()).isFalse();
        assertThat(result.body().get("code").asText()).isEqualTo("INVALID_ARGUMENT");
        assertThat(result.body().get("message").asText()).contains("没有 Java/Kotlin 源码");
        assertThat(result.body().get("hint").asText()).contains("list-modules");
    }

    @Test
    @DisplayName("index-info still answers for that directory, because metadata is the diagnosis")
    void indexInfoOnEmptyDirectorySucceeds() throws Exception {
        Result result = runDev("index-info", "--root", Fixtures.path("empty").toString());

        assertThat(result.exitCode()).isZero();
        assertThat(result.body().at("/metadata/fileCount").asInt()).isZero();
        assertThat(result.body().at("/metadata/warnings/0").asText()).contains(".java");
    }

    @Test
    @DisplayName("a project with source still walks straight through the CLI")
    void analysisOnRealProjectSucceeds() throws Exception {
        Result result = runDev("controllers", "--root", Fixtures.path("demo-web").toString());

        assertThat(result.exitCode()).isZero();
        assertThat(result.body().get("endpoints")).isNotEmpty();
    }
}

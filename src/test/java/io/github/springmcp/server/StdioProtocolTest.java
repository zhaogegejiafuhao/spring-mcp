package io.github.springmcp.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.springmcp.core.Fixtures;

/**
 * Drives the real server over stdio, the way an MCP client does.
 *
 * <p>
 * Every other test calls a tool or an analyzer directly, which is faster but blind to the layer this
 * project has actually broken: the entry point. A stray {@code println}, a log line routed to stdout,
 * a transport created before the real {@code System.out} is captured, a tool registry that silently
 * loses a name, or a process that outlives its client all pass every unit test and fail only here.
 * </p>
 *
 * <p>
 * The child process runs from the test classpath rather than the shaded jar, so this works during
 * {@code mvn test} without a packaging step. Shading has its own failure mode — service files merged
 * away, so the JSON mapper is never discovered — which the CI workflow covers by running the jar.
 * </p>
 */
class StdioProtocolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Every tool the server is documented to ship. */
    private static final List<String> EXPECTED_TOOLS = List.of("list-controllers", "find-service-callers",
            "find-implementations", "list-mappers", "search-symbol", "search-by-annotation",
            "get-file-dependencies", "list-gateway-routes", "list-projects", "list-modules");

    @Test
    @DisplayName("stdio: initialize → tools/list → 调用成功 → 调用失败 → 关闭即退出")
    void speaksTheProtocolOverStdio(@TempDir Path work) throws Exception {
        Path project = Fixtures.path("demo-web");
        Server server = Server.start(work, project);
        try {
            server.send(request(1, "initialize", Map.of(
                    "protocolVersion", "2025-11-25",
                    "capabilities", Map.of(),
                    "clientInfo", Map.of("name", "protocol-test", "version", "1"))));
            JsonNode init = server.receive();
            assertThat(init.path("id").asInt()).isEqualTo(1);
            assertThat(init.path("result").path("serverInfo").path("name").asText()).isNotBlank();
            assertThat(init.path("result").path("protocolVersion").asText()).isNotBlank();

            // Notifications carry no id, so no response is expected for the next line.
            server.send(Map.of("jsonrpc", "2.0", "method", "notifications/initialized"));

            server.send(request(2, "tools/list", Map.of()));
            List<String> names = new ArrayList<>();
            server.receive().path("result").path("tools").forEach(tool -> names.add(tool.path("name").asText()));
            assertThat(names).containsAll(EXPECTED_TOOLS).doesNotHaveDuplicates();

            server.send(request(3, "tools/call", Map.of(
                    "name", "list-controllers",
                    "arguments", Map.of("projectRoot", project.toString()))));
            JsonNode success = server.receive().path("result");
            assertThat(success.path("isError").asBoolean()).isFalse();
            assertThat(success.path("structuredContent").path("ok").asBoolean()).isTrue();
            assertThat(success.path("structuredContent").path("data").path("endpoints")).isNotEmpty();
            assertThat(success.path("content").get(0).path("text").asText()).isNotBlank();

            // A failure must arrive as a normal tool result with a code the caller can branch on, not
            // as a protocol error or an exception the transport turns into silence.
            server.send(request(4, "tools/call", Map.of(
                    "name", "list-controllers",
                    "arguments", Map.of("projectRoot", work.resolve("does-not-exist").toString()))));
            JsonNode failure = server.receive().path("result");
            assertThat(failure.path("isError").asBoolean()).isTrue();
            assertThat(failure.path("structuredContent").path("ok").asBoolean()).isFalse();
            assertThat(failure.path("structuredContent").path("error").path("code").asText())
                .isEqualTo("PATH_NOT_FOUND");
            assertThat(failure.path("structuredContent").path("error").path("hint").isTextual()).isTrue();

            // Everything the client saw is a JSON-RPC message. One non-JSON line desynchronises the
            // transport, and the symptom (a client timeout minutes later) points nowhere useful.
            assertThat(server.lines()).isNotEmpty()
                .allSatisfy(line -> assertThatCode(() -> MAPPER.readTree(line)).doesNotThrowAnyException());

            // Closing the client half of the pipe is how a session ends. A server that outlives it
            // leaks a JVM per session — invisible until the machine runs out of memory.
            server.closeStdin();
            assertThat(server.awaitExit(20))
                .as("关闭 stdin 后服务应自行退出；stderr:\n%s", server.stderrText())
                .isTrue();
            assertThat(server.exitCode()).as("正常结束应返回 0").isZero();
        }
        finally {
            server.kill();
        }
    }

    @Test
    @DisplayName("客户端发完请求就关闭管道，响应仍要写完且进程退出")
    void answersEvenWhenTheClientHangsUpImmediately(@TempDir Path work) throws Exception {
        Server server = Server.start(work, Fixtures.path("demo-web"));
        try {
            // Equivalent to `printf '<request>' | java -jar ...`: the request and the end of the input
            // arrive in one batch. Teardown used to win the race against the asynchronous dispatch and
            // the answer was silently lost — a shell pipeline saw zero bytes.
            server.send(request(1, "initialize", Map.of(
                    "protocolVersion", "2025-11-25",
                    "capabilities", Map.of(),
                    "clientInfo", Map.of("name", "hangup-test", "version", "1"))));
            server.closeStdin();

            JsonNode init = server.receive();
            assertThat(init.path("result").path("serverInfo").path("name").asText()).isNotBlank();
            assertThat(server.awaitExit(20))
                .as("关闭 stdin 后服务应自行退出；stderr:\n%s", server.stderrText())
                .isTrue();
            assertThat(server.exitCode()).isZero();
        }
        finally {
            server.kill();
        }
    }

    private static Map<String, Object> request(int id, String method, Map<String, Object> params) {
        return Map.of("jsonrpc", "2.0", "id", id, "method", method, "params", params);
    }

    /**
     * A server process with the pieces a test needs: line-oriented stdin, a drained stdout queue and
     * a stderr buffer that is only consulted when something times out.
     */
    private static final class Server {

        /** Generous: covers JVM start plus the first index build on a slow CI machine. */
        private static final long RESPONSE_TIMEOUT_SECONDS = 60;

        private final Process process;

        private final Writer stdin;

        private final BlockingQueue<String> stdout = new LinkedBlockingQueue<>();

        private final List<String> lines = Collections.synchronizedList(new ArrayList<>());

        private final StringBuilder stderr = new StringBuilder();

        private Server(Process process) {
            this.process = process;
            this.stdin = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8);
        }

        static Server start(Path workDir, Path projectRoot) throws IOException {
            ProcessBuilder builder = new ProcessBuilder(javaExecutable(), "-Dfile.encoding=UTF-8",
                    "-cp", System.getProperty("java.class.path"),
                    SpringMcpMain.class.getName(),
                    "--project=" + projectRoot,
                    // Without this the test would write the index cache into the developer's profile.
                    "--index-cache=off");
            // A neutral working directory: no ./plugins to load, so the tool list is the documented one.
            builder.directory(workDir.toFile());

            Server server = new Server(builder.start());
            server.pumpStdout();
            server.pumpStderr();
            return server;
        }

        private static String javaExecutable() {
            boolean windows = System.getProperty("os.name").toLowerCase(Locale.ROOT).startsWith("windows");
            return Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java").toString();
        }

        private void pumpStdout() {
            Thread thread = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        stdout.add(line);
                    }
                }
                catch (IOException e) {
                    // Process died; the empty queue turns into a timeout with the stderr text attached.
                }
            }, "protocol-test-stdout");
            thread.setDaemon(true);
            thread.start();
        }

        private void pumpStderr() {
            Thread thread = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        synchronized (stderr) {
                            stderr.append(line).append('\n');
                        }
                    }
                }
                catch (IOException e) {
                    // Same as above: nothing to add.
                }
            }, "protocol-test-stderr");
            thread.setDaemon(true);
            thread.start();
        }

        void send(Map<String, Object> message) throws IOException {
            stdin.write(MAPPER.writeValueAsString(message));
            stdin.write('\n');
            stdin.flush();
        }

        JsonNode receive() throws Exception {
            String line = stdout.poll(RESPONSE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertThat(line)
                .as("服务器在 %ds 内没有响应；已退出=%s，stderr:\n%s", RESPONSE_TIMEOUT_SECONDS,
                        !process.isAlive(), stderrText())
                .isNotNull();
            lines.add(line);
            return MAPPER.readTree(line);
        }

        void closeStdin() {
            try {
                stdin.close();
            }
            catch (IOException e) {
                // The process may already be gone; the exit assertion below reports that.
            }
        }

        boolean awaitExit(int seconds) {
            try {
                return process.waitFor(seconds, TimeUnit.SECONDS);
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        int exitCode() {
            return process.exitValue();
        }

        void kill() {
            if (process.isAlive()) {
                process.destroyForcibly();
                awaitExit(10);
            }
        }

        List<String> lines() {
            synchronized (lines) {
                return List.copyOf(lines);
            }
        }

        String stderrText() {
            synchronized (stderr) {
                return stderr.toString();
            }
        }
    }
}

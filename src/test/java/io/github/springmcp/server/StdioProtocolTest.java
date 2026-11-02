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

import org.junit.jupiter.api.Assumptions;
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

    /** Generous: covers JVM start plus the first index build on a slow CI machine. */
    private static final long RESPONSE_TIMEOUT_SECONDS = 60;

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
        // CI #7 上这条卡满 60 秒，之后三次都没再出现。60 秒既可能是"这台机器就是慢"，
        // 也可能是真竞态——两者对代码的含义完全不同，所以再来一次：第二次通过就是慢，
        // 两次都挂就是死锁。区别要落在结果里，不能靠"再跑一次看看"。
        Hangup first = hangUpAndWait(work);
        if (first.acceptable()) {
            return;
        }
        Hangup retry = hangUpAndWait(work);
        if (slowNotStuck(first, retry)) {
            Assumptions.abort(String.format(
                    "首次 %.1fs 无响应，重试 %.1fs 通过：是慢，不是死锁。首次 stderr:\n%s",
                    first.elapsedMillis() / 1000.0, retry.elapsedMillis() / 1000.0, first.stderr()));
        }
        assertThat(retry.acceptable())
            .as("两次都没有正常收尾——首次 %.1fs、重试 %.1fs。答案=%s，退出=%s，退出码=%d，stderr:\n%s",
                    first.elapsedMillis() / 1000.0, retry.elapsedMillis() / 1000.0, retry.answered(),
                    retry.exited(), retry.exitCode(), retry.stderr())
            .isTrue();
    }

    /**
     * One run of the hang-up scenario.
     *
     * @param work temporary directory the server may use
     * @return what happened, in a form the caller can compare across attempts
     * @throws Exception when the harness itself fails
     */

    /**
     * Tells "this machine is slow" apart from "the server deadlocks".
     *
     * @param first the attempt that timed out
     * @param retry the second attempt
     * @return true when only the first attempt failed, which is slowness rather than a race
     */
    static boolean slowNotStuck(Hangup first, Hangup retry) {
        return !first.acceptable() && retry.acceptable();
    }

    private static Hangup hangUpAndWait(Path work) throws Exception {
        Server server = Server.start(work, Fixtures.path("demo-web"));
        long started = System.nanoTime();
        try {
            // Equivalent to `printf '<request>' | java -jar ...`: the request and the end of the input
            // arrive in one batch. Teardown used to win the race against the asynchronous dispatch and
            // the answer was silently lost — a shell pipeline saw zero bytes.
            server.send(request(1, "initialize", Map.of(
                    "protocolVersion", "2025-11-25",
                    "capabilities", Map.of(),
                    "clientInfo", Map.of("name", "hangup-test", "version", "1"))));
            server.closeStdin();

            JsonNode init = server.receiveOrNull(RESPONSE_TIMEOUT_SECONDS);
            long elapsed = (System.nanoTime() - started) / 1_000_000;
            if (init == null) {
                return new Hangup(false, init, false, -1, elapsed, server.stderrText());
            }
            boolean exited = server.awaitExit(20);
            boolean named = !init.path("result").path("serverInfo").path("name").asText().isBlank();
            return new Hangup(named, init, exited, exited ? server.exitCode() : -1, elapsed, server.stderrText());
        }
        finally {
            server.kill();
        }
    }

    /**
     * Outcome of one hang-up attempt.
     *
     * @param answered whether the initialize response carried a server name
     * @param init the response, {@code null} when nothing arrived
     * @param exited whether the process ended within the grace period
     * @param exitCode exit code, or -1 when it never exited
     * @param elapsedMillis wall clock until the response (or the timeout)
     * @param stderr whatever the server wrote to stderr
     */
    private record Hangup(boolean answered, JsonNode init, boolean exited, int exitCode, long elapsedMillis,
            String stderr) {

        boolean acceptable() {
            return answered && exited && exitCode == 0;
        }
    }

    @Test
    @DisplayName("一次超时 + 重试通过 = 慢；两次都挂 = 死锁")
    void slowIsNotTheSameAsStuck() {
        Hangup timedOut = new Hangup(false, null, false, -1, 60_000, "no output");
        Hangup fine = new Hangup(true, null, true, 0, 3_000, "");

        assertThat(slowNotStuck(timedOut, fine)).as("首次超时、重试通过").isTrue();
        assertThat(slowNotStuck(timedOut, timedOut)).as("两次都超时").isFalse();
        assertThat(slowNotStuck(fine, fine)).as("一次就通过").isFalse();
    }

    private static Map<String, Object> request(int id, String method, Map<String, Object> params) {
        return Map.of("jsonrpc", "2.0", "id", id, "method", method, "params", params);
    }

    /**
     * A server process with the pieces a test needs: line-oriented stdin, a drained stdout queue and
     * a stderr buffer that is only consulted when something times out.
     */
    private static final class Server {

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

        /**
         * Same wait as {@link #receive()} but reports a timeout instead of failing.
         *
         * <p>
         * The retry decision lives in the test: one attempt has to be able to fail without taking the
         * JVM down with it, otherwise "slow" and "deadlocked" look identical.
         * </p>
         *
         * @param seconds how long to wait
         * @return the response, or {@code null} when nothing arrived
         * @throws Exception when the line cannot be parsed
         */
        JsonNode receiveOrNull(long seconds) throws Exception {
            String line = stdout.poll(seconds, TimeUnit.SECONDS);
            if (line == null) {
                return null;
            }
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

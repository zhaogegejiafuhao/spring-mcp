package io.github.springmcp.server.external;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.springmcp.spi.ErrorCode;
import io.github.springmcp.spi.ToolFailure;
import io.github.springmcp.spi.ToolOutcome;

/**
 * Runs one external-process tool invocation.
 *
 * <p>
 * One process per call, JSON in, exactly one JSON document out, exit code 0. A resident process with
 * line-oriented RPC would be faster but brings lifecycle, crash recovery and concurrency
 * serialisation — none of which is worth it before the simple version has proven useful.
 * </p>
 *
 * <p>
 * Three details are easy to get wrong and are handled explicitly:
 * </p>
 *
 * <ol>
 * <li><strong>Never {@code inheritIO()}.</strong> The child's stdout would be wired to the MCP
 * protocol channel and corrupt the session.</li>
 * <li><strong>Drain both pipes concurrently.</strong> A child that fills its stderr buffer while we
 * are blocked reading stdout deadlocks; both are read on their own thread.</li>
 * <li><strong>Keep draining past the cap.</strong> Once the output limit is hit, further bytes are
 * read and discarded rather than left unread, otherwise the child blocks on a full pipe and the
 * timeout fires on a process that was merely chatty.</li>
 * </ol>
 */
public final class ExternalProcessRunner {

    /** Protocol marker written to the child's stdin. */
    public static final String PROTOCOL = "spring-mcp.external/1";

    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * Executes an external tool.
     *
     * @param definition tool definition from the config file
     * @param arguments caller arguments (already filtered to exposed names)
     * @param projectRoot project root, forwarded to the child but not used as the working directory
     * @param extensionBase directory of the config file; the working directory is resolved against it
     * @param context extra context passed to the child, may be empty
     * @return the tool outcome; failures are returned, never thrown
     */
    public ToolOutcome run(io.github.springmcp.server.declarative.DeclarativeConfig.ExternalDef definition,
            Map<String, Object> arguments, Path projectRoot, Path extensionBase, Map<String, Object> context) {

        List<String> command = definition.command();
        if (command.isEmpty()) {
            return ToolOutcome.fail(ToolFailure.of(ErrorCode.PLUGIN_ERROR,
                    "外部工具 '" + definition.name() + "' 的 command 为空", "在配置文件里补上 command 数组"));
        }

        Path workingDir;
        try {
            workingDir = resolveWorkingDirectory(definition, extensionBase);
        }
        catch (ToolFailure failure) {
            return ToolOutcome.fail(failure);
        }

        ProcessBuilder builder = new ProcessBuilder(command).directory(workingDir.toFile());
        Process process;
        try {
            process = builder.start();
        }
        catch (IOException e) {
            return ToolOutcome.fail(ToolFailure.of(ErrorCode.PLUGIN_ERROR,
                    "外部工具 '" + definition.name() + "' 启动失败: " + e.getMessage(),
                    "确认 command[0] 在 PATH 中，或改用绝对路径", e));
        }

        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int cap = definition.effectiveMaxOutputBytes();
        Thread outReader = Thread.ofVirtual().start(() -> drain(process.getInputStream(), stdout, cap));
        Thread errReader = Thread.ofVirtual().start(() -> drain(process.getErrorStream(), stderr, cap));

        try {
            writeRequest(process, definition, arguments, projectRoot, context);
        }
        catch (IOException e) {
            process.destroyForcibly();
            return ToolOutcome.fail(ToolFailure.of(ErrorCode.PLUGIN_ERROR,
                    "向外部工具写入参数失败: " + e.getMessage(), "子进程可能已退出", e));
        }

        long timeoutMs = definition.effectiveTimeoutMs();
        try {
            if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                outReader.join(TimeUnit.SECONDS.toMillis(2));
                errReader.join(TimeUnit.SECONDS.toMillis(2));
                return ToolOutcome.fail(ToolFailure.of(ErrorCode.TIMEOUT,
                        "外部工具 '" + definition.name() + "' 超过 " + timeoutMs + "ms 未返回",
                        "把 timeoutMs 调大，或检查脚本是否在等待输入"));
            }
            outReader.join(TimeUnit.SECONDS.toMillis(5));
            errReader.join(TimeUnit.SECONDS.toMillis(5));
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            return ToolOutcome.fail(ToolFailure.of(ErrorCode.TIMEOUT, "外部工具调用被中断", null, e));
        }

        int exitCode = process.exitValue();
        String stdoutText = stdout.toString(StandardCharsets.UTF_8);
        String stderrText = stderr.toString(StandardCharsets.UTF_8);

        if (exitCode != 0) {
            return ToolOutcome.fail(ToolFailure.of(ErrorCode.PLUGIN_ERROR,
                    "外部工具 '" + definition.name() + "' 退出码 " + exitCode + tail(stderrText),
                    "检查脚本自身的日志与参数"));
        }

        return parseResponse(definition, stdoutText);
    }

    /**
     * Resolves the working directory of the child process.
     *
     * <p>
     * Relative to the <em>extension</em> directory, not the analysed project. An extension ships with
     * its own scripts; requiring them to exist inside every repository it analyses would make the
     * extension usable only on the repository it was written for. The project root is still handed to
     * the child in the request, so a tool that wants to operate on the project can.
     * </p>
     *
     * @param def tool definition
     * @param extensionBase directory holding the config file
     * @return absolute working directory
     * @throws ToolFailure when the configured directory escapes the extension directory
     */
    private Path resolveWorkingDirectory(io.github.springmcp.server.declarative.DeclarativeConfig.ExternalDef def,
            Path extensionBase) throws ToolFailure {
        Path base = extensionBase.toAbsolutePath().normalize();
        if (def.cwd() == null || def.cwd().isBlank()) {
            return base;
        }
        Path resolved = base.resolve(def.cwd()).normalize();
        if (!resolved.startsWith(base)) {
            throw ToolFailure.of(ErrorCode.INVALID_ARGUMENT,
                    "外部工具 '" + def.name() + "' 的 cwd 逃出了扩展目录: " + def.cwd(),
                    "cwd 必须是扩展目录内的相对路径");
        }
        return resolved;
    }

    private void writeRequest(Process process,
            io.github.springmcp.server.declarative.DeclarativeConfig.ExternalDef def, Map<String, Object> arguments,
            Path projectRoot, Map<String, Object> context) throws IOException {

        ObjectNode request = mapper.createObjectNode();
        request.put("protocol", PROTOCOL);
        request.put("tool", def.name());
        request.set("arguments", mapper.valueToTree(arguments));
        request.put("projectRoot", projectRoot.toAbsolutePath().normalize().toString());
        request.set("context", mapper.valueToTree(context == null ? Map.of() : context));

        try (var stdin = process.getOutputStream()) {
            // Closing stdin is the signal that the request is complete; a child reading to EOF would
            // otherwise block forever and hit the timeout for no reason.
            stdin.write(mapper.writeValueAsBytes(request));
            stdin.flush();
        }
    }

    private ToolOutcome parseResponse(io.github.springmcp.server.declarative.DeclarativeConfig.ExternalDef def,
            String stdoutText) {
        if (stdoutText.isBlank()) {
            return ToolOutcome.fail(ToolFailure.of(ErrorCode.PLUGIN_ERROR,
                    "外部工具 '" + def.name() + "' 没有输出任何 JSON", "约定：向 stdout 写一个 JSON 对象"));
        }

        JsonNode response;
        try {
            response = mapper.readTree(stdoutText);
        }
        catch (IOException e) {
            return ToolOutcome.fail(ToolFailure.of(ErrorCode.PLUGIN_ERROR,
                    "外部工具 '" + def.name() + "' 的输出不是合法 JSON: " + e.getMessage(),
                    "只向 stdout 写 JSON；调试信息请写 stderr"));
        }

        boolean ok = response.path("ok").asBoolean(true);
        if (!ok) {
            ErrorCode code = parseCode(response.path("code").asText(null));
            return ToolOutcome.fail(ToolFailure.of(code, response.path("message").asText("外部工具报告失败"),
                    response.path("hint").asText(null)));
        }

        Object data = response.has("data") ? mapper.convertValue(response.path("data"), Object.class) : Map.of();
        String summary = response.path("summary").asText("外部工具 '" + def.name() + "' 执行完成");
        List<String> warnings = new java.util.ArrayList<>();
        for (JsonNode warning : response.path("warnings")) {
            warnings.add(warning.asText());
        }
        return ToolOutcome.ok(data, summary, warnings);
    }

    private static ErrorCode parseCode(String raw) {
        if (raw == null || raw.isBlank()) {
            return ErrorCode.PLUGIN_ERROR;
        }
        try {
            return ErrorCode.valueOf(raw.trim());
        }
        catch (IllegalArgumentException e) {
            // Unknown code from a third-party script: fall back rather than failing the call.
            return ErrorCode.PLUGIN_ERROR;
        }
    }

    private static void drain(InputStream stream, ByteArrayOutputStream sink, int cap) {
        byte[] buffer = new byte[8192];
        int kept = 0;
        try (stream) {
            int read;
            while ((read = stream.read(buffer)) > 0) {
                if (kept < cap) {
                    int allowed = Math.min(read, cap - kept);
                    sink.write(buffer, 0, allowed);
                    kept += allowed;
                }
                // Bytes past the cap are still consumed, just discarded, so the child never blocks.
            }
        }
        catch (IOException e) {
            // Stream closed because the process died; the exit code check reports it.
        }
    }

    private static String tail(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String trimmed = text.strip();
        String last = trimmed.length() <= 500 ? trimmed : trimmed.substring(trimmed.length() - 500);
        return "；stderr: " + last;
    }

    /**
     * Builds the request document without running anything, for tests and for {@code --dev config-check}.
     * @param def tool definition
     * @param arguments caller arguments
     * @param projectRoot project root
     * @return the JSON request as a map
     */
    public Map<String, Object> describeRequest(io.github.springmcp.server.declarative.DeclarativeConfig.ExternalDef def,
            Map<String, Object> arguments, Path projectRoot) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("protocol", PROTOCOL);
        request.put("tool", def.name());
        request.put("arguments", arguments);
        request.put("projectRoot", projectRoot.toAbsolutePath().normalize().toString());
        request.put("command", def.command());
        return request;
    }
}

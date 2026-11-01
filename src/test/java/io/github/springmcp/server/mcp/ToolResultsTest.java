package io.github.springmcp.server.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.springmcp.spi.ErrorCode;
import io.github.springmcp.spi.ToolFailure;
import io.github.springmcp.spi.ToolOutcome;
import io.modelcontextprotocol.spec.McpSchema;

/**
 * Result envelope. The shape asserted here is the contract a client codes against, so it is checked
 * rather than assumed.
 */
class ToolResultsTest {

    private final ToolResults results = new ToolResults(new ObjectMapper());

    @SuppressWarnings("unchecked")
    private static Map<String, Object> body(McpSchema.CallToolResult result) {
        return (Map<String, Object>) result.structuredContent();
    }

    @Test
    @DisplayName("a record payload becomes a JSON object under data")
    void successWrapsRecord() {
        McpSchema.CallToolResult result = results
            .ok(new ToolOutcome.Success(new Payload("x", 2), "found 2 things", List.of()));

        assertThat(result.isError()).isFalse();
        Map<String, Object> body = body(result);
        assertThat(body.get("ok")).isEqualTo(true);

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) body.get("data");
        assertThat(data).containsEntry("name", "x").containsEntry("count", 2);

        assertThat(((McpSchema.TextContent) result.content().get(0)).text()).isEqualTo("found 2 things");
        assertThat(body).doesNotContainKey("warnings");
    }

    @Test
    @DisplayName("warnings are carried alongside the data")
    void successWithWarnings() {
        McpSchema.CallToolResult result = results
            .ok(new ToolOutcome.Success(Map.of("k", "v"), "ok", List.of("1 个文件解析失败")));

        @SuppressWarnings("unchecked")
        List<String> warnings = (List<String>) body(result).get("warnings");
        assertThat(warnings).containsExactly("1 个文件解析失败");
    }

    @Test
    @DisplayName("a failure carries both a code and a human message, and isError is true")
    void failure() {
        McpSchema.CallToolResult result = results
            .error(ToolFailure.of(ErrorCode.PATH_NOT_FOUND, "目录不存在: /x", "检查路径"));

        assertThat(result.isError()).isTrue();

        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) body(result).get("error");
        assertThat(error).containsEntry("code", "PATH_NOT_FOUND").containsEntry("hint", "检查路径");

        String text = ((McpSchema.TextContent) result.content().get(0)).text();
        assertThat(text).startsWith("[PATH_NOT_FOUND]").contains("提示: 检查路径");
    }

    @Test
    @DisplayName("an empty payload still yields a JSON object, never null")
    void nullPayload() {
        McpSchema.CallToolResult result = results.ok(new ToolOutcome.Success(null, "nothing", null));
        assertThat(body(result).get("data")).isEqualTo(Map.of());
    }

    @Test
    @DisplayName("a map payload is passed through unchanged")
    void mapPayload() {
        McpSchema.CallToolResult result = results
            .ok(new ToolOutcome.Success(Map.of("a", 1), "ok", List.of()));
        assertThat(body(result).get("data")).isEqualTo(Map.of("a", 1));
    }

    /** Minimal record standing in for a real analysis result. */
    record Payload(String name, int count) {
    }
}

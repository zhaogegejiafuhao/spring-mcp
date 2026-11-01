package io.github.springmcp.server.mcp;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.springmcp.spi.ErrorCode;
import io.github.springmcp.spi.ToolFailure;
import io.github.springmcp.spi.ToolOutcome;
import io.modelcontextprotocol.spec.McpSchema;

/**
 * Converts a {@link ToolOutcome} into an MCP {@code CallToolResult}.
 *
 * <p>
 * Two rules are enforced here so that no tool has to remember them.
 * </p>
 *
 * <p>
 * First, {@code structuredContent} is always a JSON <em>object</em> with a stable envelope:
 * {@code {"ok":true,"data":...,"warnings":[...]}} or
 * {@code {"ok":false,"error":{"code":...,"message":...,"hint":...}}}. MCP requires an object there,
 * and a stable shape means a client can branch on {@code ok} without parsing prose.
 * </p>
 *
 * <p>
 * Second, failures carry <em>both</em> a machine readable code and a human readable text block. An
 * LLM reads the text and corrects its call; a program reads the code. Dropping either one makes the
 * other audience guess.
 * </p>
 */
public final class ToolResults {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final ObjectMapper mapper;

    /**
     * @param mapper Jackson mapper used to normalise result objects into maps
     */
    public ToolResults(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * Builds a success result.
     * @param success the tool outcome
     * @return a {@code CallToolResult} with {@code isError=false}
     */
    public McpSchema.CallToolResult ok(ToolOutcome.Success success) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("data", toMap(success.structured()));
        if (!success.warnings().isEmpty()) {
            body.put("warnings", success.warnings());
        }
        String text = success.humanSummary() == null || success.humanSummary().isBlank() ? "(no result)"
                : success.humanSummary();
        return McpSchema.CallToolResult.builder()
            .content(List.of(McpSchema.TextContent.builder(text).build()))
            .structuredContent(body)
            .isError(false)
            .build();
    }

    /**
     * Builds a failure result.
     * @param failure the domain failure
     * @return a {@code CallToolResult} with {@code isError=true}
     */
    public McpSchema.CallToolResult error(ToolFailure failure) {
        return error(failure.code(), failure.getMessage(), failure.hint());
    }

    /**
     * Builds a failure result from raw parts.
     * @param code machine readable category
     * @param message human readable detail
     * @param hint suggested next step, may be {@code null}
     * @return a {@code CallToolResult} with {@code isError=true}
     */
    public McpSchema.CallToolResult error(ErrorCode code, String message, String hint) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", code.name());
        error.put("message", message == null ? "" : message);
        error.put("hint", hint == null ? "" : hint);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", false);
        body.put("error", error);

        StringBuilder text = new StringBuilder("[").append(code.name()).append("] ")
            .append(message == null ? "" : message);
        if (hint != null && !hint.isBlank()) {
            text.append('\n').append("提示: ").append(hint);
        }

        return McpSchema.CallToolResult.builder()
            .content(List.of(McpSchema.TextContent.builder(text.toString()).build()))
            .structuredContent(body)
            .isError(true)
            .build();
    }

    /**
     * Normalises an arbitrary result object into a map.
     *
     * <p>
     * Tools return records because that is the readable thing to write, but MCP demands an object in
     * {@code structuredContent}. Converting here keeps that constraint in one place.
     * </p>
     * @param structured tool payload, may be {@code null}
     * @return a map, empty when there was nothing to convert
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> toMap(Object structured) {
        if (structured == null) {
            return Map.of();
        }
        if (structured instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return mapper.convertValue(structured, MAP_TYPE);
    }
}

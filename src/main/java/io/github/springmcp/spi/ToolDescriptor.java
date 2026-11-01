package io.github.springmcp.spi;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Static metadata of a tool: what it is called, how the LLM should decide to use it, and the
 * JSON Schema of its arguments.
 *
 * @param name globally unique tool name. Must be kebab-case ({@code list-controllers}); the MCP
 * SDK rejects or mangles other shapes.
 * @param title optional human readable title shown by some clients.
 * @param description what the tool does, what it returns, and — most importantly — when
 * <em>not</em> to use it. This text is the single biggest lever on tool-selection accuracy.
 * @param inputSchemaJson a complete JSON Schema document as text. The top level must be
 * {@code {"type":"object"}}. The server parses it once at registration time and rejects the
 * tool if the SDK considers it invalid.
 * @param tags free-form labels, currently informational only.
 */
public record ToolDescriptor(String name, String title, String description, String inputSchemaJson, List<String> tags) {

    private static final Pattern VALID_NAME = Pattern.compile("[a-z][a-z0-9]*(-[a-z0-9]+)*");

    public ToolDescriptor {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(description, "description must not be null");
        Objects.requireNonNull(inputSchemaJson, "inputSchemaJson must not be null");
        if (!VALID_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException(
                    "tool name must be kebab-case, lowercase letters/digits separated by '-', got: " + name);
        }
        tags = tags == null ? List.of() : List.copyOf(tags);
    }

    /**
     * Starts building a descriptor.
     * @param name tool name, kebab-case
     * @return builder
     */
    public static Builder builder(String name) {
        return new Builder(name);
    }

    /** Mutable builder; {@link #build()} validates eagerly so bad metadata fails at startup. */
    public static final class Builder {

        private final String name;

        private String title;

        private String description = "";

        private String inputSchemaJson = JsonSchemas.emptyObjectSchema();

        private List<String> tags = List.of();

        private Builder(String name) {
            this.name = name;
        }

        public Builder title(String title) {
            this.title = title;
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder inputSchemaJson(String inputSchemaJson) {
            this.inputSchemaJson = inputSchemaJson;
            return this;
        }

        public Builder inputSchema(JsonSchemas.SchemaBuilder schema) {
            this.inputSchemaJson = schema.buildJson();
            return this;
        }

        public Builder tags(List<String> tags) {
            this.tags = tags;
            return this;
        }

        public ToolDescriptor build() {
            return new ToolDescriptor(name, title, description, inputSchemaJson, tags);
        }
    }
}

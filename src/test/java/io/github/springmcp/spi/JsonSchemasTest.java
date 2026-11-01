package io.github.springmcp.spi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The schema builder emits JSON text that the MCP SDK parses at registration time.
 *
 * <p>
 * These tests deliberately avoid a JSON library: the SPI module must stay dependency free, and the
 * enforcer rule in its pom refuses any third-party jar even in test scope. Using exact-string
 * comparison is not a weaker check here — it pins the contract, including property order, which is
 * what keeps generated schemas diff friendly.
 * </p>
 */
class JsonSchemasTest {

    @Test
    @DisplayName("the empty schema is a minimal valid object schema")
    void emptySchema() {
        assertThat(JsonSchemas.emptyObjectSchema())
            .isEqualTo("{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}");
    }

    @Test
    @DisplayName("a small schema renders in a stable, parseable shape")
    void smallSchema() {
        String json = JsonSchemas.object()
            .string("projectRoot", "工程根目录", true, null)
            .integer("limit", "上限", false, 1, 500)
            .buildJson();

        assertThat(json).isEqualTo("{\"type\":\"object\",\"properties\":{"
                + "\"projectRoot\":{\"type\":\"string\",\"description\":\"工程根目录\"},"
                + "\"limit\":{\"type\":\"integer\",\"description\":\"上限\",\"minimum\":1,\"maximum\":500}"
                + "},\"required\":[\"projectRoot\"],\"additionalProperties\":false}");
    }

    @Test
    @DisplayName("required properties are only the ones asked for")
    void requiredList() {
        String json = JsonSchemas.object()
            .string("a", "d", true, null)
            .string("b", "d", false, null)
            .bool("c", "d", false)
            .buildJson();
        assertThat(json).contains("\"required\":[\"a\"]");
    }

    @Test
    @DisplayName("patterns, enums and arrays are emitted when supplied")
    void advancedProperties() {
        String json = JsonSchemas.object()
            .string("p", "d", false, "^/api")
            .enumString("verb", "d", false, List.of("GET", "POST"))
            .arrayOfStrings("kinds", "d", false, List.of("A", "B"))
            .buildJson();

        assertThat(json).contains("\"pattern\":\"^/api\"");
        assertThat(json).contains("\"enum\":[\"GET\",\"POST\"]");
        assertThat(json).contains("\"items\":{\"type\":\"string\",\"enum\":[\"A\",\"B\"]}");
    }

    @Test
    @DisplayName("quotes, backslashes and control characters are escaped")
    void escapesText() {
        assertThat(JsonSchemas.escape("a\"b")).isEqualTo("a\\\"b");
        assertThat(JsonSchemas.escape("a\\b")).isEqualTo("a\\\\b");
        assertThat(JsonSchemas.escape("a\nb")).isEqualTo("a\\nb");
        assertThat(JsonSchemas.escape("\u0001")).isEqualTo("\\u0001");
        assertThat(JsonSchemas.escape(null)).isEmpty();
    }

    @Test
    @DisplayName("an enum property needs at least one value")
    void enumNeedsValues() {
        assertThatThrownBy(() -> JsonSchemas.object().enumString("x", "d", false, List.of()))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("tool names must be kebab-case")
    void toolNameValidation() {
        assertThat(ToolDescriptor.builder("list-controllers")
            .description("d")
            .inputSchema(JsonSchemas.object().string("a", "d", false, null))
            .build()
            .name()).isEqualTo("list-controllers");

        assertThatThrownBy(() -> ToolDescriptor.builder("ListControllers").build())
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("kebab-case");
        assertThatThrownBy(() -> ToolDescriptor.builder("list_controllers").build())
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("descriptor tags default to an immutable empty list")
    void descriptorDefaults() {
        ToolDescriptor d = ToolDescriptor.builder("x-tool").description("d").build();
        assertThat(d.tags()).isEmpty();
        assertThatThrownBy(() -> d.tags().add("nope")).isInstanceOf(UnsupportedOperationException.class);
    }
}

package io.github.springmcp.spi;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tiny, dependency-free JSON Schema builder.
 *
 * <p>
 * The SPI cannot depend on Jackson (that would pin plugin authors to a serialisation library
 * and leak into the plugin classpath), so tool input schemas are exchanged as <em>text</em>.
 * This class produces that text. It only covers the subset spring-mcp needs — if a plugin
 * wants something exotic it can simply return a hand-written JSON string from
 * {@link ToolDescriptor#inputSchemaJson()}.
 * </p>
 *
 * <p>
 * Only the {@code "type":"object"} shape is supported, because MCP tool arguments are always
 * an object.
 * </p>
 */
public final class JsonSchemas {

    private JsonSchemas() {
    }

    /** @return a valid schema accepting any object: {@code {"type":"object","properties":{}}} */
    public static String emptyObjectSchema() {
        return "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}";
    }

    /**
     * Starts an object schema.
     * @return a fresh builder
     */
    public static SchemaBuilder object() {
        return new SchemaBuilder();
    }

    /**
     * Escapes a string for embedding in a JSON document.
     * @param raw text, may be {@code null}
     * @return escaped text without surrounding quotes
     */
    public static String escape(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(raw.length() + 16);
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    }
                    else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    /**
     * Incremental builder for a flat object schema. Property order is preserved, which keeps
     * generated schemas stable across runs and therefore diff-friendly.
     */
    public static final class SchemaBuilder {

        private final Map<String, String> properties = new LinkedHashMap<>();

        private final List<String> required = new ArrayList<>();

        private String title;

        private boolean additionalProperties = false;

        private SchemaBuilder() {
        }

        /** Sets the schema {@code title}. */
        public SchemaBuilder title(String title) {
            this.title = title;
            return this;
        }

        /**
         * Adds a string property.
         * @param name property name
         * @param description what the caller should put here
         * @param isRequired whether the property is mandatory
         * @param pattern optional ECMA-262 regex the value must match, may be {@code null}
         */
        public SchemaBuilder string(String name, String description, boolean isRequired, String pattern) {
            StringBuilder sb = new StringBuilder("{\"type\":\"string\",\"description\":\"");
            sb.append(escape(description)).append('"');
            if (pattern != null && !pattern.isBlank()) {
                sb.append(",\"pattern\":\"").append(escape(pattern)).append('"');
            }
            sb.append('}');
            return put(name, sb.toString(), isRequired);
        }

        /**
         * Adds an integer property.
         * @param name property name
         * @param description what the caller should put here
         * @param isRequired whether the property is mandatory
         * @param minimum optional inclusive lower bound
         * @param maximum optional inclusive upper bound
         */
        public SchemaBuilder integer(String name, String description, boolean isRequired, Integer minimum,
                Integer maximum) {
            StringBuilder sb = new StringBuilder("{\"type\":\"integer\",\"description\":\"");
            sb.append(escape(description)).append('"');
            if (minimum != null) {
                sb.append(",\"minimum\":").append(minimum);
            }
            if (maximum != null) {
                sb.append(",\"maximum\":").append(maximum);
            }
            sb.append('}');
            return put(name, sb.toString(), isRequired);
        }

        /**
         * Adds a boolean property.
         * @param name property name
         * @param description what the caller should put here
         * @param isRequired whether the property is mandatory
         */
        public SchemaBuilder bool(String name, String description, boolean isRequired) {
            String json = "{\"type\":\"boolean\",\"description\":\"" + escape(description) + "\"}";
            return put(name, json, isRequired);
        }

        /**
         * Adds a string property restricted to a fixed set of values.
         * @param name property name
         * @param description what the caller should put here
         * @param isRequired whether the property is mandatory
         * @param values allowed values, must not be empty
         */
        public SchemaBuilder enumString(String name, String description, boolean isRequired, List<String> values) {
            if (values == null || values.isEmpty()) {
                throw new IllegalArgumentException("enum property '" + name + "' needs at least one value");
            }
            StringBuilder sb = new StringBuilder("{\"type\":\"string\",\"description\":\"");
            sb.append(escape(description)).append("\",\"enum\":[");
            for (int i = 0; i < values.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append('"').append(escape(values.get(i))).append('"');
            }
            sb.append("]}");
            return put(name, sb.toString(), isRequired);
        }

        /**
         * Adds an array-of-strings property.
         * @param name property name
         * @param description what the caller should put here
         * @param isRequired whether the property is mandatory
         */
        public SchemaBuilder arrayOfStrings(String name, String description, boolean isRequired) {
            return arrayOfStrings(name, description, isRequired, null);
        }

        /**
         * Adds an array-of-strings property whose items are restricted to a fixed set.
         * @param name property name
         * @param description what the caller should put here
         * @param isRequired whether the property is mandatory
         * @param allowedValues allowed item values, or {@code null} for unconstrained items
         */
        public SchemaBuilder arrayOfStrings(String name, String description, boolean isRequired,
                List<String> allowedValues) {
            StringBuilder sb = new StringBuilder("{\"type\":\"array\",\"description\":\"");
            sb.append(escape(description)).append("\",\"items\":{\"type\":\"string\"");
            if (allowedValues != null && !allowedValues.isEmpty()) {
                sb.append(",\"enum\":[");
                for (int i = 0; i < allowedValues.size(); i++) {
                    if (i > 0) {
                        sb.append(',');
                    }
                    sb.append('"').append(escape(allowedValues.get(i))).append('"');
                }
                sb.append(']');
            }
            sb.append("}}");
            return put(name, sb.toString(), isRequired);
        }

        /**
         * Controls whether unknown properties are allowed. Defaults to {@code false} so that a
         * misspelled argument name is rejected instead of silently ignored.
         */
        public SchemaBuilder additionalProperties(boolean allow) {
            this.additionalProperties = allow;
            return this;
        }

        private SchemaBuilder put(String name, String json, boolean isRequired) {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("property name must not be blank");
            }
            properties.put(name, json);
            if (isRequired) {
                required.add(name);
            }
            return this;
        }

        /**
         * Renders the schema document. Top level is always {@code "type":"object"}.
         * @return JSON text
         */
        public String buildJson() {
            StringBuilder sb = new StringBuilder(256);
            sb.append('{');
            if (title != null && !title.isBlank()) {
                sb.append("\"title\":\"").append(escape(title)).append("\",");
            }
            sb.append("\"type\":\"object\",\"properties\":{");
            boolean first = true;
            for (Map.Entry<String, String> e : properties.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append('"').append(escape(e.getKey())).append("\":").append(e.getValue());
            }
            sb.append("},\"required\":[");
            for (int i = 0; i < required.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append('"').append(escape(required.get(i))).append('"');
            }
            sb.append("],\"additionalProperties\":").append(additionalProperties);
            sb.append('}');
            return sb.toString();
        }

        @Override
        public String toString() {
            return buildJson();
        }
    }
}

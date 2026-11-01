package io.github.springmcp.server.declarative;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Derives the input schema of a declarative tool from the schema of the tool it wraps.
 *
 * <p>
 * Types, enums and bounds are inherited rather than restated, so a declarative tool cannot drift out
 * of sync with the query it delegates to — if {@code list-controllers} gains a parameter, derived
 * schemas pick it up. The config author only decides what is visible and what is pinned.
 * </p>
 *
 * <p>
 * The validation here targets the mistake that is genuinely easy to make: writing a "find callers of
 * X" tool and forgetting to pin {@code serviceClass}. Without this check that tool would be
 * registered, look fine in {@code tools/list}, and fail on every call. Rejecting it at load time with
 * the parameter name is far cheaper than debugging it later.
 * </p>
 */
public final class SchemaDeriver {

    private SchemaDeriver() {
    }

    /**
     * Thrown when a declaration cannot be turned into a usable schema.
     */
    public static final class SchemaException extends Exception {

        private static final long serialVersionUID = 1L;

        SchemaException(String message) {
            super(message);
        }
    }

    /**
     * Builds the derived schema.
     * @param baseSchemaJson schema of the wrapped tool
     * @param name tool name, used in messages
     * @param fixed pinned arguments
     * @param expose caller-visible arguments
     * @param mapper JSON mapper
     * @return JSON text of the derived schema
     * @throws SchemaException when the declaration is inconsistent with the base schema
     */
    public static String derive(String baseSchemaJson, String name, Map<String, Object> fixed,
            List<DeclarativeConfig.Param> expose, ObjectMapper mapper) throws SchemaException {

        ObjectNode base;
        try {
            base = (ObjectNode) mapper.readTree(baseSchemaJson);
        }
        catch (Exception e) {
            throw new SchemaException("内建工具的 schema 无法解析: " + e.getMessage());
        }

        JsonNode baseProperties = base.path("properties");
        if (!baseProperties.isObject()) {
            throw new SchemaException("内建工具的 schema 没有 properties，无法派生");
        }

        Set<String> available = new LinkedHashSet<>();
        baseProperties.fieldNames().forEachRemaining(available::add);

        Set<String> keep = new LinkedHashSet<>();
        ObjectNode properties = mapper.createObjectNode();

        for (Map.Entry<String, Object> entry : fixed.entrySet()) {
            String key = entry.getKey();
            if (!available.contains(key)) {
                throw new SchemaException("fixed 里的 '" + key + "' 不是 " + name + " 的参数；可用参数: "
                        + String.join(", ", available));
            }
            // Deliberately not added to the derived properties. A pinned argument is not a parameter:
            // leaving it in the schema invites a caller to pass a different value, which would then be
            // silently ignored — the worst of both worlds. What the tool has pinned is instead stated
            // in its description, where it reads as fact rather than as an option.
        }

        for (DeclarativeConfig.Param param : expose) {
            if (param == null || param.name() == null || param.name().isBlank()) {
                throw new SchemaException("expose 里有空的参数名");
            }
            String key = param.name();
            if (!available.contains(key)) {
                throw new SchemaException("expose 里的 '" + key + "' 不是 " + name + " 的参数；可用参数: "
                        + String.join(", ", available));
            }
            ObjectNode property = ((ObjectNode) baseProperties.get(key)).deepCopy();
            if (param.description() != null && !param.description().isBlank()) {
                property.put("description", param.description());
            }
            properties.set(key, property);
            keep.add(key);
        }

        List<String> required = new ArrayList<>();
        List<String> unsatisfied = new ArrayList<>();
        for (JsonNode requiredName : base.path("required")) {
            String key = requiredName.asText();
            if (keep.contains(key)) {
                required.add(key);
            }
            else if (!fixed.containsKey(key)) {
                unsatisfied.add(key);
            }
        }
        if (!unsatisfied.isEmpty()) {
            throw new SchemaException("必填参数 " + unsatisfied + " 既未 expose 也未 fixed，调用方无法提供");
        }

        ObjectNode derived = mapper.createObjectNode();
        derived.put("type", "object");
        derived.set("properties", properties);
        ArrayNode requiredNode = derived.putArray("required");
        required.forEach(requiredNode::add);
        derived.put("additionalProperties", false);
        return derived.toString();
    }
}

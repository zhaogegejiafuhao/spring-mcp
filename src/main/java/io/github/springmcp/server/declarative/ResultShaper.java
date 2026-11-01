package io.github.springmcp.server.declarative;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Reshapes a delegated tool result into the declarative tool's own answer.
 *
 * <p>
 * Supported operations are selection, grouping, ordering and truncation — nothing that needs an
 * expression evaluator. That is a deliberate ceiling: a config file that can compute is a
 * programming language without a debugger, and anyone who needs that should write an external
 * process tool or a real plugin.
 * </p>
 *
 * <p>
 * The shaped payload does <strong>not</strong> inherit the original report's other fields. Keeping,
 * say, {@code controllerCount} from a report whose endpoint list has been filtered down to twelve
 * items would state something false about the twelve items.
 * </p>
 *
 * <p>
 * Grouping happens before projection, and the grouping field is retained in every projected item. The
 * obvious alternative — project first, then group — silently collapses everything into one bucket as
 * soon as {@code select} omits the group field, which produces a confidently wrong answer instead of
 * an error.
 * </p>
 */
public final class ResultShaper {

    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * Applies the shaping rules.
     *
     * @param structured payload returned by the wrapped tool
     * @param output shaping rules
     * @return the payload to expose to the caller
     */
    public ObjectNode shape(Object structured, DeclarativeConfig.Output output) {
        JsonNode root = mapper.valueToTree(structured);
        String sourceField = resolveSource(root, output);
        List<JsonNode> items = readItems(root, sourceField);
        int total = items.size();

        List<JsonNode> ordered = new ArrayList<>(items);
        if (!output.sort().isEmpty()) {
            ordered.sort(comparator(output.sort()));
        }

        Map<String, List<JsonNode>> groups = null;
        List<JsonNode> finalItems;
        if (output.groupBy() != null && !output.groupBy().isBlank()) {
            groups = new LinkedHashMap<>();
            for (JsonNode item : ordered) {
                String key = groupKey(item, output.groupBy());
                groups.computeIfAbsent(key, k -> new ArrayList<>()).add(project(item, output));
            }
            int perGroup = output.limitPerGroup() == null || output.limitPerGroup() <= 0 ? Integer.MAX_VALUE
                    : output.limitPerGroup();
            for (Map.Entry<String, List<JsonNode>> entry : groups.entrySet()) {
                if (entry.getValue().size() > perGroup) {
                    entry.setValue(new ArrayList<>(entry.getValue().subList(0, perGroup)));
                }
            }
            finalItems = new ArrayList<>();
            for (List<JsonNode> bucket : groups.values()) {
                finalItems.addAll(bucket);
            }
        }
        else {
            finalItems = new ArrayList<>(ordered.size());
            for (JsonNode item : ordered) {
                finalItems.add(project(item, output));
            }
        }

        // Global truncation applies to the flat list only; per-group lists were already bounded by
        // limitPerGroup and trimming them again would misrepresent the grouping.
        if (output.limit() != null && output.limit() > 0 && finalItems.size() > output.limit()) {
            finalItems = new ArrayList<>(finalItems.subList(0, output.limit()));
        }

        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        payload.put("source", sourceField == null ? "" : sourceField);
        payload.put("total", total);

        ArrayNode itemsNode = payload.putArray("items");
        finalItems.forEach(itemsNode::add);

        if (groups != null) {
            ObjectNode groupsNode = payload.putObject("groups");
            groups.forEach((key, value) -> {
                ArrayNode array = groupsNode.putArray(key);
                value.forEach(array::add);
            });
        }

        payload.put("returned", finalItems.size());
        payload.put("truncated", finalItems.size() < total);
        return payload;
    }

    private String resolveSource(JsonNode root, DeclarativeConfig.Output output) {
        if (output.source() != null && !output.source().isBlank()) {
            return root.path(output.source()).isArray() ? output.source() : null;
        }
        String best = null;
        int bestSize = -1;
        for (Map.Entry<String, JsonNode> entry : root.properties()) {
            if (entry.getValue().isArray() && entry.getValue().size() > bestSize) {
                best = entry.getKey();
                bestSize = entry.getValue().size();
            }
        }
        return best;
    }

    private List<JsonNode> readItems(JsonNode root, String sourceField) {
        List<JsonNode> items = new ArrayList<>();
        if (sourceField == null) {
            return items;
        }
        for (JsonNode item : root.path(sourceField)) {
            items.add(item);
        }
        return items;
    }

    private String groupKey(JsonNode item, String field) {
        JsonNode key = item.get(field);
        return key == null || key.isNull() ? "(未分组)" : key.asText();
    }

    private JsonNode project(JsonNode item, DeclarativeConfig.Output output) {
        if (output.select().isEmpty() || !item.isObject()) {
            // Scalar arrays cannot be projected; keep them rather than emptying the result.
            return item;
        }
        ObjectNode projected = JsonNodeFactory.instance.objectNode();
        for (String field : output.select()) {
            JsonNode value = item.get(field);
            if (value != null) {
                projected.set(field, value);
            }
        }
        // The grouping field is part of the answer's structure, not just of the sort order.
        if (output.groupBy() != null && !output.groupBy().isBlank() && !projected.has(output.groupBy())) {
            JsonNode value = item.get(output.groupBy());
            if (value != null) {
                projected.set(output.groupBy(), value);
            }
        }
        return projected;
    }

    private Comparator<JsonNode> comparator(List<DeclarativeConfig.Sort> keys) {
        return (left, right) -> {
            for (DeclarativeConfig.Sort key : keys) {
                int compared = compareValues(left.get(key.field()), right.get(key.field()));
                if (compared != 0) {
                    return key.descending() ? -compared : compared;
                }
            }
            return 0;
        };
    }

    private static int compareValues(JsonNode left, JsonNode right) {
        if (left == null || left.isNull()) {
            return right == null || right.isNull() ? 0 : -1;
        }
        if (right == null || right.isNull()) {
            return 1;
        }
        if (left.isNumber() && right.isNumber()) {
            return Double.compare(left.asDouble(), right.asDouble());
        }
        return left.asText().compareTo(right.asText());
    }
}

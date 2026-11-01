package io.github.springmcp.server.dev;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

/**
 * Turns a tool result into a form that can be compared across machines and runs.
 *
 * <p>
 * Three things have to be erased before a digest means anything: absolute paths (they differ per
 * checkout), build timing (it differs per run), and array order (streams and maps do not promise it).
 * Everything else — including line and column numbers — is deliberately kept: the repository commit
 * is pinned in the manifest, so a line number that moves is a real behaviour change and exactly what
 * the baseline exists to catch.
 * </p>
 */
public final class BaselineNormaliser {

    /** Fields that change on every build and carry no behavioural meaning. */
    private static final Set<String> VOLATILE = Set.of("builtAtEpochMillis", "buildDurationMs", "generation");

    private BaselineNormaliser() {
    }

    /**
     * Normalises a result tree.
     * @param node raw JSON produced by a tool
     * @param root project root, replaced with {@code <root>} in every string
     * @return a new tree, sorted and stripped of volatile fields
     */
    public static JsonNode normalise(JsonNode node, String root) {
        String prefix = root.replace('\\', '/');
        if (prefix.endsWith("/")) {
            prefix = prefix.substring(0, prefix.length() - 1);
        }
        return normalise(node, prefix, root);
    }

    private static JsonNode normalise(JsonNode node, String prefix, String rawRoot) {
        if (node == null || node.isNull()) {
            return JsonNodeFactory.instance.nullNode();
        }
        if (node.isObject()) {
            ObjectNode out = JsonNodeFactory.instance.objectNode();
            for (Map.Entry<String, JsonNode> entry : node.properties()) {
                if (!VOLATILE.contains(entry.getKey())) {
                    out.set(entry.getKey(), normalise(entry.getValue(), prefix, rawRoot));
                }
            }
            return out;
        }
        if (node.isArray()) {
            List<JsonNode> items = new ArrayList<>();
            for (JsonNode item : node) {
                items.add(normalise(item, prefix, rawRoot));
            }
            // Order-independent comparison: two runs that differ only in map iteration order must
            // produce the same digest.
            items.sort(Comparator.comparing(JsonNode::toString));
            ArrayNode out = JsonNodeFactory.instance.arrayNode();
            items.forEach(out::add);
            return out;
        }
        if (node.isTextual()) {
            return TextNode.valueOf(scrub(node.asText(), prefix, rawRoot));
        }
        return node;
    }

    private static String scrub(String text, String prefix, String rawRoot) {
        String scrubbed = text.replace('\\', '/');
        if (!prefix.isEmpty()) {
            scrubbed = scrubbed.replace(prefix, "<root>");
        }
        if (!scrubbed.contains("<root>") && !rawRoot.isEmpty()) {
            String forwardSlashRoot = rawRoot.replace('\\', '/');
            scrubbed = scrubbed.replace(forwardSlashRoot, "<root>");
        }
        return scrubbed;
    }

    /**
     * Computes a stable SHA-256 over a normalised tree.
     * @param normalised output of {@link #normalise(JsonNode, String)}
     * @return {@code sha256:<hex>}
     */
    public static String digest(JsonNode normalised) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            byte[] hash = sha256.digest(normalised.toString().getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(hash);
        }
        catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /**
     * Finds the largest array directly under a result object.
     *
     * <p>
     * Used to report a readable item count next to the digest: a digest tells you <em>something</em>
     * changed, a count tells you whether it grew, shrank or disappeared.
     * </p>
     * @param data the tool's {@code data} object
     * @return field name of the largest array, or {@code null} when there is none
     */
    public static String largestArrayField(JsonNode data) {
        if (data == null || !data.isObject()) {
            return null;
        }
        String best = null;
        int bestSize = -1;
        for (Map.Entry<String, JsonNode> entry : data.properties()) {
            if (entry.getValue().isArray() && entry.getValue().size() > bestSize) {
                best = entry.getKey();
                bestSize = entry.getValue().size();
            }
        }
        return best;
    }

    /**
     * Extracts the first and last few items of an array, for a human readable diff.
     * @param data the tool's {@code data} object
     * @param field array field name, may be {@code null}
     * @param perSide how many items to keep from each end
     * @return sample items, empty when the array is missing
     */
    public static List<JsonNode> sample(JsonNode data, String field, int perSide) {
        if (data == null || field == null || !data.path(field).isArray()) {
            return List.of();
        }
        ArrayNode array = (ArrayNode) data.path(field);
        if (array.size() <= perSide * 2) {
            List<JsonNode> all = new ArrayList<>();
            array.forEach(all::add);
            return all;
        }
        List<JsonNode> out = new ArrayList<>();
        for (int i = 0; i < perSide; i++) {
            out.add(array.get(i));
        }
        for (int i = array.size() - perSide; i < array.size(); i++) {
            out.add(array.get(i));
        }
        return out;
    }
}

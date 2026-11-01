package io.github.springmcp.server.dev;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.springmcp.server.ProjectCatalog;
import io.github.springmcp.server.builtin.BuiltinTools;
import io.github.springmcp.server.plugin.DefaultPluginContext;
import io.github.springmcp.spi.PluginContext;
import io.github.springmcp.spi.PluginLogger;
import io.github.springmcp.spi.ToolInvocation;
import io.github.springmcp.spi.ToolOutcome;
import io.github.springmcp.spi.ToolProvider;
import io.github.springmcp.spi.api.CoreAnalyzer;
import io.github.springmcp.spi.api.IndexOptions;

/**
 * Runs the frozen tool calls of the baseline and turns the results into comparable snapshots.
 *
 * <p>
 * Shared by {@code --dev baseline} (which writes the golden files) and {@code BaselineTest} (which
 * compares against them). Sharing the code is the point: if the generator and the verifier
 * normalised differently, the baseline would go green while behaviour changed.
 * </p>
 */
public final class BaselineRunner {

    private static final int SAMPLE_PER_SIDE = 20;

    private final CoreAnalyzer analyzer;

    private final boolean includeTests;

    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * @param analyzer analysis facade
     * @param includeTests whether test sources are indexed, normally {@code false}
     */
    public BaselineRunner(CoreAnalyzer analyzer, boolean includeTests) {
        this.analyzer = analyzer;
        this.includeTests = includeTests;
    }

    /**
     * A frozen observation of one tool call.
     *
     * @param digest stable hash of the normalised result; the actual comparison key
     * @param arrayField largest array under {@code data}, for readability
     * @param size number of items in that array
     * @param sample first and last items, so a failure can be eyeballed
     * @param note set when the call failed, e.g. {@code "error:CLASS_NOT_FOUND"}
     */
    public record ToolSnapshot(String digest, String arrayField, int size, List<JsonNode> sample, String note) {
    }

    /**
     * Executes every query of a repository.
     * @param spec repository and its frozen calls
     * @param repoDir checked out working copy
     * @return tool name to snapshot, in manifest order
     */
    public Map<String, ToolSnapshot> run(BaselineSpec spec, Path repoDir) {
        // Absolute and normalised, because the analyser reports absolute paths. Handing a relative
        // root to the normaliser leaves the working directory baked into the digest, which would make
        // the baseline differ between machines or even between invocations from different folders.
        Path root = repoDir.toAbsolutePath().normalize();

        PluginContext context = new DefaultPluginContext(analyzer, PluginLogger.noop(), root, root);
        Map<String, ToolProvider> tools = BuiltinTools.byName(analyzer, ProjectCatalog.single(root),
                IndexOptions.defaults().withIncludeTests(includeTests));

        Map<String, ToolSnapshot> snapshots = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Object>> query : spec.queries().entrySet()) {
            String toolName = query.getKey();
            ToolProvider provider = tools.get(toolName);
            if (provider == null) {
                throw new IllegalStateException("manifest 引用了不存在的工具: " + toolName);
            }

            Map<String, Object> args = new LinkedHashMap<>(query.getValue());
            args.putIfAbsent("projectRoot", root.toString());
            ToolOutcome outcome = provider.invoke(new ToolInvocation(args, context));

            snapshots.put(toolName, switch (outcome) {
                case ToolOutcome.Success success -> snapshotOf(success, root);
                // A failure is frozen too: turning a working query into an error is exactly the kind
                // of regression the baseline must catch, so it cannot simply be skipped.
                case ToolOutcome.Failure failure -> new ToolSnapshot(
                        "error:" + failure.failure().code().name(), null, 0, List.of(),
                        failure.failure().getMessage());
            });
        }
        return snapshots;
    }

    private ToolSnapshot snapshotOf(ToolOutcome.Success success, Path repoDir) {
        JsonNode normalised = BaselineNormaliser.normalise(mapper.valueToTree(success.structured()),
                repoDir.toString());
        String field = BaselineNormaliser.largestArrayField(normalised);
        int size = field == null ? 0 : normalised.path(field).size();
        return new ToolSnapshot(BaselineNormaliser.digest(normalised), field, size,
                BaselineNormaliser.sample(normalised, field, SAMPLE_PER_SIDE), null);
    }

    /**
     * Renders snapshots as the on-disk golden document.
     * @param spec repository
     * @param snapshots result of {@link #run}
     * @return JSON document
     */
    public ObjectNode toJson(BaselineSpec spec, Map<String, ToolSnapshot> snapshots) {
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        root.put("repo", spec.name());
        root.put("commit", spec.commit());
        root.put("stack", spec.stack());
        ObjectNode tools = root.putObject("tools");
        snapshots.forEach((name, snapshot) -> {
            ObjectNode node = tools.putObject(name);
            node.put("digest", snapshot.digest());
            if (snapshot.arrayField() != null) {
                node.put("arrayField", snapshot.arrayField());
            }
            node.put("size", snapshot.size());
            if (snapshot.note() != null) {
                node.put("note", snapshot.note());
            }
            ArrayNode sample = node.putArray("sample");
            snapshot.sample().forEach(sample::add);
        });
        return root;
    }

    /**
     * Compares a stored golden document against a fresh run.
     * @param golden parsed golden file
     * @param actual fresh snapshots
     * @return human readable differences, empty when the run matches
     */
    public static List<String> compare(JsonNode golden, Map<String, ToolSnapshot> actual) {
        List<String> differences = new ArrayList<>();
        JsonNode goldenTools = golden.path("tools");

        for (Map.Entry<String, ToolSnapshot> entry : actual.entrySet()) {
            String name = entry.getKey();
            ToolSnapshot snapshot = entry.getValue();
            JsonNode expected = goldenTools.get(name);

            if (expected == null) {
                differences.add(name + ": golden 里没有这一项（manifest 新增了查询？请重新生成 golden）");
                continue;
            }
            if (!expected.path("digest").asText().equals(snapshot.digest())) {
                differences.add(name + ": digest 变化 " + expected.path("digest").asText()
                        + " -> " + snapshot.digest()
                        + "（size " + expected.path("size").asInt() + " -> " + snapshot.size() + "）");
            }
            else if (expected.path("size").asInt() != snapshot.size()) {
                differences.add(name + ": digest 相同但 size 不同（不应发生，请检查规范化逻辑）");
            }
        }

        var names = goldenTools.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            if (!actual.containsKey(name)) {
                differences.add(name + ": golden 里有但本次没有运行（manifest 删了查询？）");
            }
        }
        return differences;
    }

    /**
     * Writes a golden document.
     * @param golden document from {@link #toJson}
     * @param file target file; parent directories are created
     * @throws java.io.IOException when writing fails
     */
    public void write(JsonNode golden, Path file) throws java.io.IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(golden) + "\n",
                java.nio.charset.StandardCharsets.UTF_8);
    }
}

package io.github.springmcp.server.declarative;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

/**
 * Reads the declarative extension file.
 *
 * <p>
 * Two levels of error isolation, mirroring how jar plugins are handled: a file that cannot be parsed
 * costs the whole file (there is nothing sensible to salvage), while a single malformed entry costs
 * only that entry and is reported. The server always starts — the worst case is zero extra tools,
 * which is reported once and never blocks the built-in ones.
 * </p>
 *
 * <p>
 * YAML or JSON is chosen by extension. Both are supported because the file is hand edited, and
 * forcing one syntax on everyone buys nothing.
 * </p>
 */
public final class ConfigLoader {

    /** Schema version understood by this build. */
    public static final int SUPPORTED_VERSION = 1;

    private static final List<String> CANDIDATE_NAMES = List.of("spring-mcp.yaml", "spring-mcp.yml",
            "spring-mcp.json");

    private ConfigLoader() {
    }

    /**
     * Result of attempting to load the configuration.
     *
     * @param config parsed configuration, never {@code null}
     * @param file the file that was read, {@code null} when none existed
     * @param problems human readable issues; non-empty means some entries were skipped
     */
    public record LoadResult(DeclarativeConfig config, Path file, List<String> problems) {

        public LoadResult {
            problems = problems == null ? List.of() : List.copyOf(problems);
        }

        /** @return whether a file was found at all */
        public boolean found() {
            return file != null;
        }
    }

    /**
     * Resolves and loads the configuration.
     * @param explicit value of {@code --config}, may be {@code null}
     * @param pluginsDir directory to look in when nothing explicit was given
     * @return the result; {@link LoadResult#found()} is {@code false} when no file exists
     */
    public static LoadResult load(Path explicit, Path pluginsDir) {
        Path file = resolve(explicit, pluginsDir);
        if (file == null) {
            return new LoadResult(DeclarativeConfig.empty(), null, List.of());
        }
        return parse(file);
    }

    /**
     * Finds the configuration file.
     * @param explicit value of {@code --config}, may be {@code null}
     * @param pluginsDir directory to search, may be {@code null}
     * @return the file, or {@code null} when there is none
     */
    static Path resolve(Path explicit, Path pluginsDir) {
        if (explicit != null) {
            return explicit;
        }
        String env = System.getenv("SPRING_MCP_CONFIG");
        if (env != null && !env.isBlank()) {
            return Path.of(env.trim());
        }
        if (pluginsDir == null) {
            return null;
        }
        for (String name : CANDIDATE_NAMES) {
            Path candidate = pluginsDir.resolve(name);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * Parses one file.
     * @param file YAML or JSON configuration
     * @return the result, with problems instead of exceptions
     */
    public static LoadResult parse(Path file) {
        List<String> problems = new ArrayList<>();
        if (!Files.isRegularFile(file)) {
            problems.add("配置文件不存在: " + file);
            return new LoadResult(DeclarativeConfig.empty(), file, problems);
        }

        JsonNode root;
        try {
            root = mapperFor(file).readTree(Files.readString(file, StandardCharsets.UTF_8));
        }
        catch (IOException | RuntimeException e) {
            problems.add("配置解析失败（整份文件被忽略）: " + e.getMessage());
            return new LoadResult(DeclarativeConfig.empty(), file, problems);
        }

        if (root == null || root.isNull() || root.isMissingNode()) {
            return new LoadResult(DeclarativeConfig.empty(), file, problems);
        }

        int version = root.path("version").asInt(SUPPORTED_VERSION);
        if (version != SUPPORTED_VERSION) {
            problems.add("配置 version=" + version + " 不受支持，本版本只认 " + SUPPORTED_VERSION + "；整份文件被忽略");
            return new LoadResult(DeclarativeConfig.empty(), file, problems);
        }

        ObjectMapper mapper = mapperFor(file);
        List<DeclarativeConfig.ToolDef> tools = readEach(root.path("tools"), DeclarativeConfig.ToolDef.class, mapper,
                "tools", problems);
        List<DeclarativeConfig.ExternalDef> external = readEach(root.path("external"),
                DeclarativeConfig.ExternalDef.class, mapper, "external", problems);

        return new LoadResult(new DeclarativeConfig(tools, external), file, problems);
    }

    private static <T> List<T> readEach(JsonNode array, Class<T> type, ObjectMapper mapper, String section,
            List<String> problems) {
        List<T> out = new ArrayList<>();
        if (array == null || array.isMissingNode() || array.isNull()) {
            return out;
        }
        if (!array.isArray()) {
            problems.add(section + " 必须是数组，已忽略");
            return out;
        }
        int index = 0;
        for (JsonNode node : array) {
            String label = section + "[" + index + "]";
            try {
                T value = mapper.treeToValue(node, type);
                out.add(value);
                String name = node.path("name").asText("");
                if (!name.isBlank()) {
                    label = label + " '" + name + "'";
                }
            }
            catch (Exception e) {
                problems.add(label + " 解析失败，已跳过: " + rootCause(e));
            }
            index++;
        }
        return out;
    }

    private static String rootCause(Throwable e) {
        Throwable current = e;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return current.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }

    /**
     * @param file configuration file
     * @return a mapper matching the extension
     */
    static ObjectMapper mapperFor(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".json") ? new ObjectMapper() : new ObjectMapper(new YAMLFactory());
    }
}

package io.github.springmcp.server.declarative;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.springmcp.server.external.ExternalToolProvider;
import io.github.springmcp.spi.ToolDescriptor;
import io.github.springmcp.spi.ToolProvider;

/**
 * Turns a parsed configuration into tools, collecting one problem per rejected entry.
 *
 * <p>
 * Nothing here throws. A bad declaration loses exactly that tool and appends a line to the problem
 * list, matching how jar plugins are isolated — the server's job is to keep serving, not to punish a
 * typo in an optional extension file.
 * </p>
 */
public final class DeclarativeTools {

    /** Interpreters that would turn "pass an argument list" back into shell evaluation. */
    private static final Set<String> SHELL_BASENAMES = Set.of("sh", "bash", "zsh", "dash", "cmd", "cmd.exe",
            "powershell", "powershell.exe", "pwsh", "pwsh.exe");

    private DeclarativeTools() {
    }

    /**
     * Outcome of building the declarative tools.
     *
     * @param tools accepted tools, in config order
     * @param problems one line per rejected or skipped entry
     */
    public record BuildResult(List<ToolProvider> tools, List<String> problems) {

        public BuildResult {
            tools = tools == null ? List.of() : List.copyOf(tools);
            problems = problems == null ? List.of() : List.copyOf(problems);
        }
    }

    /**
     * Builds every tool declared in the configuration.
     *
     * @param config parsed configuration
     * @param builtins built-in tools keyed by name, used as delegation targets
     * @param defaultProjectRoot default root handed to external tools
     * @param extensionBase directory holding the config file, used to resolve external working dirs
     * @param allowExternal whether external process tools may be registered
     * @return tools plus problems
     */
    public static BuildResult build(DeclarativeConfig config, Map<String, ToolProvider> builtins,
            Path defaultProjectRoot, Path extensionBase, boolean allowExternal) {

        ObjectMapper mapper = new ObjectMapper();
        List<ToolProvider> tools = new ArrayList<>();
        List<String> problems = new ArrayList<>();

        for (DeclarativeConfig.ToolDef def : config.tools()) {
            try {
                tools.add(declarativeTool(def, builtins, mapper));
            }
            catch (SchemaDeriver.SchemaException e) {
                problems.add("tools '" + def.name() + "' 已跳过: " + e.getMessage());
            }
            catch (RuntimeException e) {
                problems.add("tools '" + def.name() + "' 已跳过: " + e.getMessage());
            }
        }

        int externalTotal = config.external().size();
        int externalSkipped = 0;
        for (DeclarativeConfig.ExternalDef def : config.external()) {
            String rejection = validateExternal(def);
            if (rejection != null) {
                problems.add("external '" + def.name() + "' 已跳过: " + rejection);
                continue;
            }
            if (!allowExternal) {
                externalSkipped++;
                continue;
            }
            try {
                tools.add(new ExternalToolProvider(def, defaultProjectRoot, extensionBase, true));
            }
            catch (RuntimeException e) {
                problems.add("external '" + def.name() + "' 已跳过: " + e.getMessage());
            }
        }
        if (externalSkipped > 0) {
            problems.add("发现 " + externalSkipped + "/" + externalTotal
                    + " 个外部进程工具，未启用 --allow-external-commands，已全部跳过（该开关会让配置文件可执行）");
        }

        return new BuildResult(tools, problems);
    }

    private static ToolProvider declarativeTool(DeclarativeConfig.ToolDef def, Map<String, ToolProvider> builtins,
            ObjectMapper mapper) throws SchemaDeriver.SchemaException {

        if (def.name() == null || def.name().isBlank()) {
            throw new SchemaDeriver.SchemaException("缺少 name");
        }
        if (def.query() == null || def.query().isBlank()) {
            throw new SchemaDeriver.SchemaException("缺少 query，可用: " + String.join(", ", builtins.keySet()));
        }
        ToolProvider delegate = builtins.get(def.query());
        if (delegate == null) {
            throw new SchemaDeriver.SchemaException("query '" + def.query() + "' 不存在，可用: "
                    + String.join(", ", builtins.keySet()));
        }

        String schema = SchemaDeriver.derive(delegate.descriptor().inputSchemaJson(), def.query(), def.fixed(),
                def.expose(), mapper);

        String description = def.description();
        if (description == null || description.isBlank()) {
            throw new SchemaDeriver.SchemaException("缺少 description（它会直接影响模型能否选对工具）");
        }

        ToolDescriptor descriptor = ToolDescriptor.builder(def.name())
            .title(def.title() == null || def.title().isBlank() ? def.name() : def.title())
            .description(description + describeFixed(def.fixed()))
            .inputSchemaJson(schema)
            .build();

        return new DeclarativeTool(descriptor, delegate, def.fixed(), def.expose(), def.output(), def.summary());
    }

    /**
     * Renders pinned arguments as a sentence appended to the tool description.
     *
     * <p>
     * The schema deliberately omits them, so this is the only place a caller can learn what the tool
     * has fixed — and it matters: "GET endpoints under /owners" and "endpoints" need different tool
     * choices.
     * </p>
     *
     * @param fixed pinned arguments, may be empty
     * @return a sentence, or an empty string when nothing is pinned
     */
    private static String describeFixed(Map<String, Object> fixed) {
        if (fixed.isEmpty()) {
            return "";
        }
        String separator = System.lineSeparator();
        StringBuilder sb = new StringBuilder(separator + separator + "固定参数（调用方不可更改）: ");
        boolean first = true;
        for (Map.Entry<String, Object> entry : fixed.entrySet()) {
            if (!first) {
                sb.append(", ");
            }
            first = false;
            sb.append(entry.getKey()).append('=').append(entry.getValue());
        }
        return sb.toString();
    }

    /**
     * Rejects external definitions that cannot be run safely.
     * @param def definition
     * @return a reason, or {@code null} when acceptable
     */
    static String validateExternal(DeclarativeConfig.ExternalDef def) {
        if (def.name() == null || def.name().isBlank()) {
            return "缺少 name";
        }
        if (def.command().isEmpty()) {
            return "缺少 command 数组";
        }
        String executable = def.command().get(0);
        if (executable == null || executable.isBlank()) {
            return "command[0] 为空";
        }
        String base = executable.replace('\\', '/');
        int slash = base.lastIndexOf('/');
        String basename = (slash < 0 ? base : base.substring(slash + 1)).toLowerCase(Locale.ROOT);
        if (SHELL_BASENAMES.contains(basename)) {
            return "command[0] 不允许是 shell（" + basename + "），参数会被当成命令执行；请直接调用目标程序";
        }
        if (def.description() == null || def.description().isBlank()) {
            return "缺少 description";
        }
        return null;
    }

    /**
     * Renders the schema of a declarative tool without registering it, for diagnostics.
     * @param def declaration
     * @param builtins built-in tools
     * @return the derived schema text
     * @throws SchemaDeriver.SchemaException when inconsistent
     */
    public static String previewSchema(DeclarativeConfig.ToolDef def, Map<String, ToolProvider> builtins)
            throws SchemaDeriver.SchemaException {
        ToolProvider delegate = builtins.get(def.query());
        if (delegate == null) {
            throw new SchemaDeriver.SchemaException("query '" + def.query() + "' 不存在");
        }
        return SchemaDeriver.derive(delegate.descriptor().inputSchemaJson(), def.query(), def.fixed(), def.expose(),
                new ObjectMapper());
    }
}

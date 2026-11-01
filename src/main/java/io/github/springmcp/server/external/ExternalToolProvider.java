package io.github.springmcp.server.external;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.springmcp.server.declarative.DeclarativeConfig;
import io.github.springmcp.spi.Args;
import io.github.springmcp.spi.ErrorCode;
import io.github.springmcp.spi.JsonSchemas;
import io.github.springmcp.spi.ToolDescriptor;
import io.github.springmcp.spi.ToolFailure;
import io.github.springmcp.spi.ToolInvocation;
import io.github.springmcp.spi.ToolOutcome;
import io.github.springmcp.spi.ToolProvider;

/**
 * A tool implemented by a child process in any language.
 *
 * <p>
 * This is the escape hatch for extensions that need genuinely new logic: the declarative tools cover
 * "a different view of an existing analysis", this covers everything else without requiring the author
 * to write Java. The price is per-call process startup, which is documented rather than hidden.
 * </p>
 *
 * <p>
 * Registration is gated by {@code --allow-external-commands}, which defaults to off. Enabling it means
 * the configuration file can execute commands, so that decision must be explicit and conscious — it is
 * checked again here, not just at registration time.
 * </p>
 */
public final class ExternalToolProvider implements ToolProvider {

    private final DeclarativeConfig.ExternalDef definition;

    private final ToolDescriptor descriptor;

    private final Path defaultProjectRoot;

    private final Path extensionBase;

    private final boolean allowed;

    private final ExternalProcessRunner runner = new ExternalProcessRunner();

    private final Set<String> exposed;

    /**
     * @param definition tool definition
     * @param defaultProjectRoot project root to use when the caller omits one
     * @param extensionBase directory holding the config file, used to resolve the working directory
     * @param allowed whether external commands were enabled on the command line
     * @throws IllegalArgumentException when the definition cannot form a valid tool
     */
    public ExternalToolProvider(DeclarativeConfig.ExternalDef definition, Path defaultProjectRoot,
            Path extensionBase, boolean allowed) {
        this.definition = definition;
        this.defaultProjectRoot = defaultProjectRoot;
        this.extensionBase = extensionBase;
        this.allowed = allowed;
        Set<String> names = new LinkedHashSet<>();
        if (definition.expose() != null) {
            definition.expose().forEach(param -> names.add(param.name()));
        }
        this.exposed = Set.copyOf(names);
        this.descriptor = ToolDescriptor.builder(definition.name())
            .title(definition.title() == null || definition.title().isBlank() ? definition.name()
                    : definition.title())
            .description(definition.description() == null ? "" : definition.description())
            .inputSchemaJson(inputSchema())
            .build();
    }

    private String inputSchema() {
        if (definition.inputSchemaJson() != null && !definition.inputSchemaJson().isBlank()) {
            return definition.inputSchemaJson();
        }
        JsonSchemas.SchemaBuilder schema = JsonSchemas.object().title(definition.name());
        definition.expose().forEach(param -> schema.string(param.name(),
                param.description() == null ? "" : param.description(), false, null));
        return schema.buildJson();
    }

    @Override
    public ToolDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public ToolOutcome invoke(ToolInvocation invocation) {
        if (!allowed) {
            return ToolOutcome.fail(ToolFailure.of(ErrorCode.PLUGIN_ERROR,
                    "外部进程工具 '" + definition.name() + "' 未启用",
                    "启动服务时加 --allow-external-commands 才会执行配置里的 command"));
        }

        Args args = invocation.args();
        Map<String, Object> forwarded = new LinkedHashMap<>();
        for (String name : exposed) {
            if (args.has(name)) {
                forwarded.put(name, args.raw().get(name));
            }
        }

        Path root;
        try {
            Path explicit = args.optionalExistingDirectory("projectRoot", defaultProjectRoot);
            if (explicit == null) {
                return ToolOutcome.fail(ToolFailure.of(ErrorCode.INVALID_ARGUMENT, "缺少工程参数",
                        "请传 'projectRoot'（绝对路径），或在启动服务时用 --project=<dir> 设默认值"));
            }
            root = explicit;
        }
        catch (ToolFailure failure) {
            return ToolOutcome.fail(failure);
        }

        Map<String, Object> context = new LinkedHashMap<>();
        context.put("pluginsDirectory", String.valueOf(invocation.context().pluginsDirectory()));
        return runner.run(definition, forwarded, root, extensionBase, context);
    }

    /** @return the definition this provider was built from */
    public DeclarativeConfig.ExternalDef definition() {
        return definition;
    }

    /** @return exposed argument names */
    public List<String> exposedNames() {
        return List.copyOf(exposed);
    }
}

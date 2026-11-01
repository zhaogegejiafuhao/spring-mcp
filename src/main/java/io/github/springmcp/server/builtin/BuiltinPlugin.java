package io.github.springmcp.server.builtin;

import java.util.List;

import io.github.springmcp.server.ProjectCatalog;
import io.github.springmcp.spi.PluginContext;
import io.github.springmcp.spi.SpringMcpPlugin;
import io.github.springmcp.spi.ToolFailure;
import io.github.springmcp.spi.ToolProvider;
import io.github.springmcp.spi.api.IndexOptions;

/**
 * The seven tools that ship with spring-mcp, packaged as a plugin.
 *
 * <p>
 * They are registered through exactly the same path as a third-party plugin: same SPI, same
 * {@link PluginContext}, same {@code ToolRegistrar} guards. That is intentional pressure on the
 * design — if the SPI cannot express the tools in the box, it cannot express anyone else's either.
 * </p>
 */
public final class BuiltinPlugin implements SpringMcpPlugin {

    /** Plugin id used in logs and diagnostics. */
    public static final String ID = "io.github.springmcp.builtin";

    private final IndexOptions baseOptions;

    private final ProjectCatalog catalog;

    private List<ToolProvider> tools = List.of();

    /**
     * Creates the plugin with production defaults; the default root comes from the
     * {@link PluginContext} instead. Sufficient for tests and for embedding.
     * @param baseOptions options every query starts from
     */
    public BuiltinPlugin(IndexOptions baseOptions) {
        this(baseOptions, ProjectCatalog.empty());
    }

    /**
     * @param baseOptions options every query starts from
     * @param catalog configured projects, used to resolve the {@code project} alias
     */
    public BuiltinPlugin(IndexOptions baseOptions, ProjectCatalog catalog) {
        this.baseOptions = baseOptions == null ? IndexOptions.defaults() : baseOptions;
        this.catalog = catalog == null ? ProjectCatalog.empty() : catalog;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String version() {
        return "0.1.0";
    }

    @Override
    public String displayName() {
        return "spring-mcp built-in tools";
    }

    @Override
    public void initialize(PluginContext context) throws ToolFailure {
        // An empty catalog plus a context-provided default root is a legitimate setup (tests, or an
        // embedder that only ever analyses one project); promote it to a one-entry catalog so the
        // tools do not have to special-case it.
        ProjectCatalog effective = catalog;
        if (effective.isEmpty() && context.defaultProjectRoot() != null) {
            effective = ProjectCatalog.single(context.defaultProjectRoot());
        }
        this.tools = BuiltinTools.create(context.analyzer(), effective, baseOptions);
    }

    @Override
    public List<ToolProvider> tools() {
        return tools;
    }
}

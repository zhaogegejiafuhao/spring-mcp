package io.github.springmcp.server.builtin;

import java.util.List;

import io.github.springmcp.server.ProjectCatalog;
import io.github.springmcp.spi.PluginContext;
import io.github.springmcp.spi.SpringMcpPlugin;
import io.github.springmcp.spi.ToolProvider;
import io.github.springmcp.spi.api.IndexOptions;

/**
 * Contributes the discovery tools: {@code list-projects} and {@code list-modules}.
 *
 * <p>
 * A separate plugin rather than extra tools inside {@link BuiltinPlugin} because the built-in set is
 * a documented contract ("these five tools answer questions about your code"), while these two
 * answer questions about the server's configuration and the shape of the repository. Keeping them
 * apart also preserves the {@code BuiltinToolsTest} assertion that the built-in plugin contributes
 * exactly five tools, which is the check that would otherwise have to be loosened.
 * </p>
 */
public final class ProjectsPlugin implements SpringMcpPlugin {

    /** Plugin id used in logs and diagnostics. */
    public static final String ID = "io.github.springmcp.projects";

    private final ProjectCatalog catalog;

    private final IndexOptions baseOptions;

    private List<ToolProvider> tools = List.of();

    /**
     * @param catalog configured projects
     * @param baseOptions options every query starts from
     */
    public ProjectsPlugin(ProjectCatalog catalog, IndexOptions baseOptions) {
        this.catalog = catalog == null ? ProjectCatalog.empty() : catalog;
        this.baseOptions = baseOptions == null ? IndexOptions.defaults() : baseOptions;
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
        return "spring-mcp project listing";
    }

    @Override
    public void initialize(PluginContext context) {
        this.tools = List.of(new ListProjectsTool(catalog),
                new ListModulesTool(context.analyzer(), catalog, baseOptions));
    }

    @Override
    public List<ToolProvider> tools() {
        return tools;
    }
}

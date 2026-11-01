package io.github.springmcp.server.builtin;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.springmcp.server.ProjectCatalog;
import io.github.springmcp.spi.ToolProvider;
import io.github.springmcp.spi.api.CoreAnalyzer;
import io.github.springmcp.spi.api.IndexOptions;

/**
 * Factory for the seven tools that ship with spring-mcp.
 *
 * <p>
 * Pulled out of {@link BuiltinPlugin} because there are now two consumers: the plugin itself, and
 * declarative tools in the config file, which work by delegating to a built-in query instead of
 * reimplementing it. Sharing one factory keeps the delegation target identical to what the plugin
 * registers — if they diverged, a declarative tool would silently answer differently from the tool
 * it claims to wrap.
 * </p>
 */
public final class BuiltinTools {

    private BuiltinTools() {
    }

    /**
     * Creates the built-in tools in registration order.
     * @param analyzer analysis facade
     * @param catalog configured projects, may be empty
     * @param options options every query starts from, shared so one process builds one index
     * @return fresh tool instances, in a stable order
     */
    public static List<ToolProvider> create(CoreAnalyzer analyzer, ProjectCatalog catalog, IndexOptions options) {
        return List.of(new ListControllersTool(analyzer, catalog, options),
                new FindServiceCallersTool(analyzer, catalog, options),
                new FindImplementationsTool(analyzer, catalog, options),
                new ListMappersTool(analyzer, catalog, options),
                new SearchSymbolTool(analyzer, catalog, options),
                new SearchByAnnotationTool(analyzer, catalog, options),
                new GetFileDependenciesTool(analyzer, catalog, options),
                new ListGatewayRoutesTool(analyzer, catalog, options),
                new ListFeignClientsTool(analyzer, catalog, options));
    }

    /**
     * Creates the built-in tools keyed by descriptor name, for callers that need to look one up.
     * @param analyzer analysis facade
     * @param catalog configured projects, may be empty
     * @param options options every query starts from
     * @return tools keyed by tool name, in registration order
     */
    public static Map<String, ToolProvider> byName(CoreAnalyzer analyzer, ProjectCatalog catalog,
            IndexOptions options) {
        Map<String, ToolProvider> byName = new LinkedHashMap<>();
        for (ToolProvider tool : create(analyzer, catalog, options)) {
            byName.put(tool.descriptor().name(), tool);
        }
        return byName;
    }
}

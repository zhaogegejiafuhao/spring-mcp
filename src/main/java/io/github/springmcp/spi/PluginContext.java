package io.github.springmcp.spi;

import java.nio.file.Path;

import io.github.springmcp.spi.api.CoreAnalyzer;

/**
 * Everything a plugin is allowed to touch at runtime.
 *
 * <p>
 * This is the whole capability surface. A plugin gets the read-only analysis facade, a
 * stderr logger and two paths — nothing else. In particular it gets no handle on the index
 * implementation, on JavaParser types, or on the MCP server.
 * </p>
 */
public interface PluginContext {

    /**
     * Read-only source analysis facade. Safe to store and reuse; it is thread-safe and
     * caches indexes per project root.
     * @return analyzer, never {@code null}
     */
    CoreAnalyzer analyzer();

    /**
     * Logger writing to stderr.
     * @return logger, never {@code null}
     */
    PluginLogger logger();

    /**
     * Directory the plugin jar was loaded from. Handy for shipping auxiliary data files next
     * to the jar.
     * @return absolute path, never {@code null}
     */
    Path pluginsDirectory();

    /**
     * Project root configured on the command line ({@code --project}), so tools can fall back
     * to it when the caller omits {@code projectRoot}.
     * @return absolute path, or {@code null} when not configured
     */
    Path defaultProjectRoot();
}

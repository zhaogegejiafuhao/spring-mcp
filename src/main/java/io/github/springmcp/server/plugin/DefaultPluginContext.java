package io.github.springmcp.server.plugin;

import java.nio.file.Path;
import java.util.Objects;

import io.github.springmcp.spi.PluginContext;
import io.github.springmcp.spi.PluginLogger;
import io.github.springmcp.spi.api.CoreAnalyzer;

/**
 * Default {@link PluginContext}: read-only access to the analyzer, a logger, and two paths.
 *
 * @param analyzer analysis facade
 * @param logger stderr logger
 * @param pluginsDirectory directory the plugin jar was loaded from
 * @param defaultProjectRoot project root configured on the command line, may be {@code null}
 */
public record DefaultPluginContext(CoreAnalyzer analyzer, PluginLogger logger, Path pluginsDirectory,
        Path defaultProjectRoot) implements PluginContext {

    public DefaultPluginContext {
        Objects.requireNonNull(analyzer, "analyzer must not be null");
        Objects.requireNonNull(logger, "logger must not be null");
        Objects.requireNonNull(pluginsDirectory, "pluginsDirectory must not be null");
    }
}

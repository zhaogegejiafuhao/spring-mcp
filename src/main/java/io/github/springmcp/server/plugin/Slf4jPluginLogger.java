package io.github.springmcp.server.plugin;

import java.nio.file.Path;

import org.slf4j.Logger;

import io.github.springmcp.spi.PluginLogger;

/**
 * {@link PluginLogger} backed by SLF4J, with the plugin id baked into every message.
 *
 * <p>
 * The id prefix is what makes a multi-plugin server debuggable: a line reading
 * {@code [com.acme.tools] skipped 3 files} is actionable, an unlabelled one is not.
 * </p>
 */
public final class Slf4jPluginLogger implements PluginLogger {

    private final Logger delegate;

    private final String prefix;

    /**
     * @param delegate underlying logger
     * @param pluginId plugin id used as message prefix
     */
    public Slf4jPluginLogger(Logger delegate, String pluginId) {
        this.delegate = delegate;
        this.prefix = "[" + pluginId + "] ";
    }

    @Override
    public void debug(String message, Object... args) {
        if (delegate.isDebugEnabled()) {
            delegate.debug(prefix + message, args);
        }
    }

    @Override
    public void info(String message, Object... args) {
        delegate.info(prefix + message, args);
    }

    @Override
    public void warn(String message, Object... args) {
        delegate.warn(prefix + message, args);
    }

    @Override
    public void warn(String message, Throwable error) {
        delegate.warn(prefix + message, error);
    }

    @Override
    public void error(String message, Object... args) {
        delegate.error(prefix + message, args);
    }

    @Override
    public void error(String message, Throwable error) {
        delegate.error(prefix + message, error);
    }
}

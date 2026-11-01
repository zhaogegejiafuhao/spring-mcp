package io.github.springmcp.spi;

import java.util.List;

/**
 * Entry point discovered by {@link java.util.ServiceLoader}.
 *
 * <p>
 * A plugin jar declares itself in {@code META-INF/services/io.github.springmcp.spi.SpringMcpPlugin}
 * and is dropped into the {@code plugins/} directory. No server recompilation is involved.
 * </p>
 *
 * <p>
 * Lifecycle: the server instantiates the plugin once, calls {@link #initialize(PluginContext)}
 * once, then {@link #tools()} once to register tools. On shutdown {@link #close()} is called.
 * A plugin that throws from any of these is discarded as a unit — it never prevents the
 * server or the other plugins from starting.
 * </p>
 */
public interface SpringMcpPlugin extends AutoCloseable {

    /**
     * Stable, globally unique identifier used in logs and to prefix tool names in reports.
     * Reverse-DNS style is recommended, e.g. {@code "com.acme.mybatis-plus"}.
     * @return plugin id, never {@code null} or blank
     */
    String id();

    /**
     * Plugin version, for diagnostics only.
     * @return version string, never {@code null}
     */
    String version();

    /**
     * SPI major version this plugin was compiled against. Reported so the server can refuse
     * incompatible binaries instead of failing later with {@code NoSuchMethodError}.
     * @return major version, defaults to the version bundled with this SPI jar
     */
    default int spiMajorVersion() {
        return SpiVersion.MAJOR;
    }

    /**
     * Human readable name, defaults to the id.
     * @return display name
     */
    default String displayName() {
        return id();
    }

    /**
     * Called once before {@link #tools()}.
     * @param context runtime capabilities; store it if the tools need it
     * @throws ToolFailure when the plugin cannot work in this environment (missing config,
     * incompatible server). The plugin is then unloaded and reported, without affecting others.
     */
    void initialize(PluginContext context) throws ToolFailure;

    /**
     * Tools contributed by this plugin. Called after {@link #initialize(PluginContext)}.
     * @return tools, may be empty but never {@code null}
     */
    List<ToolProvider> tools();

    /**
     * Releases resources. Default is a no-op.
     */
    @Override
    default void close() {
    }
}

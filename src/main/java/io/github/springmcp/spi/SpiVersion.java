package io.github.springmcp.spi;

/**
 * Plugin SPI compatibility marker.
 *
 * <p>
 * Plugins are compiled against {@code spring-mcp-spi}. When the server loads a plugin it
 * compares the plugin's declared major version with {@link #MAJOR} and refuses to load it
 * on mismatch. Minor versions are additive-only and therefore always compatible.
 * </p>
 *
 * <p>
 * Bumping {@code MAJOR} means an existing plugin binary will no longer be loaded. Do it
 * only for genuinely breaking signature changes: removing a method from {@link ToolProvider},
 * removing or reordering a record component, renaming an {@link ErrorCode}.
 * </p>
 *
 * <p>
 * <em>Adding</em> a record component is a {@code MINOR} change: existing readers keep working
 * because their accessors still exist, and only plugins that construct the record themselves would
 * notice. {@link io.github.springmcp.spi.model.Controllers.Endpoint} gained one this way.
 * </p>
 */
public final class SpiVersion {

    /** Major version of the plugin SPI. Incompatible changes bump this. */
    public static final int MAJOR = 1;

    /** Minor version of the plugin SPI. Additive changes only. */
    public static final int MINOR = 2;

    /** Human readable form, e.g. {@code "1.0"}. */
    public static final String VERSION = MAJOR + "." + MINOR;

    private SpiVersion() {
        // constants holder
    }

    /**
     * Returns whether a plugin compiled against {@code pluginMajor} can run on this server.
     * @param pluginMajor value reported by {@link SpringMcpPlugin#spiMajorVersion()}
     * @return {@code true} when the plugin may be loaded
     */
    public static boolean isCompatible(int pluginMajor) {
        return pluginMajor == MAJOR;
    }
}

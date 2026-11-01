package io.github.springmcp.spi;

/**
 * Minimal logging facade handed to plugins.
 *
 * <p>
 * The SPI must not depend on SLF4J, so plugins get this instead. Everything written here goes
 * to <strong>stderr</strong>: under the STDIO transport, stdout <em>is</em> the JSON-RPC
 * channel, and a single stray line on stdout corrupts the protocol for the whole session.
 * </p>
 */
public interface PluginLogger {

    /**
     * Logs a debug message.
     * @param message text, {@code {}} placeholders are substituted in order
     * @param args optional arguments
     */
    void debug(String message, Object... args);

    /**
     * Logs an informational message.
     * @param message text, {@code {}} placeholders are substituted in order
     * @param args optional arguments
     */
    void info(String message, Object... args);

    /**
     * Logs a warning.
     * @param message text, {@code {}} placeholders are substituted in order
     * @param args optional arguments
     */
    void warn(String message, Object... args);

    /**
     * Logs a warning with an attached throwable.
     * @param message text
     * @param error cause to print a stack trace for
     */
    void warn(String message, Throwable error);

    /**
     * Logs an error.
     * @param message text, {@code {}} placeholders are substituted in order
     * @param args optional arguments
     */
    void error(String message, Object... args);

    /**
     * Logs an error with an attached throwable.
     * @param message text
     * @param error cause to print a stack trace for
     */
    void error(String message, Throwable error);

    /**
     * A logger that discards everything. Useful in tests and as a safe default.
     * @return no-op logger
     */
    static PluginLogger noop() {
        return new PluginLogger() {
            @Override
            public void debug(String message, Object... args) {
            }

            @Override
            public void info(String message, Object... args) {
            }

            @Override
            public void warn(String message, Object... args) {
            }

            @Override
            public void warn(String message, Throwable error) {
            }

            @Override
            public void error(String message, Object... args) {
            }

            @Override
            public void error(String message, Throwable error) {
            }
        };
    }
}

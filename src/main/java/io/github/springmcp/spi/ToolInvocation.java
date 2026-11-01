package io.github.springmcp.spi;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Arguments of one tool call, together with the runtime context of the plugin that handles it.
 *
 * @param arguments raw arguments as sent by the MCP client. Never {@code null}; may be empty.
 * Values are whatever the JSON parser produced ({@code String}, {@code Boolean},
 * {@code Integer}/{@code Long}/{@code Double}, {@code List}, {@code Map}).
 * @param context runtime services (analyzer, logger, directories). Never {@code null}.
 */
public record ToolInvocation(Map<String, Object> arguments, PluginContext context) {

    public ToolInvocation {
        // Map.copyOf would reject null values, and JSON arguments legitimately may contain them.
        arguments = arguments == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(arguments));
    }

    /**
     * Typed, validating view over {@link #arguments()}.
     * @return a new {@link Args} wrapper (cheap, no state)
     */
    public Args args() {
        return new Args(arguments);
    }
}

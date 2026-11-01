package io.github.springmcp.server;

import java.nio.file.Path;

/**
 * A named project root.
 *
 * <p>
 * Aliases exist so an LLM can refer to a project by something it can actually remember and reason
 * about ({@code petclinic}, {@code order-service}) instead of an absolute path it has to be told
 * every time. The first alias in the configuration is the default, which is what
 * {@code PluginContext.defaultProjectRoot()} reports.
 * </p>
 *
 * @param name short, stable identifier used in the {@code project} tool argument
 * @param root directory containing the sources
 */
public record ProjectAlias(String name, Path root) {

    public ProjectAlias {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("工程别名不能为空");
        }
        if (root == null) {
            throw new IllegalArgumentException("工程路径不能为空");
        }
        root = root.toAbsolutePath().normalize();
    }
}

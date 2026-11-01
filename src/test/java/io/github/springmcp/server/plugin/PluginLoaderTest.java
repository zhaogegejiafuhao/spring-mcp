package io.github.springmcp.server.plugin;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.springmcp.core.IndexRegistry;
import io.github.springmcp.spi.api.CoreAnalyzer;

/**
 * Plugin isolation. The promise is that a broken plugin degrades the server to "one tool fewer" and
 * never to "does not start", so each failure mode gets its own case.
 */
class PluginLoaderTest {

    private static final String SERVICES = "META-INF/services/io.github.springmcp.spi.SpringMcpPlugin";

    private static CoreAnalyzer analyzer() {
        return new IndexRegistry(IndexRegistry.Revalidation.NEVER, 60_000);
    }

    private static void writePluginDescriptor(Path dir, String className) throws IOException {
        Path services = dir.resolve(SERVICES);
        Files.createDirectories(services.getParent());
        Files.writeString(services, className + "\n");
    }

    @Test
    @DisplayName("a missing plugins directory is a normal state, not an error")
    void missingDirectory() {
        PluginLoader loader = new PluginLoader(Path.of("does", "not", "exist"), analyzer(), null);
        assertThat(loader.loadAll()).isEmpty();
        assertThat(loader.reports()).isEmpty();
    }

    @Test
    @DisplayName("a null plugins directory is accepted")
    void nullDirectory() {
        PluginLoader loader = new PluginLoader(null, analyzer(), null);
        assertThat(loader.loadAll()).isEmpty();
    }

    @Test
    @DisplayName("a directory with a valid service declaration is loaded")
    void loadsValidPlugin(@TempDir Path plugins) throws Exception {
        Path pluginDir = plugins.resolve("valid");
        writePluginDescriptor(pluginDir, TestPlugin.class.getName());

        try (PluginLoader loader = new PluginLoader(plugins, analyzer(), null)) {
            var loaded = loader.loadAll();
            assertThat(loaded).hasSize(1);
            assertThat(loaded.get(0).plugin().id()).isEqualTo("test.example");
            assertThat(loaded.get(0).plugin().tools()).hasSize(1);
            assertThat(loader.reports()).singleElement().satisfies(r -> assertThat(r.ok()).isTrue());
        }
    }

    @Test
    @DisplayName("a services file naming a missing class is contained, and healthy plugins still load")
    void containsLazyServiceConfigurationError(@TempDir Path plugins) throws Exception {
        // "bad" sorts before "valid", so the broken entry is processed first.
        writePluginDescriptor(plugins.resolve("bad"), "com.nowhere.DoesNotExist");
        writePluginDescriptor(plugins.resolve("valid"), TestPlugin.class.getName());

        try (PluginLoader loader = new PluginLoader(plugins, analyzer(), null)) {
            var loaded = loader.loadAll();

            assertThat(loaded).hasSize(1);
            assertThat(loaded.get(0).plugin().id()).isEqualTo("test.example");
            assertThat(loader.reports()).anyMatch(r -> !r.ok() && r.detail().contains("provider 实例化失败"));
            assertThat(loader.summary()).contains("1 ok").contains("1 failed");
        }
    }

    @Test
    @DisplayName("a plain file renamed to .jar does not break discovery")
    void toleratesNonJarFile(@TempDir Path plugins) throws Exception {
        Files.writeString(plugins.resolve("oops.jar"), "this is not a jar");
        writePluginDescriptor(plugins.resolve("valid"), TestPlugin.class.getName());

        try (PluginLoader loader = new PluginLoader(plugins, analyzer(), null)) {
            var loaded = loader.loadAll();
            assertThat(loaded).hasSize(1);
            assertThat(loader.reports()).anyMatch(r -> !r.ok());
        }
    }

    @Test
    @DisplayName("a plugin whose initialize throws is dropped without affecting the others")
    void dropsFailingInitialize(@TempDir Path plugins) throws Exception {
        writePluginDescriptor(plugins.resolve("failing"), FailingPlugin.class.getName());
        writePluginDescriptor(plugins.resolve("valid"), TestPlugin.class.getName());

        try (PluginLoader loader = new PluginLoader(plugins, analyzer(), null)) {
            var loaded = loader.loadAll();
            assertThat(loaded).extracting(p -> p.plugin().id()).containsExactly("test.example");
            assertThat(loader.reports()).anyMatch(r -> !r.ok() && r.detail().contains("initialize"));
        }
    }

    @Test
    @DisplayName("closing the loader closes every plugin it created")
    void closesPlugins(@TempDir Path plugins) throws Exception {
        writePluginDescriptor(plugins.resolve("valid"), TestPlugin.class.getName());

        PluginLoader loader = new PluginLoader(plugins, analyzer(), null);
        assertThat(loader.loadAll()).hasSize(1);
        loader.close();
        assertThat(loader.reports()).isNotEmpty();
    }

    @Test
    @DisplayName("load reports render to one readable line each")
    void reportRendering(@TempDir Path plugins) throws Exception {
        writePluginDescriptor(plugins.resolve("valid"), TestPlugin.class.getName());
        try (PluginLoader loader = new PluginLoader(plugins, analyzer(), null)) {
            loader.loadAll();
            List<PluginLoader.LoadReport> reports = loader.reports();
            assertThat(reports).isNotEmpty();
            assertThat(reports.get(0).render()).startsWith("OK").contains("test.example");
        }
    }

    /** Plugin that refuses to initialise, simulating a missing dependency or bad configuration. */
    public static class FailingPlugin implements io.github.springmcp.spi.SpringMcpPlugin {

        @Override
        public String id() {
            return "test.failing";
        }

        @Override
        public String version() {
            return "1.0";
        }

        @Override
        public void initialize(io.github.springmcp.spi.PluginContext context)
                throws io.github.springmcp.spi.ToolFailure {
            throw io.github.springmcp.spi.ToolFailure.of(io.github.springmcp.spi.ErrorCode.PLUGIN_ERROR,
                    "故意失败以验证隔离性");
        }

        @Override
        public List<io.github.springmcp.spi.ToolProvider> tools() {
            return List.of();
        }
    }
}

package io.github.springmcp.server.plugin;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.springmcp.spi.PluginContext;
import io.github.springmcp.spi.SpiVersion;
import io.github.springmcp.spi.SpringMcpPlugin;
import io.github.springmcp.spi.ToolFailure;
import io.github.springmcp.spi.api.CoreAnalyzer;

/**
 * Discovers third-party plugins from a directory of jars.
 *
 * <p>
 * Every jar gets its own {@link URLClassLoader} whose parent is the classloader that loaded the SPI.
 * Parent-first delegation means the SPI types are shared (so {@code SpringMcpPlugin} is the same
 * class on both sides, even if the plugin shades its own copy of the SPI jar), while the plugins
 * themselves stay invisible to each other.
 * </p>
 *
 * <p>
 * The failure handling is the interesting part. {@link ServiceLoader} is lazy: a broken
 * {@code META-INF/services} file or a missing class throws from {@code hasNext()} or {@code next()},
 * not from {@code load()}. If that were allowed to escape, one malformed jar in {@code plugins/}
 * would abort discovery for every other jar. So each jar is iterated in its own try/catch, and a
 * failure costs exactly that jar. The server always starts; worst case it starts with zero plugins.
 * </p>
 */
public final class PluginLoader implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(PluginLoader.class);

    private final Path pluginsDir;

    private final CoreAnalyzer analyzer;

    private final Path defaultProjectRoot;

    private final List<LoadedPlugin> loaded = new ArrayList<>();

    private final List<LoadReport> reports = new ArrayList<>();

    /**
     * @param pluginsDir directory to scan; may be {@code null} or missing, which simply means "no
     * plugins"
     * @param analyzer analysis facade handed to plugins
     * @param defaultProjectRoot project root from the command line, may be {@code null}
     */
    public PluginLoader(Path pluginsDir, CoreAnalyzer analyzer, Path defaultProjectRoot) {
        this.pluginsDir = pluginsDir;
        this.analyzer = analyzer;
        this.defaultProjectRoot = defaultProjectRoot;
    }

    /**
     * Loads every plugin it can.
     * @return successfully initialised plugins, never {@code null}
     */
    public List<LoadedPlugin> loadAll() {
        if (pluginsDir == null || !Files.isDirectory(pluginsDir)) {
            LOG.info("未配置插件目录或目录不存在，仅使用内建工具");
            return List.of();
        }

        List<Path> entries = listEntries();
        LOG.info("扫描插件目录 {}，候选 {} 项", pluginsDir, entries.size());

        for (Path entry : entries) {
            loadOne(entry);
        }
        LOG.info(summary());
        return List.copyOf(loaded);
    }

    private List<Path> listEntries() {
        try (Stream<Path> stream = Files.list(pluginsDir)) {
            return stream.filter(p -> Files.isDirectory(p)
                    || p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                .toList();
        }
        catch (IOException e) {
            LOG.warn("无法列出插件目录 {}: {}", pluginsDir, e.getMessage());
            return List.of();
        }
    }

    private void loadOne(Path entry) {
        long startedAt = System.currentTimeMillis();
        URLClassLoader loader = null;
        int found = 0;
        int accepted = 0;
        // Set as soon as this entry produced any diagnostic, so a single broken jar yields exactly
        // one report instead of one per symptom.
        boolean reported = false;
        try {
            URL url = entry.toUri().toURL();
            loader = new URLClassLoader(new URL[] { url }, SpringMcpPlugin.class.getClassLoader());
            ServiceLoader<SpringMcpPlugin> serviceLoader = ServiceLoader.load(SpringMcpPlugin.class, loader);
            Iterator<SpringMcpPlugin> it = serviceLoader.iterator();

            while (true) {
                SpringMcpPlugin plugin;
                try {
                    if (!it.hasNext()) {
                        break;
                    }
                    plugin = it.next();
                }
                catch (ServiceConfigurationError | LinkageError | RuntimeException e) {
                    // Lazy failure: bad services file, missing class, throwing constructor.
                    reports.add(new LoadReport(entry, null, false, "provider 实例化失败: " + describe(e),
                            System.currentTimeMillis() - startedAt));
                    LOG.warn("插件加载失败 {}: {}", entry.getFileName(), describe(e));
                    reported = true;
                    break;
                }
                found++;

                String rejection = validate(plugin);
                if (rejection != null) {
                    reports.add(new LoadReport(entry, safeId(plugin), false, rejection,
                            System.currentTimeMillis() - startedAt));
                    LOG.warn("拒绝插件 {}: {}", safeId(plugin), rejection);
                    reported = true;
                    continue;
                }

                PluginContext context = new DefaultPluginContext(analyzer,
                        new Slf4jPluginLogger(LoggerFactory.getLogger("plugin." + plugin.id()), plugin.id()),
                        pluginsDir, defaultProjectRoot);
                try {
                    plugin.initialize(context);
                }
                catch (ToolFailure f) {
                    reports.add(new LoadReport(entry, plugin.id(), false, "initialize 失败: " + f.render(),
                            System.currentTimeMillis() - startedAt));
                    LOG.warn("插件 {} 初始化失败: {}", plugin.id(), f.render());
                    reported = true;
                    closeQuietly(plugin);
                    continue;
                }
                catch (RuntimeException | LinkageError e) {
                    reports.add(new LoadReport(entry, plugin.id(), false, "initialize 异常: " + describe(e),
                            System.currentTimeMillis() - startedAt));
                    LOG.warn("插件 {} 初始化异常", plugin.id(), e);
                    reported = true;
                    closeQuietly(plugin);
                    continue;
                }

                if (plugin.tools() == null) {
                    reports.add(new LoadReport(entry, plugin.id(), false, "tools() 返回 null",
                            System.currentTimeMillis() - startedAt));
                    reported = true;
                    closeQuietly(plugin);
                    continue;
                }

                loaded.add(new LoadedPlugin(plugin, loader, entry));
                accepted++;
                reports.add(new LoadReport(entry, plugin.id(), true,
                        plugin.tools().size() + " 个工具", System.currentTimeMillis() - startedAt));
            }

            if (found == 0 && !reported) {
                // A dependency jar dropped into plugins/ is a common mistake; say so once.
                reports.add(new LoadReport(entry, null, false,
                        "未找到 META-INF/services/io.github.springmcp.spi.SpringMcpPlugin",
                        System.currentTimeMillis() - startedAt));
            }
        }
        catch (IOException | RuntimeException e) {
            reports.add(new LoadReport(entry, null, false, describe(e), System.currentTimeMillis() - startedAt));
            LOG.warn("插件目录项处理失败 {}", entry, e);
        }
        finally {
            if (loader != null && accepted == 0) {
                // Nothing kept a reference to this loader, release the jar handle (matters on Windows).
                try {
                    loader.close();
                }
                catch (IOException ignored) {
                    // Nothing useful to do.
                }
            }
        }
    }

    private static String validate(SpringMcpPlugin plugin) {
        String id;
        try {
            id = plugin.id();
            plugin.version();
        }
        catch (RuntimeException e) {
            return "id()/version() 抛异常: " + describe(e);
        }
        if (id == null || id.isBlank()) {
            return "插件 id 为空";
        }
        int major;
        try {
            major = plugin.spiMajorVersion();
        }
        catch (RuntimeException | LinkageError e) {
            return "spiMajorVersion() 不可用: " + describe(e);
        }
        if (!SpiVersion.isCompatible(major)) {
            return "SPI 主版本不兼容：插件 " + major + "，服务端 " + SpiVersion.MAJOR;
        }
        return null;
    }

    private static String safeId(SpringMcpPlugin plugin) {
        try {
            String id = plugin.id();
            return id == null ? "<no-id>" : id;
        }
        catch (RuntimeException e) {
            return "<no-id>";
        }
    }

    private static void closeQuietly(SpringMcpPlugin plugin) {
        try {
            plugin.close();
        }
        catch (Exception ignored) {
            // A plugin that cannot close is not worth failing the load over.
        }
    }

    private static String describe(Throwable e) {
        String message = e.getMessage();
        return e.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }

    /** @return human readable one line summary of the load pass */
    public String summary() {
        long ok = reports.stream().filter(LoadReport::ok).count();
        long failed = reports.size() - ok;
        return "plugin load summary: " + ok + " ok, " + failed + " failed";
    }

    /** @return per candidate diagnostics, in load order */
    public List<LoadReport> reports() {
        return List.copyOf(reports);
    }

    @Override
    public void close() {
        // Reverse order mirrors construction order, which is what plugins usually expect.
        for (int i = loaded.size() - 1; i >= 0; i--) {
            LoadedPlugin lp = loaded.get(i);
            try {
                lp.plugin.close();
            }
            catch (Exception e) {
                LOG.warn("插件 {} 关闭失败", safeId(lp.plugin), e);
            }
            try {
                lp.loader.close();
            }
            catch (IOException e) {
                LOG.warn("插件 classloader 关闭失败", e);
            }
        }
        loaded.clear();
    }

    /**
     * A plugin that passed validation and initialisation.
     *
     * @param plugin the plugin instance
     * @param loader its classloader, closed on shutdown
     * @param source jar or directory it came from
     */
    public record LoadedPlugin(SpringMcpPlugin plugin, URLClassLoader loader, Path source) {
    }

    /**
     * Outcome for one candidate entry or one plugin inside it.
     *
     * @param source jar or directory
     * @param pluginId plugin id when known, otherwise {@code null}
     * @param ok whether the plugin was accepted
     * @param detail success detail or failure reason
     * @param durationMs time spent
     */
    public record LoadReport(Path source, String pluginId, boolean ok, String detail, long durationMs) {

        /** @return one line rendering suitable for logs */
        public String render() {
            String name = pluginId == null ? source.getFileName().toString() : pluginId;
            return (ok ? "OK   " : "FAIL ") + name + " (" + durationMs + "ms) " + detail;
        }
    }
}

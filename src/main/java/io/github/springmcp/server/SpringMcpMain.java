package io.github.springmcp.server;

import java.io.InputStream;
import java.io.PrintStream;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.springmcp.core.IndexRegistry;
import io.github.springmcp.core.cache.IndexCacheStore;
import io.github.springmcp.server.builtin.BuiltinPlugin;
import io.github.springmcp.server.builtin.BuiltinTools;
import io.github.springmcp.server.builtin.ProjectsPlugin;
import io.github.springmcp.server.declarative.ConfigLoader;
import io.github.springmcp.server.declarative.DeclarativeTools;
import io.github.springmcp.server.dev.DevCli;
import io.github.springmcp.server.mcp.ToolRegistrar;
import io.github.springmcp.server.mcp.ToolResults;
import io.github.springmcp.server.plugin.DefaultPluginContext;
import io.github.springmcp.server.plugin.PluginLoader;
import io.github.springmcp.server.plugin.Slf4jPluginLogger;
import io.github.springmcp.server.transport.EofAwareInputStream;
import io.github.springmcp.server.transport.StdioTransportFactory;
import io.github.springmcp.server.transport.StdoutGuard;
import io.github.springmcp.server.transport.TransportFactory;
import io.github.springmcp.spi.PluginContext;
import io.github.springmcp.spi.ToolFailure;
import io.github.springmcp.spi.ToolProvider;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;

/**
 * Entry point.
 *
 * <p>
 * Startup order matters and is the easiest thing to get wrong:
 * </p>
 * <ol>
 * <li>capture the real stdout and replace {@code System.out} with a stderr redirect — before any
 * plugin or library can print, otherwise the JSON-RPC stream gets polluted</li>
 * <li>parse the command line</li>
 * <li>build the analyzer and load plugins — a plugin failure must never prevent startup</li>
 * <li>create the transport with the <em>captured</em> stdout, then register tools</li>
 * </ol>
 */
public final class SpringMcpMain {

    private static final Logger LOG = LoggerFactory.getLogger(SpringMcpMain.class);

    /** Version reported to MCP clients. */
    public static final String VERSION = "0.1.0";

    /**
     * How long the server keeps running after the client closed stdin, so a response that is already
     * being written is not truncated. See the comment at the EOF handler.
     */
    private static final long EOF_GRACE_MILLIS = 500;

    private SpringMcpMain() {
    }

    /**
     * @param args command line
     */
    public static void main(String[] args) {
        // Must be first: everything after this may write to System.out by accident.
        PrintStream realOut = StdoutGuard.install();

        LauncherConfig config;
        try {
            config = LauncherConfig.parse(args);
        }
        catch (IllegalArgumentException e) {
            System.err.println("参数错误: " + e.getMessage());
            System.err.println(LauncherConfig.usage());
            System.exit(2);
            return;
        }

        if (config.help()) {
            System.err.println(LauncherConfig.usage());
            return;
        }

        McpJsonMapper jsonMapper = new JacksonMcpJsonMapper(new ObjectMapper());
        IndexRegistry registry = IndexRegistry.fromEnvironment(cacheFor(config));
        Path pluginsDir = resolvePluginsDirectory(config.pluginsDir());

        if (config.devMode()) {
            // Wrap in UTF-8: the JVM's default console encoding on Windows is GBK, which would
            // turn Chinese messages into mojibake before they reach the terminal.
            var utf8Out = new PrintStream(realOut, true, java.nio.charset.StandardCharsets.UTF_8);
            int code = new DevCli(registry, utf8Out).run(config.devArgs());
            utf8Out.flush();
            System.exit(code);
            return;
        }

        try {
            serve(config, jsonMapper, registry, pluginsDir, realOut);
        }
        catch (RuntimeException e) {
            LOG.error("启动失败", e);
            System.exit(1);
        }
    }

    private static void serve(LauncherConfig config, McpJsonMapper jsonMapper, IndexRegistry registry, Path pluginsDir,
            PrintStream realOut) {

        ToolResults results = new ToolResults(new ObjectMapper());
        ToolRegistrar registrar = new ToolRegistrar(jsonMapper, results);

        ProjectCatalog catalog = config.catalog();
        Path defaultRoot = catalog.defaultRoot();

        PluginContext builtinContext = new DefaultPluginContext(registry,
                new Slf4jPluginLogger(LoggerFactory.getLogger("plugin.builtin"), "builtin"), pluginsDir, defaultRoot);

        // Built-in tools go through the plugin SPI like any other plugin.
        List<McpServerFeatures.SyncToolSpecification> specs = new ArrayList<>();
        Set<String> registeredNames = new LinkedHashSet<>();

        BuiltinPlugin builtin = new BuiltinPlugin(config.indexOptions(), catalog);
        try {
            builtin.initialize(builtinContext);
        }
        catch (ToolFailure e) {
            LOG.error("内建工具初始化失败: {}", e.render());
            System.exit(1);
            return;
        }
        registerTools(builtin.tools(), BuiltinPlugin.ID, builtinContext, registrar, registeredNames, specs);

        // Project listing is separate from the five analysis tools on purpose: it answers a question
        // about this server's configuration, not about the analysed code.
        ProjectsPlugin projects = new ProjectsPlugin(catalog, config.indexOptions());
        projects.initialize(builtinContext);
        registerTools(projects.tools(), ProjectsPlugin.ID, builtinContext, registrar, registeredNames, specs);

        // Declarative extension: config-defined tools that delegate to a built-in query, plus
        // (behind an explicit switch) tools backed by an external process.
        ConfigLoader.LoadResult extensionConfig = ConfigLoader.load(config.configFile(), pluginsDir);
        if (extensionConfig.found()) {
            LOG.info("扩展配置: {}", extensionConfig.file());
        }
        extensionConfig.problems().forEach(problem -> LOG.warn("扩展配置: {}", problem));

        DeclarativeTools.BuildResult declarative = DeclarativeTools.build(extensionConfig.config(),
                BuiltinTools.byName(registry, catalog, config.indexOptions()), defaultRoot,
                extensionBase(extensionConfig), config.allowExternalCommands());
        declarative.problems().forEach(problem -> LOG.warn("扩展工具: {}", problem));
        registerTools(declarative.tools(), "config", builtinContext, registrar, registeredNames, specs);

        PluginLoader loader = new PluginLoader(pluginsDir, registry, defaultRoot);
        try {
            for (var loaded : loader.loadAll()) {
                PluginContext context = new DefaultPluginContext(registry,
                        new Slf4jPluginLogger(LoggerFactory.getLogger("plugin." + loaded.plugin().id()),
                                loaded.plugin().id()),
                        pluginsDir, defaultRoot);
                registerTools(loaded.plugin().tools(), loaded.plugin().id(), context, registrar, registeredNames,
                        specs);
            }
            for (var report : loader.reports()) {
                LOG.info("{}", report.render());
            }
        }
        catch (RuntimeException e) {
            // Even a broken plugin loader must not stop the built-in tools from serving.
            LOG.error("插件加载异常，继续以内建工具运行", e);
        }

        CountDownLatch shutdown = new CountDownLatch(1);

        // End of session arrives as EOF on stdin, not as a signal: a client shuts the server down by
        // closing the pipe. Handle it on a separate thread because closing a transport from inside the
        // read that just ended can deadlock against the reader's own teardown.
        InputStream clientInput = new EofAwareInputStream(System.in, () -> {
            Thread exit = new Thread(() -> {
                // A client may write a request and close the pipe in the same breath, which is what
                // `printf ... | java -jar` does. The request is dispatched asynchronously, so tearing
                // down the instant EOF is seen truncates the answer (measured: a piped initialize
                // produced 0 bytes before this pause existed). Half a second is far more than the
                // dispatch needs and costs nothing at the end of a session.
                try {
                    Thread.sleep(EOF_GRACE_MILLIS);
                }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                shutdown.countDown();
            }, "spring-mcp-client-gone");
            exit.setDaemon(true);
            exit.start();
        });

        TransportFactory transportFactory = new StdioTransportFactory(jsonMapper, clientInput, realOut);
        McpSyncServer server = McpServer.sync(transportFactory.create())
            .serverInfo("spring-mcp", VERSION)
            .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
            .tools(specs)
            .build();

        LOG.info("spring-mcp {} 已就绪：{} 个工具，传输 {}，插件目录 {}", VERSION, specs.size(),
                transportFactory.name(), pluginsDir);

        // Both exit paths — signal and client-gone — run the same teardown exactly once.
        AtomicBoolean tornDown = new AtomicBoolean();
        Runnable teardown = () -> {
            if (!tornDown.compareAndSet(false, true)) {
                return;
            }
            try {
                server.close();
            }
            catch (RuntimeException e) {
                LOG.warn("关闭 MCP server 时出错", e);
            }
            loader.close();
        };

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOG.info("正在关闭 spring-mcp");
            teardown.run();
            shutdown.countDown();
        }, "spring-mcp-shutdown"));

        try {
            // The STDIO transport owns its reader thread; the main thread just parks here.
            shutdown.await();
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Returning from main is not enough: the transport's reader thread is not a daemon, so the JVM
        // would stay up holding an idle, unreachable server.
        LOG.info("客户端已关闭输入流，正在退出");
        teardown.run();
        // The transport writes through the captured stream; flush before exiting so a last response
        // that is still sitting in a buffer is not lost with the process.
        realOut.flush();
        System.exit(0);
    }

    /**
     * Registers a batch of tools into the shared name space.
     *
     * <p>
     * There are several tool sources — built-in, project listing, declarative config and jar plugins —
     * and they all have to obey the same two rules: a name is claimed exactly once, and a tool whose
     * schema the SDK rejects is dropped rather than failing startup. Keeping that in one place means
     * adding a new source is a one-line call instead of a copy of the loop, and the name-conflict
     * arbitration cannot drift between sources.
     * </p>
     *
     * @param tools tools to register, may be {@code null}
     * @param owner plugin id or {@code "builtin"}, used in diagnostics
     * @param context runtime context handed to each tool
     * @param registrar registrar that validates the schema and installs the exception firewall
     * @param registeredNames names already claimed; this method adds to it
     * @param specs sink for accepted specifications, in registration order
     * @return how many tools were actually registered
     */
    private static int registerTools(List<ToolProvider> tools, String owner, PluginContext context,
            ToolRegistrar registrar, Set<String> registeredNames,
            List<McpServerFeatures.SyncToolSpecification> specs) {

        if (tools == null) {
            LOG.warn("{} 提供了 null 工具列表，已跳过", owner);
            return 0;
        }

        int registered = 0;
        for (ToolProvider tool : tools) {
            String name = toolName(tool);
            if (name == null) {
                LOG.warn("{} 提供了无名工具，已跳过", owner);
                continue;
            }
            if (!registeredNames.add(name)) {
                LOG.warn("工具名 '{}' 已被占用（本次来自 {}），已跳过", name, owner);
                continue;
            }
            var spec = registrar.register(tool, owner, context);
            if (spec.isPresent()) {
                specs.add(spec.get());
                registered++;
            }
        }
        return registered;
    }

    private static String toolName(ToolProvider tool) {
        try {
            return tool.descriptor().name();
        }
        catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Directory that external tools treat as their working directory base.
     *
     * <p>
     * It is the config file's own folder: an extension ships alongside its scripts, and it must stay
     * usable on projects that do not contain those scripts.
     * </p>
     * @param config loaded extension configuration
     * @return absolute directory, never {@code null}
     */
    private static Path extensionBase(ConfigLoader.LoadResult config) {
        Path file = config.file();
        Path parent = file == null ? null : file.toAbsolutePath().normalize().getParent();
        return parent != null ? parent : Path.of("").toAbsolutePath();
    }

    /**
     * Picks the index cache for this run.
     *
     * <p>
     * The baseline command always runs without one. Its whole job is to prove the analysis still
     * produces the same answer, and a cached answer would prove nothing — a regression that only
     * affects parsing would hide behind a hit.
     * </p>
     *
     * @param config launcher configuration
     * @return the cache store to use, possibly disabled
     */
    private static IndexCacheStore cacheFor(LauncherConfig config) {
        if (config.devMode() && !config.devArgs().isEmpty() && "baseline".equals(config.devArgs().get(0))) {
            return IndexCacheStore.disabled();
        }
        IndexCacheStore store = IndexCacheStore.fromMode(config.indexCacheMode(), null);
        if (store.enabled()) {
            LOG.info("索引缓存目录: {}", store.directory());
        }
        return store;
    }

    /**
     * Resolves the plugin directory: explicit option, then environment variable, then a
     * {@code plugins} folder next to the jar, then {@code ./plugins}.
     * @param configured value from {@code --plugins}, may be {@code null}
     * @return absolute path, never {@code null}
     */
    static Path resolvePluginsDirectory(Path configured) {
        if (configured != null) {
            return configured.toAbsolutePath().normalize();
        }
        String env = System.getenv("SPRING_MCP_PLUGINS_DIR");
        if (env != null && !env.isBlank()) {
            return Path.of(env).toAbsolutePath().normalize();
        }
        Path besideJar = jarDirectory();
        if (besideJar != null) {
            Path candidate = besideJar.resolve("plugins");
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
        }
        return Path.of("plugins").toAbsolutePath().normalize();
    }

    private static Path jarDirectory() {
        try {
            var source = SpringMcpMain.class.getProtectionDomain().getCodeSource();
            if (source == null) {
                return null;
            }
            Path path = Path.of(source.getLocation().toURI());
            return Files.isDirectory(path) ? path : path.getParent();
        }
        catch (URISyntaxException | RuntimeException e) {
            return null;
        }
    }
}

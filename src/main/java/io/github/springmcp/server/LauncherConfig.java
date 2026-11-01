package io.github.springmcp.server;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import io.github.springmcp.spi.api.IndexOptions;

/**
 * Command line configuration.
 *
 * <p>
 * Kept as a hand written parser with no dependencies: the server ships as a single jar and pulling
 * in a CLI library for a handful of options would not pay for itself.
 * </p>
 *
 * @param projects configured project roots; the first is the default. Never {@code null}, may be
 * empty when no {@code --project} was given
 * @param configFile declarative extension file, {@code null} to auto-detect
 * @param allowExternalCommands whether config-declared external processes may run; off by default
 * because enabling it turns the config file into executable code
 * @param pluginsDir plugin directory, {@code null} to auto-detect
 * @param includeTests whether test sources are indexed by default
 * @param maxIndexedFiles file cap for one index build, {@code null} to keep the default
 * @param indexCacheMode on-disk index cache mode: {@code on} (default) or {@code off}
 * @param devMode run the offline CLI instead of the MCP server
 * @param devArgs arguments forwarded to the offline CLI
 * @param help whether to print usage and exit
 */
public record LauncherConfig(List<ProjectAlias> projects, Path configFile, boolean allowExternalCommands,
        Path pluginsDir, boolean includeTests, Integer maxIndexedFiles, String indexCacheMode, boolean devMode,
        List<String> devArgs, boolean help) {

    public LauncherConfig {
        projects = projects == null ? List.of() : List.copyOf(projects);
        devArgs = devArgs == null ? List.of() : List.copyOf(devArgs);
        indexCacheMode = indexCacheMode == null || indexCacheMode.isBlank() ? "on" : indexCacheMode.trim();
    }

    /**
     * Default project root, kept because most callers only ever care about one project.
     * @return absolute root of the first configured project, or {@code null}
     */
    public Path projectRoot() {
        return projects.isEmpty() ? null : projects.get(0).root();
    }

    /**
     * Options every analysis call starts from.
     * @return index options reflecting the command line
     */
    public IndexOptions indexOptions() {
        IndexOptions options = IndexOptions.defaults().withIncludeTests(includeTests);
        return maxIndexedFiles == null ? options : options.withMaxIndexedFiles(maxIndexedFiles);
    }

    /**
     * Configured projects as a lookup structure.
     * @return catalog, empty when nothing was configured
     */
    public ProjectCatalog catalog() {
        return ProjectCatalog.of(projects);
    }

    /** @return usage text, printed to stderr because stdout is the protocol channel */
    public static String usage() {
        return """
                spring-mcp - static Spring Boot source analysis over MCP

                Usage:
                  java -jar spring-mcp-all.jar [options]
                  java -jar spring-mcp-all.jar --dev <command> [command options]

                Options:
                  --project=<dir>          a project root; may be repeated
                  --project=<alias>:<dir>  same, but exposed to tools as `project: <alias>`
                                           (the first project listed is the default)
                  --config=<file>          declarative tools file (default: <plugins>/spring-mcp.yaml|json)
                  --allow-external-commands
                                           allow config-declared external processes (default: off)
                  --plugins=<dir>          plugin directory (default: <jar dir>/plugins, then ./plugins)
                  --include-tests          also index src/test/java
                  --max-indexed-files=<n>  cap on files per index build (default: 20000)
                  --index-cache=on|off     reuse a serialised index across processes (default: on)
                  --dev                    run the offline CLI instead of the MCP server
                  -h, --help               print this text

                Dev commands:
                  index-info   --root <dir>
                  controllers  --root <dir> [--http-method GET] [--base-path /api]
                  callers      --root <dir> --service <Class> [--method name] [--low-confidence]
                  mappers      --root <dir> [--kinds ANNOTATION,XML,JPA] [--name User]
                  annotations  --root <dir> --annotation <Name> [--kinds TYPE,METHOD,...] [--strict]
                  deps         --root <dir> --file <path>
                  baseline     --repo <name> [--regenerate] [--manifest <file>] [--work <dir>]

                Environment:
                  SPRING_MCP_PLUGINS_DIR       plugin directory
                  SPRING_MCP_INDEX_REVALIDATE  stat | ttl | never  (default: stat)
                  SPRING_MCP_INDEX_CACHE       on | off            (default: on)
                """;
    }

    /**
     * Parses arguments.
     * @param args raw arguments
     * @return configuration
     * @throws IllegalArgumentException when an option has no value
     */
    public static LauncherConfig parse(String[] args) {
        // Accumulated through the catalog rather than a plain list so a repeated alias is renamed
        // instead of silently shadowing the first project.
        ProjectCatalog projects = ProjectCatalog.empty();
        Path configFile = null;
        boolean allowExternalCommands = false;
        Path plugins = null;
        boolean includeTests = false;
        Integer maxIndexedFiles = null;
        String indexCacheMode = cacheModeFromEnvironment();
        boolean dev = false;
        boolean help = false;
        List<String> devArgs = new ArrayList<>();

        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (!dev) {
                if (a.startsWith("--project=")) {
                    ProjectAlias alias = parseProject(value(a));
                    projects = projects.with(alias.name(), alias.root());
                    continue;
                }
                if (a.startsWith("--config=")) {
                    configFile = Path.of(value(a));
                    continue;
                }
                if ("--allow-external-commands".equals(a)) {
                    allowExternalCommands = true;
                    continue;
                }
                if (a.startsWith("--plugins=")) {
                    plugins = Path.of(value(a));
                    continue;
                }
                if ("--include-tests".equals(a)) {
                    includeTests = true;
                    continue;
                }
                if (a.startsWith("--max-indexed-files=")) {
                    maxIndexedFiles = positiveInt("--max-indexed-files", value(a));
                    continue;
                }
                if (a.startsWith("--index-cache=")) {
                    indexCacheMode = cacheMode(value(a));
                    continue;
                }
                if ("--dev".equals(a)) {
                    dev = true;
                    continue;
                }
                if ("-h".equals(a) || "--help".equals(a)) {
                    help = true;
                    continue;
                }
                if (a.startsWith("--")) {
                    throw new IllegalArgumentException("未知参数: " + a);
                }
                continue;
            }
            devArgs.add(a);
        }

        return new LauncherConfig(projects.all(), configFile, allowExternalCommands, plugins, includeTests,
                maxIndexedFiles, indexCacheMode, dev, devArgs, help);
    }

    /**
     * Parses one {@code --project} value, with or without an alias.
     *
     * <p>
     * The alias separator is the first colon, but a colon at index 1 is a Windows drive letter
     * ({@code C:\work\app}) and must not be mistaken for one. Getting that wrong would silently turn
     * {@code C:\work\app} into an alias named {@code C} pointing at {@code \work\app}.
     * </p>
     *
     * @param raw value after {@code --project=}
     * @return the project
     */
    static ProjectAlias parseProject(String raw) {
        String value = raw.trim();
        int colon = value.indexOf(':');
        boolean driveLetter = colon == 1 && value.length() > 2
                && (value.charAt(2) == '\\' || value.charAt(2) == '/');
        if (colon > 1 && !driveLetter) {
            String name = value.substring(0, colon).trim();
            String path = value.substring(colon + 1).trim();
            if (!name.isEmpty() && !path.isEmpty()) {
                return new ProjectAlias(name, Path.of(path));
            }
        }
        Path root = Path.of(value);
        return new ProjectAlias(folderName(root), root);
    }

    private static String folderName(Path root) {
        Path fileName = root.toAbsolutePath().normalize().getFileName();
        return fileName == null ? "project" : fileName.toString();
    }

    private static String cacheMode(String raw) {
        String mode = raw.trim().toLowerCase(java.util.Locale.ROOT);
        if (!mode.equals("on") && !mode.equals("off")) {
            throw new IllegalArgumentException("--index-cache 只接受 on 或 off: " + raw);
        }
        return mode;
    }

    private static String cacheModeFromEnvironment() {
        String raw = System.getenv("SPRING_MCP_INDEX_CACHE");
        if (raw == null || raw.isBlank()) {
            return "on";
        }
        String mode = raw.trim().toLowerCase(java.util.Locale.ROOT);
        return mode.equals("off") ? "off" : "on";
    }

    private static int positiveInt(String option, String raw) {
        try {
            int parsed = Integer.parseInt(raw.trim());
            if (parsed <= 0) {
                throw new IllegalArgumentException(option + " 必须是正整数: " + raw);
            }
            return parsed;
        }
        catch (NumberFormatException e) {
            throw new IllegalArgumentException(option + " 必须是正整数: " + raw);
        }
    }

    private static String value(String arg) {
        int eq = arg.indexOf('=');
        if (eq < 0 || eq == arg.length() - 1) {
            throw new IllegalArgumentException("参数缺少取值: " + arg);
        }
        return arg.substring(eq + 1);
    }
}

package io.github.springmcp.core.index;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.Collections;

/**
 * Which module each source file belongs to, and which modules depend on which.
 *
 * <p>
 * Used only by query-time scoping ({@link io.github.springmcp.spi.api.QueryScope}). The index is
 * always built over the whole project, so this never decides what gets parsed — only how results
 * are labelled and filtered.
 * </p>
 *
 * <p>
 * Module identifiers are project-relative paths, never Maven artifact ids. Artifact ids are
 * convention, paths are facts, and the caller has to be able to type one after looking at the
 * directory tree.
 * </p>
 */
public final class ModuleLayout {

    /** How deep to look for an aggregator {@code pom.xml}. */
    private static final int MAX_BUILD_FILE_DEPTH = 3;

    /** Build files are tiny; anything larger is not a real pom and is skipped unread. */
    private static final long MAX_BUILD_FILE_BYTES = 256L * 1024L;

    private static final Pattern MODULES_BLOCK = Pattern.compile("<modules>(.*?)</modules>", Pattern.DOTALL);

    private static final Pattern MODULE_ENTRY = Pattern.compile("<module>\\s*([^<]+?)\\s*</module>");

    private static final Pattern ARTIFACT_ID = Pattern.compile("<artifactId>\\s*([^<]+?)\\s*</artifactId>");

    private static final Pattern PARENT_BLOCK = Pattern.compile("<parent>.*?</parent>", Pattern.DOTALL);

    private static final Pattern DEPENDENCIES_BLOCK = Pattern.compile("<dependencies>(.*?)</dependencies>",
            Pattern.DOTALL);

    private static final Pattern GRADLE_INCLUDE = Pattern.compile("include\\s*\\(?\\s*([^\\n)]+)");

    private static final Pattern GRADLE_STRING = Pattern.compile("[\"']([^\"']+)[\"']");

    private static final Pattern GRADLE_PROJECT_DEP = Pattern.compile("project\\s*\\(\\s*[\"'](:[^\"']+)[\"']\\s*\\)");

    private final Path root;

    private final List<String> modules;

    /** Same modules, deepest first, so longest-prefix matching stops at the most specific one. */
    private final List<String> byDepthDesc;

    private final Map<String, List<String>> dependencies;

    private ModuleLayout(Path root, List<String> modules, Map<String, List<String>> dependencies) {
        this.root = root;
        List<String> sorted = new ArrayList<>(modules);
        sorted.sort(Comparator.naturalOrder());
        this.modules = List.copyOf(sorted);
        List<String> deepest = new ArrayList<>(sorted);
        deepest.sort(Comparator.comparingInt((String m) -> m.split("/").length).reversed()
            .thenComparing(Comparator.naturalOrder()));
        this.byDepthDesc = List.copyOf(deepest);
        // Ordered: this map is serialised by list-modules, and Map.copyOf randomises iteration order.
        this.dependencies = Collections.unmodifiableMap(new LinkedHashMap<>(dependencies));
    }

    /**
     * A layout that knows about no modules, which disables scoping.
     * @param root project root
     * @return empty layout
     */
    public static ModuleLayout empty(Path root) {
        return new ModuleLayout(root, List.of(), Map.of());
    }

    /**
     * Detects the module structure of a project.
     *
     * <p>
     * Maven aggregators are read first (they are unambiguous), then Gradle settings, then a path
     * convention as a last resort. Detection returns no modules at all when fewer than two are
     * found: a single-module project must not gain a {@code scope} argument it can never usefully
     * set.
     * </p>
     *
     * @param root project root
     * @return the layout, never {@code null}
     */
    public static ModuleLayout detect(Path root) {
        Path base = root.toAbsolutePath().normalize();
        List<String> found = detectMaven(base);
        if (found.size() < 2) {
            found = detectGradle(base);
        }
        if (found.size() < 2) {
            found = detectByConvention(base);
        }
        if (found.size() < 2) {
            return empty(base);
        }
        return new ModuleLayout(base, found, readDependencies(base, found));
    }

    /**
     * @return module paths, sorted; empty when the project is not multi-module
     */
    public List<String> modules() {
        return modules;
    }

    /**
     * @return whether scoping is available for this project
     */
    public boolean isEmpty() {
        return modules.isEmpty();
    }

    /**
     * @return module path to the modules it declares a dependency on
     */
    public Map<String, List<String>> dependencies() {
        return dependencies;
    }

    /**
     * Finds the module a file belongs to.
     * @param file absolute or root-relative source path
     * @return the deepest matching module path, or {@code ""} when the file is outside every module
     */
    public String moduleOf(Path file) {
        if (modules.isEmpty()) {
            return "";
        }
        String rel = relative(file);
        if (rel == null) {
            return "";
        }
        for (String module : byDepthDesc) {
            if (rel.equals(module) || rel.startsWith(module + "/")) {
                return module;
            }
        }
        return "";
    }

    /**
     * Expands a set of modules with everything they depend on, transitively.
     *
     * <p>
     * Answers "I only care about {@code services/order}, but its types extend classes from
     * {@code common}" without making the caller read every build file.
     * </p>
     *
     * @param seeds modules to start from
     * @return the seeds plus their transitive dependencies, in discovery order
     */
    public Set<String> withDependencies(Collection<String> seeds) {
        Set<String> out = new LinkedHashSet<>();
        if (seeds == null) {
            return out;
        }
        List<String> queue = new ArrayList<>();
        for (String seed : seeds) {
            String module = resolveName(seed);
            if (module != null && out.add(module)) {
                queue.add(module);
            }
        }
        while (!queue.isEmpty()) {
            String current = queue.remove(0);
            for (String dependency : dependencies.getOrDefault(current, List.of())) {
                if (out.add(dependency)) {
                    queue.add(dependency);
                }
            }
        }
        return out;
    }

    /**
     * Maps a caller supplied name onto a real module path.
     * @param name as supplied, e.g. {@code services/order} or {@code /abs/path/services/order}
     * @return the module path, or {@code null} when nothing matches
     */
    public String resolveName(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String needle = name.trim().replace('\\', '/');
        Path asPath = Path.of(needle);
        if (asPath.isAbsolute()) {
            String viaPath = moduleOf(asPath);
            return viaPath.isEmpty() ? null : viaPath;
        }
        while (needle.startsWith("./")) {
            needle = needle.substring(2);
        }
        while (needle.endsWith("/")) {
            needle = needle.substring(0, needle.length() - 1);
        }
        for (String module : modules) {
            if (module.equals(needle)) {
                return module;
            }
        }
        return null;
    }

    private String relative(Path file) {
        Path normalised = file.toAbsolutePath().normalize();
        if (!normalised.startsWith(root)) {
            return null;
        }
        return root.relativize(normalised).toString().replace('\\', '/');
    }

    // ---------------------------------------------------------------- detection

    private static List<String> detectMaven(Path root) {
        Set<String> modules = new LinkedHashSet<>();
        for (Path pom : buildFiles(root, "pom.xml")) {
            String text = read(pom);
            if (text == null) {
                continue;
            }
            Matcher block = MODULES_BLOCK.matcher(text);
            while (block.find()) {
                Matcher entry = MODULE_ENTRY.matcher(block.group(1));
                while (entry.find()) {
                    Path moduleDir = pom.getParent().resolve(entry.group(1).trim()).normalize();
                    if (Files.isDirectory(moduleDir) && moduleDir.startsWith(root)) {
                        modules.add(root.relativize(moduleDir).toString().replace('\\', '/'));
                    }
                }
            }
        }
        return new ArrayList<>(modules);
    }

    private static List<String> detectGradle(Path root) {
        for (String name : List.of("settings.gradle", "settings.gradle.kts")) {
            Path settings = root.resolve(name);
            String text = read(settings);
            if (text == null) {
                continue;
            }
            Set<String> modules = new LinkedHashSet<>();
            Matcher include = GRADLE_INCLUDE.matcher(text);
            while (include.find()) {
                Matcher quoted = GRADLE_STRING.matcher(include.group(1));
                while (quoted.find()) {
                    String path = toModulePath(quoted.group(1));
                    Path dir = path == null ? null : safeResolve(root, path);
                    if (dir != null && Files.isDirectory(dir)) {
                        modules.add(path);
                    }
                }
            }
            if (modules.size() >= 2) {
                return new ArrayList<>(modules);
            }
        }
        return List.of();
    }

    /**
     * Normalises a Gradle project path ({@code :a:b}) into a module path ({@code a/b}).
     *
     * <p>
     * Returns {@code null} for anything that is not a plain path. Settings files are real code and do
     * contain globs — a commented-out include with a wildcard is common — and feeding one to
     * {@link Path#of} throws {@link java.nio.file.InvalidPathException} on Windows, which would take
     * the whole index build down over a comment.
     * </p>
     *
     * @param raw quoted string from an {@code include} statement
     * @return module path, or {@code null} when the value is not a usable path
     */
    private static String toModulePath(String raw) {
        String path = raw.replace(':', '/');
        while (path.startsWith("/")) {
            path = path.substring(1);
        }
        if (path.isEmpty()) {
            return null;
        }
        for (char c : path.toCharArray()) {
            if (isGlobCharacter(c)) {
                return null;
            }
        }
        return path;
    }

    private static boolean isGlobCharacter(char c) {
        return switch (c) {
            case '*', '?', '[', ']', '{', '}', '<', '>', '|', '"', '\'', '\\', ' ' -> true;
            default -> false;
        };
    }

    private static Path safeResolve(Path root, String relative) {
        try {
            Path resolved = root.resolve(relative).normalize();
            return resolved.startsWith(root) ? resolved : null;
        }
        catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Last resort: any directory whose layout is {@code <module>/src/main/java}. Directory names
     * alone are not trusted, because {@code foo/src/main/java} is a fact while {@code foo} being a
     * module is a guess.
     */
    private static List<String> detectByConvention(Path root) {
        Set<String> modules = new LinkedHashSet<>();
        try (Stream<Path> stream = Files.walk(root, MAX_BUILD_FILE_DEPTH + 1)) {
            stream.filter(Files::isDirectory)
                .filter(p -> !p.equals(root))
                .filter(p -> !SourceWalker.DEFAULT_EXCLUDED_DIRS
                    .contains(p.getFileName().toString().toLowerCase(Locale.ROOT)))
                .filter(p -> Files.isDirectory(p.resolve("src").resolve("main").resolve("java")))
                .forEach(p -> modules.add(root.relativize(p).toString().replace('\\', '/')));
        }
        catch (IOException e) {
            return List.of();
        }
        return new ArrayList<>(modules);
    }

    private static Map<String, List<String>> readDependencies(Path root, List<String> modules) {
        Map<String, String> moduleByArtifact = new LinkedHashMap<>();
        Map<String, List<String>> rawDependencies = new LinkedHashMap<>();

        for (String module : modules) {
            Path dir = root.resolve(module);
            String pom = read(dir.resolve("pom.xml"));
            if (pom != null) {
                String own = ownArtifactId(pom);
                if (own != null) {
                    moduleByArtifact.putIfAbsent(own, module);
                }
                rawDependencies.put(module, declaredArtifacts(pom));
                continue;
            }
            for (String name : List.of("build.gradle", "build.gradle.kts")) {
                String gradle = read(dir.resolve(name));
                if (gradle == null) {
                    continue;
                }
                List<String> deps = new ArrayList<>();
                Matcher m = GRADLE_PROJECT_DEP.matcher(gradle);
                while (m.find()) {
                    String path = toModulePath(m.group(1));
                    if (path != null) {
                        deps.add(path);
                    }
                }
                rawDependencies.put(module, deps);
                break;
            }
        }

        Map<String, List<String>> resolved = new LinkedHashMap<>();
        rawDependencies.forEach((module, deps) -> {
            List<String> targets = new ArrayList<>();
            for (String dep : deps) {
                String target = moduleByArtifact.get(dep);
                if (target == null) {
                    // Gradle dependencies are already module paths; keep the ones that exist.
                    target = modules.contains(dep) ? dep : null;
                }
                if (target != null && !target.equals(module) && !targets.contains(target)) {
                    targets.add(target);
                }
            }
            if (!targets.isEmpty()) {
                resolved.put(module, targets);
            }
        });
        return resolved;
    }

    /**
     * The project's own artifactId, which is the first one outside the {@code <parent>} block.
     */
    private static String ownArtifactId(String pom) {
        String withoutParent = PARENT_BLOCK.matcher(pom).replaceFirst("");
        Matcher m = ARTIFACT_ID.matcher(withoutParent);
        return m.find() ? m.group(1).trim() : null;
    }

    private static List<String> declaredArtifacts(String pom) {
        List<String> out = new ArrayList<>();
        Matcher blocks = DEPENDENCIES_BLOCK.matcher(pom);
        while (blocks.find()) {
            Matcher ids = ARTIFACT_ID.matcher(blocks.group(1));
            while (ids.find()) {
                String id = ids.group(1).trim();
                // <dependency> also carries groupId/version/scope; only artifactId is wanted, and
                // nested <exclusions> would contribute extra artifactIds — those are rare enough to
                // let the module lookup filter them out.
                if (!out.contains(id)) {
                    out.add(id);
                }
            }
        }
        return out;
    }

    private static List<Path> buildFiles(Path root, String fileName) {
        List<Path> out = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(root, MAX_BUILD_FILE_DEPTH)) {
            stream.filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().equalsIgnoreCase(fileName))
                .filter(p -> !isExcluded(root, p))
                .sorted()
                .forEach(out::add);
        }
        catch (IOException e) {
            return out;
        }
        return out;
    }

    private static boolean isExcluded(Path root, Path file) {
        Path rel = root.relativize(file);
        for (Path segment : rel) {
            if (SourceWalker.DEFAULT_EXCLUDED_DIRS.contains(segment.toString().toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static String read(Path file) {
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            if (Files.size(file) > MAX_BUILD_FILE_BYTES) {
                return null;
            }
            return Files.readString(file, StandardCharsets.UTF_8);
        }
        catch (IOException | RuntimeException e) {
            return null;
        }
    }

    @Override
    public String toString() {
        return isEmpty() ? "ModuleLayout[no modules]" : "ModuleLayout[" + modules.size() + " modules]";
    }
}

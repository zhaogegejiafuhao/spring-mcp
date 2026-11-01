package io.github.springmcp.core.index;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import io.github.springmcp.spi.ErrorCode;
import io.github.springmcp.spi.ToolFailure;
import io.github.springmcp.spi.api.IndexOptions;

/**
 * Finds the source files of a project, skipping everything that cannot contain hand written
 * application code.
 *
 * <p>
 * Both {@code .java} and {@code .kt} are collected. Kotlin is not a courtesy here: a Spring project
 * that mixes the two languages would otherwise get answers covering half of itself.
 * </p>
 *
 * <p>
 * Two decisions carry most of the performance: excluded directories are pruned with
 * {@link FileVisitResult#SKIP_SUBTREE} instead of being walked and filtered afterwards, and
 * symbolic links are not followed, which also makes directory cycles impossible.
 * </p>
 */
public final class SourceWalker {

    /** Directory names that never contain sources worth analysing. */
    public static final Set<String> DEFAULT_EXCLUDED_DIRS = Set.of("target", "build", "out", "bin", "classes",
            "test-classes", ".git", ".svn", ".hg", ".idea", ".vscode", ".settings", ".gradle", ".mvn", "node_modules",
            ".next", "dist", ".cache", ".sass-cache", "coverage",
            // Test fixtures, not application code. They are frequently invalid on purpose — parsers,
            // linters and IDE plugins ship deliberately broken samples — and indexing them invents
            // controllers and clients that the application never had. Names are lower-cased before
            // matching, so Gradle's src/testFixtures is covered by "testfixtures".
            "testdata", "test-data", "testfixtures");

    /** Directory names only skipped when generated sources are excluded. */
    public static final Set<String> GENERATED_DIRS = Set.of("generated-sources", "generated-test-sources", "generated");

    /**
     * Result of a walk.
     *
     * @param files discovered source files, sorted by path for deterministic output
     * @param warnings non-fatal notes, e.g. oversized files that were skipped
     * @param skippedLarge number of files skipped because they exceeded the size cap
     * @param kotlin what was taken in of the Kotlin half, so the index can state the coverage of
     * Kotlin support instead of leaving the caller to guess it
     */
    public record WalkResult(List<FileFingerprint> files, List<String> warnings, int skippedLarge,
            KotlinUsage kotlin) {

        public WalkResult {
            files = files == null ? List.of() : List.copyOf(files);
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
            kotlin = kotlin == null ? KotlinUsage.NONE : kotlin;
        }
    }

    /**
     * Walks a project root.
     * @param root directory to scan
     * @param options filtering and size limits
     * @return discovered files
     * @throws IOException when the tree cannot be read
     * @throws ToolFailure with {@link ErrorCode#INDEX_TOO_LARGE} when the file count exceeds the
     * configured cap
     */
    public WalkResult walk(Path root, IndexOptions options) throws IOException, ToolFailure {
        IgnoreRules ignore = IgnoreRules.load(root);
        List<FileFingerprint> files = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        int[] skippedLarge = { 0 };
        int[] kotlinFiles = { 0 };
        int[] kotlinSkipped = { 0 };

        Files.walkFileTree(root, new SimpleFileVisitor<>() {

            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (dir.equals(root)) {
                    return FileVisitResult.CONTINUE;
                }
                String name = dir.getFileName().toString().toLowerCase(Locale.ROOT);
                if (DEFAULT_EXCLUDED_DIRS.contains(name)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                if (!options.includeGenerated() && GENERATED_DIRS.contains(name)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                if (!options.includeTests() && isTestRoot(dir)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                if (matchesExtraExclude(root, dir, options.extraExcludes()) || ignore.excludesDirectory(dir)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                String name = file.getFileName().toString();
                String lower = name.toLowerCase(Locale.ROOT);
                // build.gradle.kts ends with ".kts", so build scripts are excluded by this test alone.
                boolean kotlin = lower.endsWith(".kt");
                if (!kotlin && !lower.endsWith(".java")) {
                    return FileVisitResult.CONTINUE;
                }
                if (!attrs.isRegularFile()) {
                    return FileVisitResult.CONTINUE;
                }
                if (ignore.excludesFile(file)) {
                    return FileVisitResult.CONTINUE;
                }
                if (attrs.size() > options.maxFileBytes()) {
                    if (kotlin) {
                        kotlinSkipped[0]++;
                    }
                    else {
                        skippedLarge[0]++;
                    }
                    warnings.add("跳过超大文件(" + attrs.size() + "B): " + file);
                    return FileVisitResult.CONTINUE;
                }
                if (files.size() >= options.maxIndexedFiles()) {
                    throw new TooManyFilesException(root, options.maxIndexedFiles());
                }
                if (kotlin) {
                    kotlinFiles[0]++;
                }
                files.add(new FileFingerprint(file.toAbsolutePath().normalize(), attrs.size(),
                        attrs.lastModifiedTime().toMillis()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                warnings.add("无法读取，已跳过: " + file + " (" + exc.getMessage() + ")");
                return FileVisitResult.CONTINUE;
            }
        });

        files.sort(Comparator.comparing(f -> f.path().toString()));
        return new WalkResult(files, warnings, skippedLarge[0], new KotlinUsage(kotlinFiles[0], kotlinSkipped[0]));
    }

    /**
     * Raised while walking as soon as the file cap is exceeded, so a huge repository is rejected
     * before thousands of files are parsed.
     */
    public static final class TooManyFilesException extends IOException {

        private static final long serialVersionUID = 1L;

        TooManyFilesException(Path root, int limit) {
            super("工程 " + root + " 的源码文件数超过上限 " + limit);
        }
    }

    /**
     * Whether a directory is a test source root.
     *
     * <p>
     * Matches {@code <anything>/src/test}, not just {@code <root>/src/test}. In a multi-module
     * repository the tests live one level deeper — {@code order-service/src/test/java} — and the
     * single-module check silently treated all of them as production code: on
     * {@code mybatis-spring-boot} it indexed 114 of 115 files when only 46 are main sources, and test
     * controllers and mappers ended up in answers about the application.
     * </p>
     *
     * @param dir directory being visited, never the root itself
     * @return whether its contents are test sources
     */
    private static boolean isTestRoot(Path dir) {
        Path name = dir.getFileName();
        Path parent = dir.getParent();
        if (name == null || parent == null) {
            return false;
        }
        Path parentName = parent.getFileName();
        return "test".equals(name.toString())
                && parentName != null && "src".equals(parentName.toString());
    }

    private static boolean matchesExtraExclude(Path root, Path dir, List<String> extra) {
        if (extra.isEmpty()) {
            return false;
        }
        String name = dir.getFileName().toString();
        String rel = root.relativize(dir).toString().replace('\\', '/');
        for (String rule : extra) {
            if (rule == null || rule.isBlank()) {
                continue;
            }
            String r = rule.trim();
            if (r.equalsIgnoreCase(name) || rel.equals(r) || rel.startsWith(r.endsWith("/") ? r : r + "/")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Minimal {@code .mcpignore} support: blank lines and {@code #} comments are ignored,
     * {@code dir/} excludes a directory name anywhere in the tree, {@code *.ext} excludes by
     * filename suffix. Anything else is ignored rather than guessed at — a half-implemented glob
     * engine silently excluding real sources would be worse than not supporting it.
     */
    static final class IgnoreRules {

        private final Set<String> dirNames = new HashSet<>();

        private final Set<String> suffixes = new HashSet<>();

        static IgnoreRules load(Path root) {
            IgnoreRules rules = new IgnoreRules();
            Path file = root.resolve(".mcpignore");
            if (!Files.isRegularFile(file)) {
                return rules;
            }
            try {
                for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    String line = raw.trim();
                    if (line.isEmpty() || line.startsWith("#")) {
                        continue;
                    }
                    rules.add(line);
                }
            }
            catch (IOException e) {
                // Ignoring an unreadable ignore-file is safer than failing the whole scan.
            }
            return rules;
        }

        void add(String rule) {
            if (rule.endsWith("/")) {
                dirNames.add(rule.substring(0, rule.length() - 1).toLowerCase(Locale.ROOT));
            }
            else if (rule.startsWith("*.")) {
                suffixes.add(rule.substring(1).toLowerCase(Locale.ROOT));
            }
            else if (rule.startsWith("*")) {
                suffixes.add(rule.substring(1).toLowerCase(Locale.ROOT));
            }
        }

        boolean excludesDirectory(Path dir) {
            return dirNames.contains(dir.getFileName().toString().toLowerCase(Locale.ROOT));
        }

        boolean excludesFile(Path file) {
            String lower = file.getFileName().toString().toLowerCase(Locale.ROOT);
            for (String s : suffixes) {
                if (lower.endsWith(s)) {
                    return true;
                }
            }
            return false;
        }
    }
}

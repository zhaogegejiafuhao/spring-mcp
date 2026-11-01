package io.github.springmcp.server.dev;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * One repository of the regression baseline, plus the exact tool calls frozen against it.
 *
 * <p>
 * The commit is pinned because a digest is meaningless against a moving target: if {@code HEAD} were
 * used, every upstream commit would turn the baseline red for reasons unrelated to spring-mcp.
 * </p>
 *
 * @param name short directory name, e.g. {@code spring-petclinic}
 * @param url git remote
 * @param commit full SHA to check out
 * @param stack human readable description, only used in diagnostics
 * @param queries tool name to arguments; the set is explicit per repository because a call such as
 * {@code find-service-callers} needs a class that actually exists there
 */
public record BaselineSpec(String name, String url, String commit, String stack,
        Map<String, Map<String, Object>> queries) {

    /** Marker file recording which commit a working copy is at. */
    private static final String COMMIT_MARKER = ".baseline-commit";

    /**
     * Loads a manifest.
     * @param manifest path to {@code manifest.json}
     * @return specs in file order
     * @throws IOException when the file cannot be read or parsed
     */
    public static List<BaselineSpec> load(Path manifest) throws IOException {
        JsonNode root = new ObjectMapper().readTree(Files.readString(manifest, StandardCharsets.UTF_8));
        List<BaselineSpec> specs = new ArrayList<>();
        for (JsonNode repo : root.path("repos")) {
            Map<String, Map<String, Object>> queries = new LinkedHashMap<>();
            for (Map.Entry<String, JsonNode> entry : repo.path("queries").properties()) {
                @SuppressWarnings("unchecked")
                Map<String, Object> args = new ObjectMapper().convertValue(entry.getValue(), Map.class);
                queries.put(entry.getKey(), args);
            }
            specs.add(new BaselineSpec(repo.path("name").asText(), repo.path("url").asText(),
                    repo.path("commit").asText(), repo.path("stack").asText(), queries));
        }
        return specs;
    }

    /**
     * Makes sure a working copy exists at the pinned commit, cloning if needed.
     *
     * <p>
     * Uses {@code git init} plus a shallow fetch of one exact SHA rather than a full clone: the
     * baseline includes spring-boot and spring-security, and a full history clone of those two is
     * hundreds of megabytes to end up on a single commit.
     * </p>
     *
     * @param workDir directory that holds all working copies, normally {@code target/baseline}
     * @return path to the checked out repository
     * @throws IOException when git fails; the message includes git's stderr
     */
    public Path ensure(Path workDir) throws IOException {
        Path dir = workDir.resolve(name);
        if (Files.isRegularFile(dir.resolve(COMMIT_MARKER))
                && commit.equals(Files.readString(dir.resolve(COMMIT_MARKER), StandardCharsets.UTF_8).trim())) {
            return dir;
        }

        deleteRecursively(dir);
        Files.createDirectories(dir);

        run(dir, "git", "init", "--quiet");
        // spring-boot contains test resource paths well over Windows' 260 character limit; without
        // this, checkout dies half way through and leaves an unusable working copy.
        run(dir, "git", "config", "core.longpaths", "true");
        run(dir, "git", "remote", "add", "origin", url);
        // GitHub serves arbitrary SHAs over fetch; a mirror in front of it does too.
        fetch(dir);
        run(dir, "git", "-c", "advice.detachedHead=false", "checkout", "--quiet", commit);

        Files.writeString(dir.resolve(COMMIT_MARKER), commit + "\n", StandardCharsets.UTF_8);
        return dir;
    }

    private void fetch(Path dir) throws IOException {
        try {
            run(dir, "git", "fetch", "--quiet", "--depth", "1", "origin", commit);
        }
        catch (IOException direct) {
            // Direct GitHub access is unreliable from some networks; retry through a mirror before
            // giving up, otherwise the baseline is unusable on those machines.
            String mirrored = "https://gh-proxy.com/" + url;
            run(dir, "git", "remote", "set-url", "origin", mirrored);
            try {
                run(dir, "git", "fetch", "--quiet", "--depth", "1", "origin", commit);
            }
            catch (IOException viaMirror) {
                viaMirror.addSuppressed(direct);
                throw viaMirror;
            }
        }
    }

    private static void run(Path dir, String... command) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(command).directory(dir.toFile());
        builder.redirectErrorStream(true);
        Process process = builder.start();
        String output;
        try (var stream = process.getInputStream()) {
            output = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        try {
            if (!process.waitFor(180, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("git 超时: " + String.join(" ", command));
            }
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("git 被中断", e);
        }
        if (process.exitValue() != 0) {
            throw new IOException("git 失败(" + process.exitValue() + "): " + String.join(" ", command)
                    + "\n" + output.strip());
        }
    }

    /**
     * Recursively deletes a directory tree.
     *
     * <p>
     * Git writes its pack files read-only, and Windows refuses to delete a read-only file, so a plain
     * recursive delete fails on a partial clone. The attribute is cleared and the delete retried —
     * without this, a repository that failed to check out once can never be re-cloned.
     * </p>
     *
     * @param dir root to remove; missing directory is fine
     * @throws IOException when a file cannot be removed
     */
    public static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(dir)) {
            for (Path path : stream.sorted(Comparator.reverseOrder()).toList()) {
                try {
                    Files.delete(path);
                }
                catch (java.nio.file.AccessDeniedException e) {
                    clearReadOnly(path);
                    Files.delete(path);
                }
            }
        }
        catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    private static void clearReadOnly(Path path) {
        try {
            Files.setAttribute(path, "dos:readonly", false);
        }
        catch (IOException | UnsupportedOperationException | IllegalArgumentException e) {
            // Not a DOS attribute view (non-Windows) or the file is gone; the retry will report it.
        }
    }
}

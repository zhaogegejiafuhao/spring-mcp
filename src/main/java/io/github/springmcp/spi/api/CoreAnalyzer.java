package io.github.springmcp.spi.api;

import java.nio.file.Path;

import io.github.springmcp.spi.ToolFailure;

/**
 * The only analysis capability exposed to tools and plugins.
 *
 * <p>
 * Implementations are thread-safe, cache one index snapshot per project root, and rebuild it
 * transparently when files change. Calling {@link #open(Path, IndexOptions)} repeatedly with the
 * same root is expected and cheap — do not cache handles in a field unless you want to pin a
 * snapshot.
 * </p>
 *
 * <p>
 * Notice what is <em>not</em> here: no JavaParser types, no MCP types, no file walking helpers.
 * Keeping the surface this narrow is what lets the SPI jar stay dependency-free.
 * </p>
 */
public interface CoreAnalyzer {

    /**
     * Opens (or reuses) the index of a project.
     * @param projectRoot directory containing the sources; will be normalised
     * @param options build options, must not be {@code null}
     * @return a handle over an immutable snapshot
     * @throws ToolFailure when the path is invalid, the project is too large, or indexing failed
     * or timed out
     */
    IndexHandle open(Path projectRoot, IndexOptions options) throws ToolFailure;

    /**
     * Opens an index with {@link IndexOptions#defaults()}.
     * @param projectRoot directory containing the sources
     * @return a handle over an immutable snapshot
     * @throws ToolFailure see {@link #open(Path, IndexOptions)}
     */
    default IndexHandle open(Path projectRoot) throws ToolFailure {
        return open(projectRoot, IndexOptions.defaults());
    }

    /**
     * Reads a source file as UTF-8 text. Intended for plugins that need the raw bytes to run
     * their own parser.
     * @param file absolute or project-relative path
     * @return file content
     * @throws ToolFailure when the file does not exist or cannot be read
     */
    String readSource(Path file) throws ToolFailure;

    /**
     * Drops every cached snapshot. Exposed mainly for tests and for a long-running server that
     * wants to release memory.
     */
    void invalidateAll();
}

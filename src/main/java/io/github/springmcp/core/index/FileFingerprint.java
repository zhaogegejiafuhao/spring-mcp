package io.github.springmcp.core.index;

import java.nio.file.Path;

/**
 * Identity of a source file at a point in time.
 *
 * <p>
 * Size plus modification time is enough to detect an edit in practice: any real edit changes at
 * least one of them, and reading the file to compute a hash would cost more than the rebuild it
 * is meant to avoid.
 * </p>
 *
 * @param path absolute, normalised path
 * @param size size in bytes
 * @param lastModifiedMillis last modification time in epoch milliseconds
 */
public record FileFingerprint(Path path, long size, long lastModifiedMillis) {
}

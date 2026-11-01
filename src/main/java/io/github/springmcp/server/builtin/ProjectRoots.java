package io.github.springmcp.server.builtin;

import java.nio.file.Path;

import io.github.springmcp.spi.ErrorCode;
import io.github.springmcp.spi.ToolFailure;
import io.github.springmcp.spi.api.IndexHandle;

/**
 * Guards the entry point every analysis tool shares.
 *
 * <p>
 * A wrong {@code projectRoot} is the easiest way to use these tools incorrectly: a parent directory,
 * a front-end folder, a path that only looks like a module. Without a check, every tool answers
 * {@code 0 endpoints, 0 controllers, 0 mappers} for such a directory — indistinguishable from a
 * service that really exposes nothing, and the caller believes the empty answer.
 * </p>
 *
 * <p>
 * The check lives here rather than in the index: an empty project is a legitimate snapshot that must
 * cache and must not rebuild in a loop (see {@code IndexCacheStoreTest.emptyProjectStillCaches}).
 * Only the tool surface has to refuse it.
 * </p>
 */
final class ProjectRoots {

    private ProjectRoots() {
    }

    /**
     * @param root directory the caller asked for
     * @param handle snapshot of it
     * @return the same handle
     * @throws ToolFailure when the snapshot contains no source file
     */
    static IndexHandle requireSources(Path root, IndexHandle handle) throws ToolFailure {
        if (handle.metadata().fileCount() > 0) {
            return handle;
        }
        throw ToolFailure.of(ErrorCode.INVALID_ARGUMENT,
                "目录下没有 Java/Kotlin 源码: " + root,
                "确认 projectRoot 是工程根目录。常见误传：传了父目录、传了前端目录（只有 package.json），"
                        + "或多模块工程传了聚合模块。可先用 list-modules 看模块清单，再指向具体模块。",
                null);
    }
}

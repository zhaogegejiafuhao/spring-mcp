package io.github.springmcp.core.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The cache has to bound itself, or it becomes the problem it was supposed to solve.
 *
 * <p>
 * Every analysed repository leaves one ~36 MB entry behind and nothing ever removed them: the
 * real-world corpus alone would leave 8 GB on disk. Trimming happens once per process, after a
 * successful write, so the entry just written — the newest one — can never be the one evicted.
 * </p>
 */
class IndexCachePruneTest {

    /**
     * @param cacheDir cache root
     * @param name project hash directory
     * @param bytes size of the fake index file
     * @param epochSecond modification time, which is what eviction order is based on
     * @return the created entry directory
     */
    private static Path entry(Path cacheDir, String name, int bytes, long epochSecond) throws IOException {
        Path dir = cacheDir.resolve(name);
        Files.createDirectories(dir);
        Path file = dir.resolve(IndexCacheFormat.FILE_NAME);
        Files.write(file, new byte[bytes]);
        Files.setLastModifiedTime(file, FileTime.from(Instant.ofEpochSecond(epochSecond)));
        return dir;
    }

    @Test
    @DisplayName("the cache is trimmed to its budget by dropping the oldest projects first")
    void oldestEntriesGoFirst(@TempDir Path cacheDir) throws Exception {
        Path oldest = entry(cacheDir, "aaa", 100, 1_000);
        Path middle = entry(cacheDir, "bbb", 100, 2_000);
        Path newest = entry(cacheDir, "ccc", 100, 3_000);

        int removed = IndexCacheStore.at(cacheDir).prune(200);

        assertThat(removed).isEqualTo(1);
        assertThat(oldest).doesNotExist();
        assertThat(middle).exists();
        assertThat(newest).exists();
    }

    @Test
    @DisplayName("nothing is deleted while the cache fits in its budget")
    void nothingRemovedUnderBudget(@TempDir Path cacheDir) throws Exception {
        Path only = entry(cacheDir, "aaa", 100, 1_000);

        assertThat(IndexCacheStore.at(cacheDir).prune(1_000)).isZero();

        assertThat(only).exists();
    }

    @Test
    @DisplayName("a stale .tmp file is reclaimed even when the cache is under budget")
    void leftoverTempFileIsRemoved(@TempDir Path cacheDir) throws Exception {
        Path dir = entry(cacheDir, "aaa", 10, 1_000);
        Path tmp = dir.resolve(IndexCacheFormat.FILE_NAME + ".tmp");
        Files.write(tmp, new byte[10]);
        // Older than the grace period: this is debris from a write that died, not one in flight.
        Files.setLastModifiedTime(tmp, FileTime.from(Instant.now().minusSeconds(3_600)));

        IndexCacheStore.at(cacheDir).prune(1_000);

        assertThat(tmp).doesNotExist();
        assertThat(dir.resolve(IndexCacheFormat.FILE_NAME)).exists();
    }

    @Test
    @DisplayName("a .tmp file being written right now is left alone")
    void freshTempFileSurvives(@TempDir Path cacheDir) throws Exception {
        Path dir = entry(cacheDir, "aaa", 10, 1_000);
        Path tmp = dir.resolve(IndexCacheFormat.FILE_NAME + ".tmp");
        Files.write(tmp, new byte[10]);

        IndexCacheStore.at(cacheDir).prune(1_000);

        assertThat(tmp)
            .describedAs("另一个进程可能正在写这个文件，删掉它会让那边的原子 move 失败")
            .exists();
    }

    @Test
    @DisplayName("a disabled store prunes nothing and does not throw")
    void disabledStoreIsInert(@TempDir Path cacheDir) throws Exception {
        Path kept = entry(cacheDir, "aaa", 100, 1_000);

        assertThat(IndexCacheStore.disabled().prune(0)).isZero();

        assertThat(kept).exists();
    }
}

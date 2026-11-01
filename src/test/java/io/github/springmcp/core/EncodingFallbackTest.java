package io.github.springmcp.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.springmcp.spi.api.IndexHandle;
import io.github.springmcp.spi.api.IndexOptions;
import io.github.springmcp.spi.model.Controllers;

/**
 * A source file in GBK must still be indexed.
 *
 * <p>
 * Java only mandated UTF-8 in Java 18; plenty of working code predates that. Reading strictly as
 * UTF-8 throws on the first Chinese comment, and that used to turn into a parse problem — 57 files,
 * 60% of one repository, disappeared from the index while the tool happily answered "no endpoints".
 * The fallback is GB18030, and the fact that it was needed is reported rather than hidden.
 * </p>
 */
class EncodingFallbackTest {

    private static final String CONTROLLER = """
            package e;

            import org.springframework.web.bind.annotation.GetMapping;
            import org.springframework.web.bind.annotation.RestController;

            /** 商品控制器：这个文件用 GBK 保存。 */
            @RestController
            public class GoodsController {

                @GetMapping("/goods")
                public String list() {
                    return "商品";
                }
            }
            """;

    private static void write(Path root, String relative, byte[] content) throws Exception {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.write(file, content);
    }

    @Test
    @DisplayName("a GBK file is decoded with GB18030 instead of becoming a parse problem")
    void gbkSourceIsIndexed(@TempDir Path root) throws Exception {
        Charset gbk = Charset.forName("GB18030");
        write(root, "src/main/java/e/GoodsController.java", CONTROLLER.getBytes(gbk));

        IndexHandle index = new IndexRegistry(IndexRegistry.Revalidation.NEVER, 60_000)
            .open(root, IndexOptions.defaults());

        assertThat(index.metadata().problems()).isEmpty();
        assertThat(index.metadata().fileCount()).isEqualTo(1);
        assertThat(index.metadata().warnings()).anyMatch(warning -> warning.contains("GB18030"));
        assertThat(index.controllers(new Controllers.Query(false, null, null)).endpoints())
            .extracting(Controllers.Endpoint::path)
            .containsExactly("/goods");
    }

    @Test
    @DisplayName("a UTF-8 byte order mark does not break the declaration on line one")
    void bomIsIgnored(@TempDir Path root) throws Exception {
        byte[] body = CONTROLLER.getBytes(StandardCharsets.UTF_8);
        byte[] withBom = new byte[body.length + 3];
        withBom[0] = (byte) 0xEF;
        withBom[1] = (byte) 0xBB;
        withBom[2] = (byte) 0xBF;
        System.arraycopy(body, 0, withBom, 3, body.length);
        write(root, "src/main/java/e/GoodsController.java", withBom);

        IndexHandle index = new IndexRegistry(IndexRegistry.Revalidation.NEVER, 60_000)
            .open(root, IndexOptions.defaults());

        assertThat(index.metadata().problems()).isEmpty();
        // A BOM is still UTF-8: it is handled, not reported as an encoding fallback.
        assertThat(index.metadata().warnings()).noneMatch(warning -> warning.contains("不是 UTF-8"));
        assertThat(index.controllers(new Controllers.Query(false, null, null)).endpoints())
            .extracting(Controllers.Endpoint::path)
            .containsExactly("/goods");
    }

    @Test
    @DisplayName("a pure UTF-8 project reports no encoding warning at all")
    void utf8IsQuiet(@TempDir Path root) throws Exception {
        write(root, "src/main/java/e/GoodsController.java", CONTROLLER.getBytes(StandardCharsets.UTF_8));

        IndexHandle index = new IndexRegistry(IndexRegistry.Revalidation.NEVER, 60_000)
            .open(root, IndexOptions.defaults());

        assertThat(index.metadata().warnings()).noneMatch(warning -> warning.contains("个文件不是 UTF-8"));
    }
}

package io.github.springmcp.spi;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Architecture guard for the plugin SPI.
 *
 * <p>
 * The SPI must stay compilable against the JDK alone, so that a plugin author's only dependency is
 * this jar and their code is insulated from whatever JSON, AST or protocol library the server
 * happens to use. That rule used to be enforced by {@code maven-enforcer-plugin} while the SPI lived
 * in its own Maven module; after collapsing to a single module there is no build-time boundary left,
 * so this test takes over.
 * </p>
 *
 * <p>
 * It works on source text rather than bytecode on purpose: reading imports names the offending file
 * and line directly, which is what a reviewer needs. A {@code CompilationUnit} appearing in an SPI
 * signature is precisely the failure mode being prevented — it would bind plugin authors to
 * JavaParser's internal node layout and throw {@code ClassCastException} across plugin classloaders.
 * </p>
 */
class SpiPurityTest {

    private static final List<String> ALLOWED_PREFIXES = List.of("java.", "javax.", "io.github.springmcp.spi.");

    /**
     * Locates {@code src/main/java/io/github/springmcp/spi} by walking up from the working directory.
     * @return the SPI source root
     */
    private static Path spiSourceRoot() {
        Path candidate = Path.of("").toAbsolutePath();
        for (int depth = 0; depth < 6 && candidate != null; depth++) {
            Path root = candidate.resolve("src/main/java/io/github/springmcp/spi");
            if (Files.isDirectory(root)) {
                return root;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException("找不到 SPI 源码目录，测试无法校验架构约束");
    }

    @Test
    @DisplayName("no class under io.github.springmcp.spi imports a third-party library")
    void spiImportsOnlyJdk() throws IOException {
        List<String> violations = new ArrayList<>();

        try (Stream<Path> files = Files.walk(spiSourceRoot())) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i).trim();
                    if (!line.startsWith("import ")) {
                        continue;
                    }
                    String imported = line.substring("import ".length()).replace("static ", "").replace(";", "").trim();
                    if (ALLOWED_PREFIXES.stream().noneMatch(imported::startsWith)) {
                        violations.add(file.getFileName() + ":" + (i + 1) + " -> " + imported);
                    }
                }
            }
        }

        assertThat(violations)
            .as("SPI 只能依赖 JDK：MCP SDK / Jackson / JavaParser / SLF4J 等一律不允许出现在 import 里")
            .isEmpty();
    }

    @Test
    @DisplayName("the guard actually sees the SPI sources")
    void guardIsEffective() throws IOException {
        try (Stream<Path> files = Files.walk(spiSourceRoot())) {
            assertThat(files.filter(p -> p.toString().endsWith(".java")).count())
                .as("至少应扫到插件 API 的若干源文件，否则这条约束等于没生效")
                .isGreaterThan(10);
        }
    }
}

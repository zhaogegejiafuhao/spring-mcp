package io.github.springmcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Architecture guard for the one dependency direction the design depends on: {@code server → core → spi}.
 *
 * <p>
 * Collapsing to a single Maven module removed the compiler's ability to enforce this. {@code
 * SpiPurityTest} covers the SPI's external dependencies (JDK only), but says nothing about who may
 * import whom inside this project — so {@code core} could start importing {@code server} and every
 * build would stay green while the layering quietly became a cycle.
 * </p>
 *
 * <p>
 * The failure mode is not theoretical: a {@code core} analyzer that wants "the default project root
 * from the launcher config" is one convenient import away, and after that the index can no longer be
 * used without a server around it — which is exactly what the plugin API and the offline CLI both
 * rely on not being true.
 * </p>
 */
class LayeringTest {

    /** What each package is allowed to see, in the direction the arrows actually point. */
    private static final Map<String, List<String>> FORBIDDEN = Map.of(
            // core is the analysis engine: usable from a test, the CLI or a plugin without a server.
            "core", List.of("io.github.springmcp.server."),
            // spi is the contract plugin authors compile against: it must not know the engine either.
            "spi", List.of("io.github.springmcp.core.", "io.github.springmcp.server."));

    private static final String PACKAGE_ROOT = "io/github/springmcp";

    /**
     * Walks up from the working directory until the main source root is found.
     *
     * @return {@code src/main/java/io/github/springmcp}
     */
    private static Path sourceRoot() {
        Path candidate = Path.of("").toAbsolutePath();
        for (int depth = 0; depth < 6 && candidate != null; depth++) {
            Path root = candidate.resolve("src/main/java").resolve(PACKAGE_ROOT);
            if (Files.isDirectory(root)) {
                return root;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException("找不到主源码目录，测试无法校验分层约束");
    }

    @Test
    @DisplayName("core does not import server, spi does not import core or server")
    void layersOnlyPointDownwards() throws IOException {
        List<String> violations = new ArrayList<>();
        int scanned = 0;

        for (Map.Entry<String, List<String>> rule : FORBIDDEN.entrySet()) {
            Path packageDir = sourceRoot().resolve(rule.getKey());
            assertThat(packageDir).as("包目录应存在: %s", packageDir).isDirectory();

            try (Stream<Path> files = Files.walk(packageDir)) {
                for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                    scanned++;
                    violations.addAll(violationsIn(file, rule.getValue()));
                }
            }
        }

        assertThat(violations)
            .as("依赖方向必须是 server → core → spi；反向 import 会让 core / spi 离不开服务端")
            .isEmpty();
        // Without this the rule silently stops applying if the package is renamed or moved.
        assertThat(scanned).as("至少应扫到 core 与 spi 下的若干源文件").isGreaterThan(50);
    }

    @Test
    @DisplayName("the guard actually recognises a forbidden import")
    void guardIsEffective(@org.junit.jupiter.api.io.TempDir Path dir) throws IOException {
        Path file = dir.resolve("Offender.java");
        Files.writeString(file, """
                package io.github.springmcp.core;

                import io.github.springmcp.server.LauncherConfig;

                class Offender {
                }
                """, StandardCharsets.UTF_8);

        assertThat(violationsIn(file, List.of("io.github.springmcp.server.")))
            .singleElement()
            .asString()
            .contains("Offender.java:3")
            .contains("io.github.springmcp.server.LauncherConfig");
    }

    /**
     * @param file source file to inspect
     * @param forbidden import prefixes this package may not use
     * @return one line per offending import, with file and line number
     */
    private static List<String> violationsIn(Path file, List<String> forbidden) throws IOException {        List<String> violations = new ArrayList<>();
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (!line.startsWith("import ")) {
                continue;
            }
            String imported = line.substring("import ".length()).replace("static ", "").replace(";", "").trim();
            if (forbidden.stream().anyMatch(imported::startsWith)) {
                violations.add(file.getFileName() + ":" + (i + 1) + " -> " + imported);
            }
        }
        return violations;
    }
}

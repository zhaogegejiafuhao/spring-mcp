package io.github.springmcp.spi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Argument coercion is where tool bugs come from, so every branch is covered. */
class ArgsTest {

    @Test
    @DisplayName("required string rejects blank and trims")
    void requireStringTrims() throws ToolFailure {
        assertThat(new Args(Map.of("k", "  v  ")).requireString("k")).isEqualTo("v");

        assertThatThrownBy(() -> new Args(Map.of()).requireString("k"))
            .isInstanceOf(ToolFailure.class)
            .satisfies(e -> assertThat(((ToolFailure) e).code()).isEqualTo(ErrorCode.INVALID_ARGUMENT));
        assertThatThrownBy(() -> new Args(Map.of("k", "   ")).requireString("k"))
            .isInstanceOf(ToolFailure.class);
    }

    @Test
    @DisplayName("booleans accept JSON booleans and the strings true/false")
    void optionalBoolean() throws ToolFailure {
        Args args = new Args(Map.of("a", true, "b", "false", "c", "TRUE"));

        assertThat(args.optionalBoolean("a", false)).isTrue();
        assertThat(args.optionalBoolean("b", true)).isFalse();
        assertThat(args.optionalBoolean("c", false)).isTrue();
        assertThat(args.optionalBoolean("missing", true)).isTrue();
    }

    @Test
    @DisplayName("a non-boolean value is rejected instead of silently defaulting")
    void optionalBooleanRejectsGarbage() {
        assertThatThrownBy(() -> new Args(Map.of("a", "yes please")).optionalBoolean("a", false))
            .isInstanceOf(ToolFailure.class);
    }

    @Test
    @DisplayName("integers are coerced from any JSON number and clamped to the range")
    void optionalInt() throws ToolFailure {
        Args args = new Args(Map.of("i", 5, "l", 5L, "d", 5.0, "s", "7"));

        assertThat(args.optionalInt("i", 0, 1, 10)).isEqualTo(5);
        assertThat(args.optionalInt("l", 0, 1, 10)).isEqualTo(5);
        assertThat(args.optionalInt("d", 0, 1, 10)).isEqualTo(5);
        assertThat(args.optionalInt("s", 0, 1, 10)).isEqualTo(7);
        assertThat(args.optionalInt("missing", 42, 1, 100)).isEqualTo(42);
        assertThat(args.optionalInt("i", 0, 1, 3)).isEqualTo(3);
        assertThat(args.optionalInt("i", 0, 10, 100)).isEqualTo(10);
    }

    @Test
    @DisplayName("a non-numeric integer is rejected")
    void optionalIntRejectsText() {
        assertThatThrownBy(() -> new Args(Map.of("i", "abc")).optionalInt("i", 0, 0, 10))
            .isInstanceOf(ToolFailure.class);
    }

    @Test
    @DisplayName("a single string is promoted to a one-element list")
    void optionalStringList() throws ToolFailure {
        assertThat(new Args(Map.of("k", "A")).optionalStringList("k")).containsExactly("A");
        assertThat(new Args(Map.of("k", List.of(" A ", "B", "  "))).optionalStringList("k"))
            .containsExactly("A", "B");
        assertThat(new Args(Map.of()).optionalStringList("k")).isEmpty();
        assertThatThrownBy(() -> new Args(Map.of("k", 3)).optionalStringList("k"))
            .isInstanceOf(ToolFailure.class);
    }

    @Test
    @DisplayName("directories are validated with distinct error codes")
    void directoryValidation(@TempDir Path temp) throws Exception {
        Args args = new Args(Map.of("dir", temp.toString(), "file", Files.createFile(temp.resolve("a.java"))
            .toString(),
                "missing", temp.resolve("nope").toString()));

        assertThat(args.requireExistingDirectory("dir")).isEqualTo(temp.toAbsolutePath().normalize());

        assertThatThrownBy(() -> args.requireExistingDirectory("file"))
            .isInstanceOf(ToolFailure.class)
            .satisfies(e -> assertThat(((ToolFailure) e).code()).isEqualTo(ErrorCode.PATH_NOT_DIRECTORY));
        assertThatThrownBy(() -> args.requireExistingDirectory("missing"))
            .isInstanceOf(ToolFailure.class)
            .satisfies(e -> assertThat(((ToolFailure) e).code()).isEqualTo(ErrorCode.PATH_NOT_FOUND));
    }

    @Test
    @DisplayName("an absent optional directory falls back instead of throwing")
    void optionalDirectoryFallsBack(@TempDir Path temp) throws ToolFailure {
        Args args = new Args(Map.of());
        Path fallback = temp.toAbsolutePath();
        assertThat(args.optionalExistingDirectory("dir", fallback)).isEqualTo(fallback);
    }

    @Test
    @DisplayName("files are validated like directories but require a regular file")
    void fileValidation(@TempDir Path temp) throws Exception {
        Path file = Files.createFile(temp.resolve("a.java"));
        Args args = new Args(Map.of("file", file.toString(), "dir", temp.toString()));

        assertThat(args.requireExistingFile("file")).isEqualTo(file.toAbsolutePath().normalize());
        assertThatThrownBy(() -> args.requireExistingFile("dir")).isInstanceOf(ToolFailure.class);
    }

    @Test
    @DisplayName("ToolFailure renders a code, message and hint")
    void failureRendering() {
        ToolFailure f = ToolFailure.of(ErrorCode.PATH_NOT_FOUND, "目录不存在", "检查路径");
        assertThat(f.render()).isEqualTo("[PATH_NOT_FOUND] 目录不存在\n提示: 检查路径");

        ToolFailure noHint = ToolFailure.of(ErrorCode.INTERNAL_ERROR, "boom");
        assertThat(noHint.render()).isEqualTo("[INTERNAL_ERROR] boom");
        assertThat(noHint.hint()).isNull();
    }
}

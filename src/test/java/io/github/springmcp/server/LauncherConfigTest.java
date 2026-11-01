package io.github.springmcp.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * Alias parsing is small but full of traps — a Windows drive letter that looks like an alias, a
 * repeated alias that silently shadows a project, a bare path that needs a sensible display name.
 */
class LauncherConfigTest {

    @Test
    @DisplayName("a bare path becomes a project named after its folder")
    void barePathUsesFolderName(@TempDir Path temp) {
        Path root = temp.resolve("order-service");
        LauncherConfig config = LauncherConfig.parse(new String[] { "--project=" + root });

        assertThat(config.projects()).singleElement().satisfies(project -> {
            assertThat(project.name()).isEqualTo("order-service");
            assertThat(project.root()).isEqualTo(root.toAbsolutePath().normalize());
        });
        assertThat(config.projectRoot()).isEqualTo(root.toAbsolutePath().normalize());
    }

    @Test
    @DisplayName("an explicit alias is honoured and the first project is the default")
    void explicitAliases() {
        LauncherConfig config = LauncherConfig.parse(
                new String[] { "--project=api:/work/api", "--project=web:/work/web" });

        assertThat(config.projects()).extracting(ProjectAlias::name).containsExactly("api", "web");
        assertThat(config.catalog().defaultName()).isEqualTo("api");
        assertThat(config.catalog().byAlias("web")).isPresent();
    }

    // Windows-only by nature: on POSIX "C:\work\app" is one file name, not a path, so the
    // folder name is the whole string. The drive-letter branch below only ever runs on Windows.
    @Test
    @EnabledOnOs(OS.WINDOWS)
    @DisplayName("a Windows drive letter is not mistaken for an alias")
    void windowsDriveLetterIsAPath() {
        ProjectAlias project = LauncherConfig.parseProject("C:\\work\\app");

        assertThat(project.name()).isEqualTo("app");
        assertThat(project.root().toString()).startsWith("C:");
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    @DisplayName("a Windows path can still carry an alias")
    void aliasWithWindowsPath() {
        ProjectAlias project = LauncherConfig.parseProject("svc:C:\\work\\app");

        assertThat(project.name()).isEqualTo("svc");
        assertThat(project.root().toString()).startsWith("C:");
    }

    @Test
    @DisplayName("a repeated alias is renamed instead of shadowing the first project")
    void duplicateAliasIsRenamed() {
        LauncherConfig config = LauncherConfig.parse(
                new String[] { "--project=app:/work/one", "--project=app:/work/two" });

        assertThat(config.projects()).extracting(ProjectAlias::name).containsExactly("app", "app-2");
        assertThat(config.catalog().byAlias("app-2")).isPresent();
    }

    @Test
    @DisplayName("no --project leaves an empty catalog rather than failing")
    void noProjects() {
        LauncherConfig config = LauncherConfig.parse(new String[] { "--include-tests" });

        assertThat(config.projects()).isEmpty();
        assertThat(config.projectRoot()).isNull();
        assertThat(config.catalog().isEmpty()).isTrue();
        assertThat(config.catalog().describeAvailable()).contains("未配置");
    }

    @Test
    @DisplayName("an unknown option is rejected loudly")
    void unknownOption() {
        assertThatThrownBy(() -> LauncherConfig.parse(new String[] { "--nope=1" }))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("未知参数");
    }

    @Test
    @DisplayName("a missing value is rejected")
    void missingValue() {
        assertThatThrownBy(() -> LauncherConfig.parse(new String[] { "--project=" }))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("arguments after --dev are forwarded verbatim and not parsed as server options")
    void devArgumentsAreForwarded() {
        LauncherConfig config = LauncherConfig.parse(
                new String[] { "--dev", "controllers", "--root", "/x", "--project=/ignored" });

        assertThat(config.devMode()).isTrue();
        assertThat(config.devArgs()).containsExactly("controllers", "--root", "/x", "--project=/ignored");
        assertThat(config.projects()).isEmpty();
    }

    @Test
    @DisplayName("--index-cache defaults to on and accepts off")
    void indexCacheMode() {
        assertThat(LauncherConfig.parse(new String[] {}).indexCacheMode()).isEqualTo("on");
        assertThat(LauncherConfig.parse(new String[] { "--index-cache=off" }).indexCacheMode()).isEqualTo("off");
        assertThat(LauncherConfig.parse(new String[] { "--index-cache=on" }).indexCacheMode()).isEqualTo("on");
        assertThat(LauncherConfig.parse(new String[] { "--index-cache=OFF" }).indexCacheMode()).isEqualTo("off");
    }

    @Test
    @DisplayName("an unknown cache mode is rejected instead of silently choosing one")
    void indexCacheModeRejectsGarbage() {
        assertThatThrownBy(() -> LauncherConfig.parse(new String[] { "--index-cache=maybe" }))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("--index-cache");
    }

    @Test
    @DisplayName("--max-indexed-files reaches the index options")
    void maxIndexedFilesReachesOptions() {
        LauncherConfig config = LauncherConfig.parse(new String[] { "--max-indexed-files=42" });

        assertThat(config.indexOptions().maxIndexedFiles()).isEqualTo(42);
        assertThatThrownBy(() -> LauncherConfig.parse(new String[] { "--max-indexed-files=0" }))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("alias lookup ignores case, because a caller will not preserve it")
    void aliasLookupIsCaseInsensitive(@TempDir Path temp) {
        LauncherConfig config = LauncherConfig.parse(new String[] { "--project=OrderService:" + temp });

        assertThat(config.catalog().byAlias("orderservice")).isPresent();
        assertThat(config.catalog().byAlias("ORDERSERVICE")).isPresent();
    }
}

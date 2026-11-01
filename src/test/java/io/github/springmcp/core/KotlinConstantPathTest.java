package io.github.springmcp.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.springmcp.spi.api.IndexOptions;
import io.github.springmcp.spi.model.Controllers;

/**
 * The constant folding rules are language-neutral, and Kotlin has two shapes of its own.
 *
 * <p>
 * {@code const val} can be defined in terms of another one, exactly like a Java constant, and a
 * single-element path array is written {@code arrayOf("/x")} rather than {@code {"/x"}}. Both are
 * plainly readable, so both are folded; an interpolation ({@code "$base/x"}) is not, and stays
 * unresolved.
 * </p>
 */
class KotlinConstantPathTest {

    private static void write(Path root, String relative, String content) throws Exception {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    @Test
    @DisplayName("a Kotlin const chain and an arrayOf path both fold to the served path")
    void constChainAndArrayOf(@TempDir Path root) throws Exception {
        write(root, "src/main/kotlin/k/Api.kt", """
                package k

                object Api {
                    const val BASE = "/api"
                    const val V1 = BASE + "/v1"
                }
                """);
        write(root, "src/main/kotlin/k/ItemController.kt", """
                package k

                import org.springframework.web.bind.annotation.GetMapping
                import org.springframework.web.bind.annotation.RequestMapping
                import org.springframework.web.bind.annotation.RestController

                @RestController
                @RequestMapping(Api.V1)
                class ItemController {

                    @GetMapping(arrayOf("/list"))
                    fun list(): String = "ok"
                }
                """);

        Controllers.Report report = new IndexRegistry(IndexRegistry.Revalidation.NEVER, 60_000)
            .open(root, IndexOptions.defaults())
            .controllers(new Controllers.Query(false, null, null));

        assertThat(report.endpoints()).singleElement().satisfies(endpoint -> {
            assertThat(endpoint.pathResolved()).isTrue();
            assertThat(endpoint.path()).isEqualTo("/api/v1/list");
        });
    }

    @Test
    @DisplayName("a constant in a companion object is reachable through the enclosing type")
    void companionConstant(@TempDir Path root) throws Exception {
        write(root, "src/main/kotlin/k/ApiContract.kt", """
                package k

                interface ApiContract {

                    companion object {
                        const val API_PATH = "/api"
                    }

                    @GetMapping(ApiContract.API_PATH + "/items")
                    fun items(): String
                }
                """);
        write(root, "src/main/kotlin/k/ApiImpl.kt", """
                package k

                import org.springframework.web.bind.annotation.RestController

                @RestController
                class ApiImpl : ApiContract {
                    override fun items(): String = "ok"
                }
                """);

        Controllers.Report report = new IndexRegistry(IndexRegistry.Revalidation.NEVER, 60_000)
            .open(root, IndexOptions.defaults())
            .controllers(new Controllers.Query(false, null, null));

        assertThat(report.endpoints()).singleElement().satisfies(endpoint -> {
            assertThat(endpoint.pathResolved()).isTrue();
            assertThat(endpoint.path()).isEqualTo("/api/items");
        });
    }

    @Test
    @DisplayName("a Kotlin string template is a placeholder, not a constant")
    void templateStaysUnresolved(@TempDir Path root) throws Exception {
        write(root, "src/main/kotlin/k/StatusController.kt", """
                package k

                import org.springframework.web.bind.annotation.GetMapping
                import org.springframework.web.bind.annotation.RestController

                private const val BASE = "/api"

                @RestController
                class StatusController {

                    @GetMapping("$BASE/status")
                    fun status(): String = "ok"
                }
                """);

        Controllers.Report report = new IndexRegistry(IndexRegistry.Revalidation.NEVER, 60_000)
            .open(root, IndexOptions.defaults())
            .controllers(new Controllers.Query(false, null, null));

        assertThat(report.endpoints()).singleElement()
            .satisfies(endpoint -> assertThat(endpoint.pathResolved()).isFalse());
    }
}

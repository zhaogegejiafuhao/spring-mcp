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
 * Controllers that do not carry {@code @Controller}.
 *
 * <p>
 * Spring's default handler mapping only accepts types annotated with {@code @Controller} or
 * {@code @RequestMapping}, but a project may install its own mapping. spring-boot-admin does: its
 * controllers carry {@code @AdminController} — a bare marker with no meta-annotation — plus
 * {@code @ResponseBody}, and a custom {@code RequestMappingHandlerMapping} registers them. Reading
 * only the standard annotations reported zero endpoints for a server whose REST API is the point.
 * </p>
 *
 * <p>
 * The rule is deliberately narrow, and these tests pin the narrowness down: a marker is required, and
 * a class without mapping methods is never a controller no matter what it is annotated with.
 * </p>
 */
class ImplicitControllerTest {

    private static void write(Path root, String relative, String content) throws Exception {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private static Controllers.Report analyze(Path root) throws Exception {
        return new IndexRegistry(IndexRegistry.Revalidation.NEVER, 60_000)
            .open(root, IndexOptions.defaults())
            .controllers(new Controllers.Query(false, null, null));
    }

    @Test
    @DisplayName("a marker annotation plus @ResponseBody is a controller, and says so")
    void markerAnnotationIsAController(@TempDir Path root) throws Exception {
        write(root, "src/main/java/p/AdminController.java", """
                package p;

                import java.lang.annotation.ElementType;
                import java.lang.annotation.Retention;
                import java.lang.annotation.RetentionPolicy;
                import java.lang.annotation.Target;

                @Target(ElementType.TYPE)
                @Retention(RetentionPolicy.RUNTIME)
                public @interface AdminController {
                }
                """);
        write(root, "src/main/java/p/ApplicationsController.java", """
                package p;

                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.ResponseBody;

                @AdminController
                @ResponseBody
                public class ApplicationsController {

                    @GetMapping("/applications")
                    public String list() {
                        return "";
                    }

                    @GetMapping("/applications/{name}")
                    public String one() {
                        return "";
                    }
                }
                """);

        Controllers.Report report = analyze(root);

        assertThat(report.controllerCount()).isEqualTo(1);
        assertThat(report.endpoints()).extracting(Controllers.Endpoint::path)
            .containsExactlyInAnyOrder("/applications", "/applications/{name}");
        // Inferred, not declared: the caller has to be able to see which classes were guessed at.
        assertThat(report.warnings()).anyMatch(w -> w.contains("推断") && w.contains("ApplicationsController"));
    }

    @Test
    @DisplayName("an annotation named ...Controller is enough on its own")
    void controllerNamedAnnotationIsEnough(@TempDir Path root) throws Exception {
        write(root, "src/main/java/p/BasePathAwareController.java", """
                package p;

                @interface BasePathAwareController {
                }
                """);
        write(root, "src/main/java/p/PersonController.java", """
                package p;

                import org.springframework.web.bind.annotation.RequestMapping;

                @BasePathAwareController
                public class PersonController {

                    @RequestMapping("/people")
                    public String all() {
                        return "";
                    }
                }
                """);

        Controllers.Report report = analyze(root);

        assertThat(report.endpoints()).extracting(Controllers.Endpoint::path).containsExactly("/people");
    }

    @Test
    @DisplayName("a marker without mapping methods is not a controller")
    void markerWithoutMappingsIsNotAController(@TempDir Path root) throws Exception {
        write(root, "src/main/java/p/AdminController.java", """
                package p;

                @interface AdminController {
                }
                """);
        write(root, "src/main/java/p/AdminConstants.java", """
                package p;

                import org.springframework.web.bind.annotation.ResponseBody;

                @AdminController
                @ResponseBody
                public class AdminConstants {

                    public static final String PATH = "/admin";

                    public String describe() {
                        return PATH;
                    }
                }
                """);

        Controllers.Report report = analyze(root);

        assertThat(report.controllerCount()).isZero();
        assertThat(report.endpoints()).isEmpty();
    }

    @Test
    @DisplayName("mapping methods alone are not enough: the marker has to be there")
    void mappingMethodsAloneAreNotAController(@TempDir Path root) throws Exception {
        // A helper that happens to call @GetMapping-annotated methods, or a scanner that stores the
        // annotation as a string, must not turn into phantom endpoints.
        write(root, "src/main/java/p/PathHelper.java", """
                package p;

                import org.springframework.web.bind.annotation.GetMapping;

                public class PathHelper {

                    @GetMapping("/not-a-real-endpoint")
                    public String build() {
                        return "";
                    }
                }
                """);

        Controllers.Report report = analyze(root);

        assertThat(report.controllerCount()).isZero();
        assertThat(report.endpoints()).isEmpty();
    }
}

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
 * Conditional assembly is reported, not evaluated.
 *
 * <p>
 * {@code @Profile("dev")} does not change a URL, it decides whether the handler exists in the running
 * application. A caller comparing the endpoint list with production traffic would otherwise treat a
 * dev-only route as "missing in production" or, worse, plan a change against an endpoint that is never
 * registered. The condition is reported as written because evaluating it needs the active environment,
 * which a static tool does not have.
 * </p>
 */
class ConditionalEndpointTest {

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
    @DisplayName("a @Profile on the controller reaches every endpoint it declares")
    void profileOnController(@TempDir Path root) throws Exception {
        write(root, "src/main/java/p/DevController.java", """
                package p;

                import org.springframework.context.annotation.Profile;
                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.RestController;

                @RestController
                @Profile("dev")
                public class DevController {

                    @GetMapping("/debug")
                    public String debug() {
                        return "";
                    }
                }
                """);

        Controllers.Report report = analyze(root);

        assertThat(report.endpoints()).singleElement()
            .satisfies(endpoint -> assertThat(endpoint.conditions()).containsExactly("@Profile(\"dev\")"));
        assertThat(report.warnings()).anyMatch(w -> w.contains("条件装配") && w.contains("@Profile(\"dev\")"));
    }

    @Test
    @DisplayName("a @ConditionalOnProperty on the handler is reported with its arguments")
    void conditionalOnPropertyOnHandler(@TempDir Path root) throws Exception {
        write(root, "src/main/java/p/FeatureController.java", """
                package p;

                import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.RestController;

                @RestController
                public class FeatureController {

                    @GetMapping("/plain")
                    public String plain() {
                        return "";
                    }

                    @GetMapping("/feature")
                    @ConditionalOnProperty(name = "feature.new-api", havingValue = "true")
                    public String feature() {
                        return "";
                    }
                }
                """);

        Controllers.Report report = analyze(root);

        assertThat(report.endpoints()).filteredOn(e -> "/feature".equals(e.path()))
            .singleElement()
            .satisfies(endpoint -> assertThat(endpoint.conditions())
                .containsExactly("@ConditionalOnProperty(name=\"feature.new-api\", havingValue=\"true\")"));
        // The unconditional handler stays clean, so the field is a signal rather than noise.
        assertThat(report.endpoints()).filteredOn(e -> "/plain".equals(e.path()))
            .singleElement()
            .satisfies(endpoint -> assertThat(endpoint.conditions()).isEmpty());
    }

    @Test
    @DisplayName("a project without conditional assembly reports nothing extra")
    void noConditionsNoWarning(@TempDir Path root) throws Exception {
        write(root, "src/main/java/p/PlainController.java", """
                package p;

                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.RestController;

                @RestController
                public class PlainController {

                    @GetMapping("/plain")
                    public String plain() {
                        return "";
                    }
                }
                """);

        Controllers.Report report = analyze(root);

        assertThat(report.endpoints()).allSatisfy(e -> assertThat(e.conditions()).isEmpty());
        assertThat(report.warnings()).noneMatch(w -> w.contains("条件装配"));
    }
}

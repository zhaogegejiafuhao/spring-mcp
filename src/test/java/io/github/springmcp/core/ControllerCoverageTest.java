package io.github.springmcp.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.springmcp.spi.api.IndexHandle;
import io.github.springmcp.spi.api.IndexOptions;
import io.github.springmcp.spi.model.Controllers;

/**
 * A {@code @Controller} that serves no HTTP endpoint must be named, not silently counted.
 *
 * <p>
 * RSocket's {@code @MessageMapping} handlers live on a {@code @Controller} but produce no URL, and a
 * view-only controller may have none either. Counting them as controllers and printing nothing for
 * them makes "9 controllers, 3 endpoints" read as if six controllers were broken.
 * </p>
 */
class ControllerCoverageTest {

    @Test
    @DisplayName("a controller without HTTP mappings is listed in the warnings")
    void controllersWithoutHandlersAreNamed(@TempDir Path root) throws Exception {
        write(root, "src/main/java/a/HttpController.java", """
                package a;

                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.RestController;

                @RestController
                public class HttpController {

                    @GetMapping("/a")
                    public String a() {
                        return "a";
                    }
                }
                """);
        write(root, "src/main/java/a/RsocketController.java", """
                package a;

                import org.springframework.messaging.handler.annotation.MessageMapping;
                import org.springframework.stereotype.Controller;
                import reactor.core.publisher.Mono;

                @Controller
                public class RsocketController {

                    @MessageMapping("greet")
                    public Mono<String> greet(String name) {
                        return Mono.just("hi " + name);
                    }
                }
                """);

        IndexHandle index = new IndexRegistry(IndexRegistry.Revalidation.NEVER, 60_000)
            .open(root, IndexOptions.defaults());

        Controllers.Report report = index.controllers(new Controllers.Query(false, null, null));

        assertThat(report.controllerCount()).isEqualTo(2);
        assertThat(report.endpoints()).extracting(Controllers.Endpoint::path).containsExactly("/a");
        assertThat(report.warnings())
            .anyMatch(warning -> warning.contains("1 个 controller 没有任何 HTTP 映射")
                    && warning.contains("RsocketController"));
    }

    @Test
    @DisplayName("a controller whose handlers are all inherited is not reported as empty")
    void inheritedHandlersCount(@TempDir Path root) throws Exception {
        write(root, "src/main/java/b/Base.java", """
                package b;

                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.RequestMapping;

                @RequestMapping("/base")
                public abstract class Base {

                    @GetMapping("/ping")
                    public String ping() {
                        return "pong";
                    }
                }
                """);
        write(root, "src/main/java/b/Child.java", """
                package b;

                import org.springframework.web.bind.annotation.RestController;

                @RestController
                public class Child extends Base {
                }
                """);

        Controllers.Report report = new IndexRegistry(IndexRegistry.Revalidation.NEVER, 60_000)
            .open(root, IndexOptions.defaults())
            .controllers(new Controllers.Query(false, null, null));

        assertThat(report.endpoints()).extracting(Controllers.Endpoint::path).containsExactly("/base/ping");
        assertThat(report.warnings()).noneMatch(warning -> warning.contains("没有任何 HTTP 映射"));
    }

    @Test
    @DisplayName("a regex path constraint keeps its backslash")
    void regexConstraintIsPreserved(@TempDir Path root) throws Exception {
        // "/detail/{id:\\d+}" in source is the pattern {id:\d+}. Rewriting the backslash to a slash
        // produced "/detail/{id:/d+}", which reads like a route and matches nothing.
        write(root, "src/main/java/r/DetailController.java", """
                package r;

                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.PathVariable;
                import org.springframework.web.bind.annotation.RestController;

                @RestController
                public class DetailController {

                    @GetMapping("/detail/{id:\\\\d+}")
                    public String detail(@PathVariable Long id) {
                        return "";
                    }
                }
                """);

        Controllers.Report report = new IndexRegistry(IndexRegistry.Revalidation.NEVER, 60_000)
            .open(root, IndexOptions.defaults())
            .controllers(new Controllers.Query(false, null, null));

        assertThat(report.endpoints()).singleElement()
            .satisfies(endpoint -> assertThat(endpoint.path()).isEqualTo("/detail/{id:\\d+}"));
    }

    @Test
    @DisplayName("a global path prefix configuration is reported, not silently ignored")
    void globalPrefixIsReported(@TempDir Path root) throws Exception {
        write(root, "src/main/java/p/ApiController.java", """
                package p;

                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.RestController;

                @RestController
                public class ApiController {

                    @GetMapping("/crm/customer")
                    public String list() {
                        return "";
                    }
                }
                """);
        write(root, "src/main/java/p/WebConfig.java", """
                package p;

                import org.springframework.context.annotation.Bean;
                import org.springframework.context.annotation.Configuration;
                import org.springframework.web.servlet.config.annotation.WebMvcRegistrations;

                @Configuration
                public class WebConfig {

                    @Bean
                    public WebMvcRegistrations registrations() {
                        return new WebMvcRegistrations() {
                        };
                    }
                }
                """);

        Controllers.Report report = new IndexRegistry(IndexRegistry.Revalidation.NEVER, 60_000)
            .open(root, IndexOptions.defaults())
            .controllers(new Controllers.Query(false, null, null));

        assertThat(report.endpoints()).extracting(Controllers.Endpoint::path).containsExactly("/crm/customer");
        // The prefix lives in configuration, so the annotation-level path is all that can be reported;
        // saying so is what keeps a later comparison with the front end from looking like a bug.
        assertThat(report.warnings()).anyMatch(w -> w.contains("全局路径前缀") && w.contains("WebConfig"));
    }

    private static void write(Path root, String relative, String content) throws Exception {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    @Test
    @DisplayName("a mass of mapping-less controllers points at a non-Spring framework")
    void nonSpringFrameworkHint(@TempDir Path root) throws Exception {
        // Solon marks classes with @Controller but maps methods with its own @Mapping. Reporting
        // "67 controllers, 0 endpoints" is right and reads like a broken scanner; three reviewers
        // asked exactly that about opensolon/solon. The sheer count is the tell.
        for (int i = 0; i < 12; i++) {
            write(root, "src/main/java/p/C" + i + ".java", String.format("""
                    package p;

                    import org.springframework.stereotype.Controller;

                    @Controller
                    public class C%d {

                        @Mapping("/c%d")
                        public String index() {
                            return "";
                        }
                    }
                    """, i, i));
        }

        Controllers.Report report = new IndexRegistry(IndexRegistry.Revalidation.NEVER, 60_000)
            .open(root, IndexOptions.defaults())
            .controllers(new Controllers.Query(false, null, null));

        assertThat(report.endpoints()).isEmpty();
        assertThat(report.warnings())
            .anyMatch(w -> w.contains("没有任何 HTTP 映射") && w.contains("别的 Web 框架"));
    }

    @Test
    @DisplayName("a couple of view controllers stay a plain statement, no framework speculation")
    void smallCountGetsNoHint(@TempDir Path root) throws Exception {
        write(root, "src/main/java/p/View.java", """
                package p;

                import org.springframework.stereotype.Controller;

                @Controller
                public class View {

                    public String show() {
                        return "";
                    }
                }
                """);

        Controllers.Report report = new IndexRegistry(IndexRegistry.Revalidation.NEVER, 60_000)
            .open(root, IndexOptions.defaults())
            .controllers(new Controllers.Query(false, null, null));

        assertThat(report.warnings()).anyMatch(w -> w.contains("没有任何 HTTP 映射"));
        assertThat(report.warnings()).noneMatch(w -> w.contains("别的 Web 框架"));
    }
}

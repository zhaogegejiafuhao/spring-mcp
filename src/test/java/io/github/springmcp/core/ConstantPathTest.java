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
 * Compile-time constants in mapping paths are folded, not reported as variable names.
 *
 * <p>
 * {@code @RequestMapping(ApiConst.BASE)} looks unresolvable but is not: the value is written in the
 * same project. Leaving it as {@code ApiConst.BASE} is honest and useless — in the 110-repository
 * corpus it hid 258 endpoints, 46% of one project's routes and 100% of another's. What must stay
 * unresolved is the genuinely dynamic half: property placeholders, injected values, expressions.
 * </p>
 */
class ConstantPathTest {

    private static IndexHandle open(Path root) throws Exception {
        return new IndexRegistry(IndexRegistry.Revalidation.NEVER, 60_000).open(root, IndexOptions.defaults());
    }

    private static void write(Path root, String relative, String content) throws Exception {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private static void constants(Path root) throws Exception {
        write(root, "src/main/java/c/consts/ApiConst.java", """
                package c.consts;

                public final class ApiConst {

                    public static final String BASE = "/api";

                    /** A constant defined in terms of another one: very common, and still foldable. */
                    public static final String FRONT = BASE + "/front";

                    /** A placeholder is not a constant: the property resolver runs at start-up. */
                    public static final String VERSIONED = "/api/${version}";

                    /** Short of a literal: the value is a StringBuilder, so it is not a constant. */
                    public static final StringBuilder BUILDER = new StringBuilder("/built");

                    public static final class Support {
                        public static final String PREFIX = "/support";
                    }
                }
                """);
    }

    @Test
    @DisplayName("a constant inherited from an implemented interface is resolved")
    void inheritedInterfaceConstant(@TempDir Path root) throws Exception {
        write(root, "src/main/java/c/api/Contract.java", """
                package c.api;

                public interface Contract {
                    String API_PREFIX = "/feign/notice";
                }
                """);
        write(root, "src/main/java/c/web/NoticeEndpoint.java", """
                package c.web;

                import c.api.Contract;
                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.RestController;

                @RestController
                public class NoticeEndpoint implements Contract {

                    @GetMapping(API_PREFIX + "/top")
                    public String top() {
                        return "";
                    }
                }
                """);

        Controllers.Report report = open(root).controllers(new Controllers.Query(false, null, null));

        assertThat(report.endpoints()).singleElement().satisfies(endpoint -> {
            assertThat(endpoint.pathResolved()).isTrue();
            assertThat(endpoint.path()).isEqualTo("/feign/notice/top");
        });
    }

    @Test
    @DisplayName("a constant defined in terms of another constant is folded through the chain")
    void chainedConstant(@TempDir Path root) throws Exception {
        constants(root);
        write(root, "src/main/java/c/web/FrontController.java", """
                package c.web;

                import c.consts.ApiConst;
                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.RequestMapping;
                import org.springframework.web.bind.annotation.RestController;

                @RestController
                @RequestMapping(ApiConst.FRONT)
                public class FrontController {

                    @GetMapping("/home")
                    public String home() {
                        return "";
                    }
                }
                """);

        Controllers.Report report = open(root).controllers(new Controllers.Query(false, null, null));

        assertThat(report.endpoints()).singleElement().satisfies(endpoint -> {
            assertThat(endpoint.pathResolved()).isTrue();
            // ApiConst.FRONT is declared as BASE + "/front", one hop away.
            assertThat(endpoint.path()).isEqualTo("/api/front/home");
        });
    }

    @Test
    @DisplayName("an interface constant is a constant even when final is not written")
    void interfaceConstant(@TempDir Path root) throws Exception {
        write(root, "src/main/java/c/api/Paths.java", """
                package c.api;

                public interface Paths {

                    String PREFIX = "/sys";

                    String USERS = PREFIX + "/users";
                }
                """);
        write(root, "src/main/java/c/web/UserController.java", """
                package c.web;

                import c.api.Paths;
                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.RequestMapping;
                import org.springframework.web.bind.annotation.RestController;

                @RestController
                @RequestMapping(Paths.USERS)
                public class UserController {

                    @GetMapping("/list")
                    public String list() {
                        return "";
                    }
                }
                """);

        Controllers.Report report = open(root).controllers(new Controllers.Query(false, null, null));

        assertThat(report.endpoints()).singleElement().satisfies(endpoint -> {
            assertThat(endpoint.pathResolved()).isTrue();
            assertThat(endpoint.path()).isEqualTo("/sys/users/list");
        });
    }

    @Test
    @DisplayName("an unqualified constant resolves against the type that declares the annotation")
    void siblingConstant(@TempDir Path root) throws Exception {
        write(root, "src/main/java/c/web/SiblingController.java", """
                package c.web;

                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.RequestMapping;
                import org.springframework.web.bind.annotation.RestController;

                @RestController
                @RequestMapping(SiblingController.PREFIX)
                public class SiblingController {

                    public static final String PREFIX = "/sibling";

                    @GetMapping(SUFFIX)
                    public String one() {
                        return "";
                    }

                    public static final String SUFFIX = "/one";
                }
                """);

        Controllers.Report report = open(root).controllers(new Controllers.Query(false, null, null));

        assertThat(report.endpoints()).singleElement()
            .satisfies(endpoint -> assertThat(endpoint.path()).isEqualTo("/sibling/one"));
    }

    @Test
    @DisplayName("a constant declared in another module is followed through its own imports")
    void crossModuleChain(@TempDir Path root) throws Exception {
        write(root, "src/main/java/shared/Base.java", """
                package shared;

                public final class Base {
                    public static final String ROOT = "/root";
                }
                """);
        write(root, "src/main/java/c/api/Api.java", """
                package c.api;

                import shared.Base;

                public final class Api {
                    public static final String V1 = Base.ROOT + "/v1";
                }
                """);
        write(root, "src/main/java/c/web/DeepController.java", """
                package c.web;

                import c.api.Api;
                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.RestController;

                @RestController
                public class DeepController {

                    @GetMapping(Api.V1 + "/deep")
                    public String deep() {
                        return "";
                    }
                }
                """);

        Controllers.Report report = open(root).controllers(new Controllers.Query(false, null, null));

        assertThat(report.endpoints()).singleElement()
            .satisfies(endpoint -> assertThat(endpoint.path()).isEqualTo("/root/v1/deep"));
    }

    @Test
    @DisplayName("a constant qualifier is resolved, including a nested constant class")
    void qualifierAndNestedClass(@TempDir Path root) throws Exception {
        constants(root);
        write(root, "src/main/java/c/web/SupportController.java", """
                package c.web;

                import c.consts.ApiConst;
                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.RequestMapping;
                import org.springframework.web.bind.annotation.RestController;

                @RestController
                @RequestMapping(ApiConst.Support.PREFIX)
                public class SupportController {

                    @GetMapping(ApiConst.BASE + "/list")
                    public String list() {
                        return "";
                    }
                }
                """);

        Controllers.Report report = open(root).controllers(new Controllers.Query(false, null, null));

        assertThat(report.endpoints()).singleElement().satisfies(endpoint -> {
            assertThat(endpoint.path()).isEqualTo("/support/api/list");
            assertThat(endpoint.pathResolved()).isTrue();
        });
    }

    @Test
    @DisplayName("a statically imported constant is resolved by its import")
    void staticImport(@TempDir Path root) throws Exception {
        constants(root);
        write(root, "src/main/java/c/web/PingController.java", """
                package c.web;

                import static c.consts.ApiConst.BASE;

                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.RestController;

                @RestController
                public class PingController {

                    @GetMapping(BASE + "/ping")
                    public String ping() {
                        return "pong";
                    }
                }
                """);

        Controllers.Report report = open(root).controllers(new Controllers.Query(false, null, null));

        assertThat(report.endpoints()).singleElement()
            .satisfies(endpoint -> assertThat(endpoint.path()).isEqualTo("/api/ping"));
    }

    @Test
    @DisplayName("a placeholder stays unresolved even when the constant holding it is known")
    void placeholderSurvives(@TempDir Path root) throws Exception {
        constants(root);
        write(root, "src/main/java/c/web/VersionedController.java", """
                package c.web;

                import c.consts.ApiConst;
                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.RestController;

                @RestController
                public class VersionedController {

                    @GetMapping(ApiConst.VERSIONED + "/status")
                    public String status() {
                        return "ok";
                    }
                }
                """);

        Controllers.Report report = open(root).controllers(new Controllers.Query(false, null, null));

        assertThat(report.endpoints()).singleElement().satisfies(endpoint -> {
            // Folding a property placeholder would claim the application serves "${version}" as a
            // literal path segment, which it does not.
            assertThat(endpoint.pathResolved()).isFalse();
            assertThat(endpoint.unresolvedReason()).isEqualTo("PLACEHOLDER");
            assertThat(endpoint.path()).contains("${version}");
        });
        assertThat(report.warnings()).anyMatch(warning -> warning.contains("pathResolved=false"));
    }

    @Test
    @DisplayName("a non-literal field is not treated as a constant")
    void nonConstantFieldStaysUnresolved(@TempDir Path root) throws Exception {
        write(root, "src/main/java/c/web/InjectedController.java", """
                package c.web;

                import org.springframework.beans.factory.annotation.Value;
                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.RestController;

                @RestController
                public class InjectedController {

                    @Value("${api.prefix}")
                    private String prefix;

                    @GetMapping(prefix)
                    public String x() {
                        return "";
                    }
                }
                """);

        Controllers.Report report = open(root).controllers(new Controllers.Query(false, null, null));

        assertThat(report.endpoints()).singleElement().satisfies(endpoint -> {
            assertThat(endpoint.pathResolved()).isFalse();
            assertThat(endpoint.unresolvedReason()).isEqualTo("LITERAL_CONSTANT");
            assertThat(endpoint.path()).contains("prefix");
        });    }

    @Test
    @DisplayName("a constant defined outside the project stays unresolved instead of guessed")
    void externalConstantStaysUnresolved(@TempDir Path root) throws Exception {
        write(root, "src/main/java/c/web/ExternalController.java", """
                package c.web;

                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.RestController;

                @RestController
                public class ExternalController {

                    @GetMapping(SomeLibrary.PREFIX + "/y")
                    public String y() {
                        return "";
                    }
                }
                """);

        Controllers.Report report = open(root).controllers(new Controllers.Query(false, null, null));

        assertThat(report.endpoints()).singleElement()
            .satisfies(endpoint -> assertThat(endpoint.pathResolved()).isFalse());
    }

    @Test
    @DisplayName("a regex constraint ending in the $ anchor is still a resolved path")
    void dollarAnchorIsNotAPlaceholder(@TempDir Path root) throws Exception {
        // {login:^[a-z]*$} ends with the regex end-anchor. Reading any "$" as a template marked a
        // fully folded path as undecidable, which is indistinguishable from a path the tool really
        // cannot know — and the caller has no way to tell the two apart.
        write(root, "src/main/java/c/web/Const.java", """
                package c.web;

                public final class Const {
                    public static final String LOGIN_REGEX = "^[_.@A-Za-z0-9-]*$";
                }
                """);
        write(root, "src/main/java/c/web/UserResource.java", """
                package c.web;

                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.RestController;

                @RestController
                public class UserResource {

                    @GetMapping("/users/{login:" + Const.LOGIN_REGEX + "}")
                    public String one() {
                        return "";
                    }
                }
                """);

        Controllers.Report report = open(root).controllers(new Controllers.Query(false, null, null));

        assertThat(report.endpoints()).singleElement().satisfies(endpoint -> {
            assertThat(endpoint.pathResolved()).isTrue();
            assertThat(endpoint.path()).isEqualTo("/users/{login:^[_.@A-Za-z0-9-]*$}");
        });
    }

    @Test
    @DisplayName("a property placeholder stays unresolved even next to a constant")
    void placeholderStillWins(@TempDir Path root) throws Exception {
        write(root, "src/main/java/c/web/VersionedController.java", """
                package c.web;

                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.RestController;

                @RestController
                public class VersionedController {

                    @GetMapping("/v/${api.version}/items")
                    public String items() {
                        return "";
                    }
                }
                """);

        Controllers.Report report = open(root).controllers(new Controllers.Query(false, null, null));

        assertThat(report.endpoints()).singleElement().satisfies(endpoint -> {
            assertThat(endpoint.pathResolved()).isFalse();
            assertThat(endpoint.unresolvedReason()).isEqualTo("PLACEHOLDER");
        });
    }
}

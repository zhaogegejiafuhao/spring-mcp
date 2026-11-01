package io.github.springmcp.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.springmcp.core.index.SourceIndex;
import io.github.springmcp.spi.ToolFailure;
import io.github.springmcp.spi.model.Annotations;
import io.github.springmcp.spi.model.Controllers;
import io.github.springmcp.spi.model.Dependencies;
import io.github.springmcp.spi.model.Mappers;
import io.github.springmcp.spi.model.ResolutionRule;
import io.github.springmcp.spi.model.ServiceCalls;

/**
 * End-to-end analysis over the fixture projects. Each fixture is small enough that the expected
 * numbers can be verified by reading the sources.
 */
class FixtureAnalysisTest {

    private static IndexRegistry registry;

    @BeforeAll
    static void setUp() {
        // NEVER: no re-walking between assertions, so the tests stay fast and deterministic.
        registry = new IndexRegistry(IndexRegistry.Revalidation.NEVER, 60_000);
    }

    private static SourceIndex index(String fixture) throws ToolFailure {
        return (SourceIndex) registry.open(Fixtures.path(fixture));
    }

    // ------------------------------------------------------------------ controllers

    @Test
    @DisplayName("list-controllers finds every verb/path combination and flags unresolved paths")
    void controllers() throws ToolFailure {
        Controllers.Report report = index("demo-web")
            .controllers(new Controllers.Query(false, null, null));

        assertThat(report.controllerCount()).isEqualTo(4);
        assertThat(report.methodCount()).isEqualTo(7);
        // 5 from OrderController (legacy maps two verbs), 2 from the two class prefixes of
        // UserController, 1 each from the constant-based and placeholder-based controllers.
        assertThat(report.endpoints()).hasSize(8);

        assertThat(report.endpoints()).extracting(Controllers.Endpoint::path)
            .contains("/api/orders", "/api/orders/{id}", "/api/orders/legacy", "/u/list", "/users/list");

        Controllers.Endpoint legacy = report.endpoints()
            .stream()
            .filter(e -> "/api/orders/legacy".equals(e.path()))
            .findFirst()
            .orElseThrow();
        assertThat(legacy.httpMethods()).containsExactly("GET", "POST");

        Controllers.Endpoint constant = report.endpoints()
            .stream()
            .filter(e -> "ping".equals(e.handlerMethod()))
            .findFirst()
            .orElseThrow();
        // The constant is declared in the same fixture, so the path is known, not guessed.
        assertThat(constant.pathResolved()).isTrue();
        assertThat(constant.path()).isEqualTo("/admin/ping");

        Controllers.Endpoint placeholder = report.endpoints()
            .stream()
            .filter(e -> "status".equals(e.handlerMethod()))
            .findFirst()
            .orElseThrow();
        assertThat(placeholder.unresolvedReason()).isEqualTo("PLACEHOLDER");

        assertThat(report.warnings()).anyMatch(w -> w.contains("无法静态求值"));
    }

    @Test
    @DisplayName("list-controllers filters by HTTP method")
    void controllersFilterByVerb() throws ToolFailure {
        Controllers.Report report = index("demo-web").controllers(new Controllers.Query(false, null, "POST"));
        assertThat(report.endpoints()).isNotEmpty().allMatch(e -> e.httpMethods().contains("POST"));
        assertThat(report.endpoints()).extracting(Controllers.Endpoint::handlerMethod).doesNotContain("list");
    }

    // ------------------------------------------------------------------ callers

    @Test
    @DisplayName("find-service-callers resolves every syntactic receiver rule and reports the rest")
    void serviceCallers() throws ToolFailure {
        ServiceCalls.Report report = index("layered-callers")
            .findServiceCallers(new ServiceCalls.Query("OrderService", null, true, true, false, 100));

        assertThat(report.targetFqn()).isEqualTo("com.acme.order.OrderService");
        assertThat(report.resolvedCount()).isEqualTo(5);
        assertThat(report.unresolvedCount()).isEqualTo(1);
        assertThat(report.truncated()).isFalse();

        assertThat(report.callSites()).extracting(ServiceCalls.CallSite::rule)
            .containsExactlyInAnyOrder(ResolutionRule.FIELD, ResolutionRule.FIELD, ResolutionRule.METHOD_PARAM,
                    ResolutionRule.METHOD_PARAM, ResolutionRule.LOCAL_VAR);

        assertThat(report.callSites()).extracting(ServiceCalls.CallSite::callerType)
            .contains("com.acme.order.OrderFacade", "com.acme.web.OrderController", "com.acme.job.PaymentJob",
                    "com.acme.report.ReportService", "com.acme.util.Helper");

        assertThat(report.callSites()).allMatch(c -> c.confidence().name().equals("HIGH"));
        assertThat(report.callSites()).allMatch(c -> !c.codeSnippet().isEmpty());

        assertThat(report.unresolved()).singleElement().satisfies(u -> {
            assertThat(u.reason()).isEqualTo("CHAINED_CALL");
            assertThat(u.callerType()).isEqualTo("com.acme.job.BadJob");
        });
    }

    @Test
    @DisplayName("find-service-callers honours a method filter")
    void serviceCallersWithMethodName() throws ToolFailure {
        ServiceCalls.Report all = index("layered-callers")
            .findServiceCallers(new ServiceCalls.Query("OrderService", null, true, true, false, 100));
        ServiceCalls.Report filtered = index("layered-callers")
            .findServiceCallers(new ServiceCalls.Query("OrderService", "notThere", true, true, false, 100));

        assertThat(all.resolvedCount()).isPositive();
        assertThat(filtered.resolvedCount()).isZero();
    }

    @Test
    @DisplayName("find-service-callers rejects an unknown class with a clear code")
    void serviceCallersUnknownClass() throws ToolFailure {
        SourceIndex index = index("layered-callers");
        try {
            index.findServiceCallers(new ServiceCalls.Query("NoSuchService", null, true, true, false, 10));
        }
        catch (ToolFailure failure) {
            assertThat(failure.code()).isEqualTo(io.github.springmcp.spi.ErrorCode.CLASS_NOT_FOUND);
            assertThat(failure.hint()).isNotBlank();
            return;
        }
        org.assertj.core.api.Assertions.fail("expected ToolFailure");
    }

    // ------------------------------------------------------------------ mappers

    @Test
    @DisplayName("list-mappers merges the MyBatis interface and XML sides and reports orphans")
    void mybatisMappers() throws ToolFailure {
        Mappers.Report report = index("mybatis-xml").mappers(new Mappers.Query(Set.of(), null));

        Mappers.Descriptor userMapper = report.mappers()
            .stream()
            .filter(m -> "UserMapper".equals(m.simpleName()))
            .findFirst()
            .orElseThrow();

        assertThat(userMapper.kind()).isEqualTo(Mappers.Kind.ANNOTATION);
        assertThat(userMapper.xmlPath()).isNotNull();
        assertThat(userMapper.declaredMethods()).contains("findAll", "insert", "findByXml");
        assertThat(userMapper.statements()).extracting(Mappers.Statement::id)
            .contains("com.acme.mapper.UserMapper.findAll", "com.acme.mapper.UserMapper.findByXml",
                    "com.acme.mapper.UserMapper.Base_Column_List");

        Mappers.Statement xmlStatement = userMapper.statements()
            .stream()
            .filter(s -> "findByXml".equals(s.name()))
            .findFirst()
            .orElseThrow();
        assertThat(xmlStatement.commandType()).isEqualTo("SELECT");
        assertThat(xmlStatement.parameterType()).isEqualTo("java.lang.Long");
        assertThat(xmlStatement.resultType()).isEqualTo("com.acme.model.User");
        // Proves the DOCTYPE did not stop parsing and <include> was followed.
        assertThat(xmlStatement.includes()).contains("Base_Column_List");

        assertThat(userMapper.statements()).anyMatch(Mappers.Statement::fragment);
        assertThat(report.orphans()).hasSize(1);
        assertThat(report.orphans().get(0)).endsWith("OrphanMapper.xml");
    }

    @Test
    @DisplayName("list-mappers detects Spring Data repositories and their generics")
    void jpaRepositories() throws ToolFailure {
        Mappers.Report report = index("jpa-repos").mappers(new Mappers.Query(Set.of(Mappers.Kind.JPA), null));

        assertThat(report.mappers()).singleElement().satisfies(m -> {
            assertThat(m.kind()).isEqualTo(Mappers.Kind.JPA);
            assertThat(m.interfaceFqn()).isEqualTo("com.acme.repo.OrderRepository");
            assertThat(m.entityType()).isEqualTo("Order");
            assertThat(m.idType()).isEqualTo("Long");
            assertThat(m.statements()).extracting(Mappers.Statement::name).contains("findBiggerThan");
        });
    }

    // ------------------------------------------------------------------ annotations

    @Test
    @DisplayName("search-by-annotation matches by simple name and says so")
    void searchByAnnotation() throws ToolFailure {
        Annotations.Report report = index("jpa-repos")
            .findByAnnotation(new Annotations.Query("Query", Set.of(), false, 100));

        assertThat(report.hits()).singleElement().satisfies(h -> {
            assertThat(h.elementKind()).isEqualTo(Annotations.ElementKind.METHOD);
            assertThat(h.ownerType()).isEqualTo("com.acme.repo.OrderRepository");
            assertThat(h.memberName()).isEqualTo("findBiggerThan");
            assertThat(h.line()).isEqualTo(12);
        });
        assertThat(report.warnings()).anyMatch(w -> w.contains("简单名"));
    }

    @Test
    @DisplayName("search-by-annotation filters by element kind")
    void searchByAnnotationKinds() throws ToolFailure {
        Annotations.Report types = index("demo-web")
            .findByAnnotation(new Annotations.Query("RestController", Set.of(Annotations.ElementKind.FIELD), false, 50));
        assertThat(types.hits()).isEmpty();
    }

    // ------------------------------------------------------------------ dependencies

    @Test
    @DisplayName("get-file-dependencies sees same-package coupling that has no import")
    void fileDependencies() throws ToolFailure {
        SourceIndex index = index("layered-callers");
        Dependencies.Report report = index
            .fileDependencies(Path.of("src/main/java/com/acme/order/OrderService.java"), true);

        assertThat(report.typeFqn()).isEqualTo("com.acme.order.OrderService");
        assertThat(report.incoming()).extracting(Dependencies.Edge::fromFile)
            .anyMatch(f -> f.endsWith("OrderFacade.java"))
            .anyMatch(f -> f.endsWith("OrderServiceImpl.java"))
            .anyMatch(f -> f.endsWith("PaymentJob.java"));
        assertThat(report.incoming()).anyMatch(e -> e.kind() == Dependencies.Kind.SAME_PACKAGE_USAGE);
        assertThat(report.incoming()).anyMatch(e -> e.kind() == Dependencies.Kind.IMPORT);
        assertThat(report.samePackageSiblings()).isNotEmpty();
        // java.lang is filtered out on purpose; it is never a real coupling.
        assertThat(report.outgoing()).noneMatch(e -> e.targetType().startsWith("java.lang."));
    }

    // ------------------------------------------------------------------ robustness

    @Test
    @DisplayName("a syntactically broken file is reported, not fatal")
    void brokenSourceIsTolerated() throws ToolFailure {
        SourceIndex index = index("broken-syntax");
        assertThat(index.metadata().fileCount()).isEqualTo(3);
        assertThat(index.metadata().problems()).hasSize(1);
        assertThat(index.metadata().problems().get(0).file()).endsWith("Broken.java");
        assertThat(index.metadata().problems().get(0).reason()).isEqualTo("syntax");
        // The healthy files are still indexed.
        assertThat(index.allTypes()).extracting(t -> t.simpleName()).contains("Alpha", "Beta");
    }

    @Test
    @DisplayName("build output directories are excluded")
    void buildOutputExcluded() throws ToolFailure {
        SourceIndex index = index("empty");
        assertThat(index.metadata().fileCount()).isZero();
        assertThat(index.metadata().warnings()).anyMatch(w -> w.contains("未在该目录下找到"));
    }

    @Test
    @DisplayName("an annotation with no matches returns an empty, successful report")
    void emptyResultIsSuccess() throws ToolFailure {
        Annotations.Report report = index("demo-web")
            .findByAnnotation(new Annotations.Query("NeverUsed", Set.of(), false, 10));
        assertThat(report.hits()).isEmpty();
        assertThat(report.total()).isZero();
    }

    @Test
    @DisplayName("results are ordered by file then line so output is stable")
    void stableOrdering() throws ToolFailure {
        Controllers.Report report = index("demo-web").controllers(new Controllers.Query(false, null, null));
        List<String> files = report.endpoints().stream().map(Controllers.Endpoint::file).toList();
        assertThat(files).isSorted();
    }
}

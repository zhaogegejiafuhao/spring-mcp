package io.github.springmcp.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.springmcp.core.index.SourceIndex;
import io.github.springmcp.spi.ToolFailure;
import io.github.springmcp.spi.api.IndexOptions;
import io.github.springmcp.spi.model.Mappers;

/**
 * Covers the three ways a MyBatis mapper gets declared, and the one way it does not.
 *
 * <p>
 * Found on {@code baomidou/mybatis-plus-samples}: 36 interfaces extend {@code BaseMapper<T>} but only
 * 29 carry {@code @Mapper}, and the tool reported 12. A detector that only understands the annotation
 * silently loses two thirds of the persistence layer on the most common Chinese Spring stack — worse
 * than losing nothing, because an almost-empty answer reads as "this project has almost no mappers".
 * </p>
 */
class MybatisDeclarationTest {

    private static IndexRegistry registry;

    @BeforeAll
    static void setUp() {
        registry = new IndexRegistry(IndexRegistry.Revalidation.NEVER, 60_000);
    }

    private static Mappers.Report mappers() throws ToolFailure {
        return mappers(false);
    }

    private static Mappers.Report mappers(boolean includeTests) throws ToolFailure {
        SourceIndex index = (SourceIndex) registry.open(Fixtures.path("mybatis-xml"),
                IndexOptions.defaults().withIncludeTests(includeTests));
        return index.mappers(new Mappers.Query(Set.of(), null));
    }

    @Test
    @DisplayName("@Mapper, BaseMapper<T> and @MapperScan are all recognised")
    void allThreeDeclarationStylesAreFound() throws ToolFailure {
        assertThat(mappers().mappers()).extracting(Mappers.Descriptor::simpleName)
            .contains("UserMapper", "PlusUserMapper", "ScannedMapper", "WildMapper");
    }

    @Test
    @DisplayName("BaseMapper<Entity> yields the entity type, a type variable does not")
    void entityTypeComesFromTheGeneric() throws ToolFailure {
        assertThat(mappers().mappers()).filteredOn(m -> "PlusUserMapper".equals(m.simpleName()))
            .singleElement()
            .satisfies(mapper -> assertThat(mapper.entityType()).isEqualTo("PlusUser"));

        // MyBaseMapper<T> declares its own parameter; "T" is not an entity and must not be reported as
        // one, because that reads like a resolved answer while carrying no information.
        assertThat(mappers().mappers()).filteredOn(m -> "MyBaseMapper".equals(m.simpleName()))
            .singleElement()
            .satisfies(mapper -> assertThat(mapper.entityType()).isNull());
    }

    @Test
    @DisplayName("mapper XML is found however deep the package is")
    void deepXmlIsDiscovered() throws ToolFailure {
        // The old discovery capped the walk at 8 levels; a mapper at
        // <module>/src/main/resources/<package>/X.xml is level 9. mall has 76 of those, and
        // every one of them was silently dropped — no orphan entry, no warning.
        assertThat(mappers().mappers()).filteredOn(m -> "DeepMapper".equals(m.simpleName()))
            .singleElement()
            .satisfies(mapper -> {
                assertThat(mapper.xmlPath()).isNotNull();
                assertThat(mapper.statements()).extracting(Mappers.Statement::id)
                    .contains("com.acme.deep.mapper.DeepMapper.selectDeep");
            });
    }

    @Test
    @DisplayName("mapper XML under src/test is ignored unless tests are included")
    void testResourcesFollowTheIncludeTestsFlag() throws ToolFailure {
        // Java sources already obey the flag; XML used to not, so list-mappers reported XML-only
        // entries whose namespace pointed at a test interface that was never indexed.
        assertThat(mappers(false).mappers()).extracting(Mappers.Descriptor::simpleName)
            .doesNotContain("TestOnlyMapper");
        assertThat(mappers(true).mappers()).extracting(Mappers.Descriptor::simpleName)
            .contains("TestOnlyMapper");
    }

    @Test
    @DisplayName("an interface with no MyBatis signal is not reported")
    void negativeControlStaysOut() throws ToolFailure {
        assertThat(mappers().mappers()).extracting(Mappers.Descriptor::simpleName)
            .doesNotContain("NotAMapper");
    }
}

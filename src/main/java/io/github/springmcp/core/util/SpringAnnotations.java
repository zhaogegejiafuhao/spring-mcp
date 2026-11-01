package io.github.springmcp.core.util;

import java.util.Set;

/**
 * Well-known Spring and MyBatis annotation names.
 *
 * <p>
 * Matching is done on simple names by default because a project may import the annotation from
 * {@code org.springframework.web.bind.annotation} or from a custom meta-annotation package, and
 * because the MVP deliberately does not resolve annotation inheritance. Constants here are fully
 * qualified so callers can request strict matching when they want it.
 * </p>
 */
public final class SpringAnnotations {

    private SpringAnnotations() {
    }

    /** Marker for {@code @RestController}. */
    public static final String REST_CONTROLLER = "org.springframework.web.bind.annotation.RestController";

    /** Marker for {@code @Controller}. */
    public static final String CONTROLLER = "org.springframework.stereotype.Controller";

    /** {@code @RequestMapping}. */
    public static final String REQUEST_MAPPING = "org.springframework.web.bind.annotation.RequestMapping";

    /** Verb-specific shortcuts handled alongside {@link #REQUEST_MAPPING}. */
    public static final Set<String> MAPPING_ANNOTATIONS = Set.of("GetMapping", "PostMapping", "PutMapping",
            "DeleteMapping", "PatchMapping", "RequestMapping");

    /** {@code @Service}. */
    public static final String SERVICE = "org.springframework.stereotype.Service";

    /** {@code @Component}. */
    public static final String COMPONENT = "org.springframework.stereotype.Component";

    /** {@code @Repository}. */
    public static final String REPOSITORY = "org.springframework.stereotype.Repository";

    /** {@code @Autowired}. */
    public static final String AUTOWIRED = "org.springframework.beans.factory.annotation.Autowired";

    /** {@code @Qualifier}. */
    public static final String QUALIFIER = "org.springframework.beans.factory.annotation.Qualifier";

    /** MyBatis {@code @Mapper}. */
    public static final String MYBATIS_MAPPER = "org.apache.ibatis.annotations.Mapper";

    /**
     * {@code @MapperScan} simple name.
     *
     * <p>
     * Matched by simple name because two packages ship it — {@code org.mybatis.spring.annotation} and
     * MyBatis-Plus' {@code com.baomidou...} — and treating them differently would serve nobody.
     * </p>
     */
    public static final String MAPPER_SCAN = "MapperScan";

    /**
     * MyBatis-Plus mapper base interfaces.
     *
     * <p>
     * Extending one of these <em>is</em> the declaration: in a MyBatis-Plus project the mapper
     * interface usually carries no {@code @Mapper}, because {@code @MapperScan} registers it. On
     * {@code mybatis-plus-samples} this set is the difference between finding 12 mappers and 36.
     * </p>
     */
    public static final Set<String> MYBATIS_PLUS_BASE_TYPES = Set.of("BaseMapper", "MPJBaseMapper");

    /** MyBatis statement annotations, mapped to the SQL command type they imply. */
    public static final Set<String> MYBATIS_STATEMENT_ANNOTATIONS = Set.of("Select", "Insert", "Update", "Delete",
            "SelectProvider", "InsertProvider", "UpdateProvider", "DeleteProvider", "SelectKey");

    /** Spring Data repository base interfaces used to detect JPA repositories. */
    public static final Set<String> JPA_REPOSITORY_TYPES = Set.of("Repository", "CrudRepository",
            "PagingAndSortingRepository", "JpaRepository", "ListCrudRepository", "ListPagingAndSortingRepository",
            "MongoRepository", "ReactiveCrudRepository", "ReactiveSortingRepository", "JpaSpecificationExecutor",
            "QueryByExampleExecutor", "R2dbcRepository");

    /**
     * Maps a MyBatis annotation simple name to the SQL command type it produces.
     * @param annotationSimpleName e.g. {@code "Select"}
     * @return {@code SELECT}, {@code INSERT}, {@code UPDATE}, {@code DELETE} or {@code UNKNOWN}
     */
    public static String mybatisCommandType(String annotationSimpleName) {
        return switch (annotationSimpleName) {
            case "Select", "SelectProvider" -> "SELECT";
            case "Insert", "InsertProvider" -> "INSERT";
            case "Update", "UpdateProvider" -> "UPDATE";
            case "Delete", "DeleteProvider" -> "DELETE";
            default -> "UNKNOWN";
        };
    }
}

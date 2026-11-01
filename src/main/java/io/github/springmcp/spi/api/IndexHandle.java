package io.github.springmcp.spi.api;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import io.github.springmcp.spi.ToolFailure;
import io.github.springmcp.spi.model.Annotations;
import io.github.springmcp.spi.model.Controllers;
import io.github.springmcp.spi.model.Dependencies;
import io.github.springmcp.spi.model.FeignClients;
import io.github.springmcp.spi.model.IndexMetadata;
import io.github.springmcp.spi.model.GatewayRoutes;
import io.github.springmcp.spi.model.Mappers;
import io.github.springmcp.spi.model.ServiceCalls;
import io.github.springmcp.spi.model.Symbols;
import io.github.springmcp.spi.model.TypeHierarchy;

/**
 * A read-only, immutable snapshot of one project's source index.
 *
 * <p>
 * Handles are cheap. Several plugins may hold the same handle, and obtaining one is a cache
 * lookup unless the sources changed. The heavy resources behind it are owned by the server, not
 * by the handle, so {@link #close()} is advisory and safe to ignore.
 * </p>
 *
 * <p>
 * All query methods are thread-safe and side-effect free.
 * </p>
 */
public interface IndexHandle {

    /**
     * @return metadata of this snapshot
     */
    IndexMetadata metadata();

    /**
     * @return absolute normalised project root
     */
    Path root();

    /**
     * Lists HTTP endpoints declared by controllers.
     * @param query filter; must not be {@code null}
     * @return endpoints plus aggregate counts, never {@code null}
     */
    Controllers.Report controllers(Controllers.Query query);

    /**
     * Finds direct call sites of a service class or method. Does not walk the call chain
     * recursively.
     * @param query what to look for
     * @return call sites and unresolved candidates
     * @throws ToolFailure when the target class cannot be resolved or is ambiguous
     */
    ServiceCalls.Report findServiceCallers(ServiceCalls.Query query) throws ToolFailure;

    /**
     * Lists Spring Cloud OpenFeign clients: the outbound side of the service dependency graph.
     * @param query filter; must not be {@code null}
     * @return clients plus warnings, never {@code null}
     */
    FeignClients.Report feignClients(FeignClients.Query query);

    /**
     * Lists MyBatis mappers, MyBatis XML mappers and JPA repositories.
     * @param query filter; must not be {@code null}
     * @return mappers plus orphans and per-kind counts
     */
    Mappers.Report mappers(Mappers.Query query);

    /**
     * Finds elements carrying an annotation.
     * @param query annotation name plus filters
     * @return hits, possibly truncated
     * @throws ToolFailure when the annotation name is blank
     */
    Annotations.Report findByAnnotation(Annotations.Query query) throws ToolFailure;

    /**
     * Lists dependencies of one file in both directions.
     * @param file absolute or project-relative path of an existing source file
     * @param includeSamePackage include other files of the same package as implicit siblings
     * @return dependency edges
     * @throws ToolFailure when the file is outside the index or not a Java source file
     */
    Dependencies.Report fileDependencies(Path file, boolean includeSamePackage) throws ToolFailure;

    /**
     * Reads the Spring Cloud Gateway route table from configuration files.
     *
     * <p>
     * Same contract as {@link #implementations(TypeHierarchy.Query)}: the default throws instead of
     * returning an empty report, because "this project has no gateway" and "this index cannot answer"
     * must not look alike.
     * </p>
     *
     * @return routes plus warnings
     * @throws ToolFailure when the implementation does not support this query
     */
    default GatewayRoutes gatewayRoutes() throws ToolFailure {
        throw ToolFailure.of(io.github.springmcp.spi.ErrorCode.INTERNAL_ERROR,
                "该索引实现不支持网关路由查询", "请升级 spring-mcp");
    }

    /**
     * Finds type declarations by name.
     *
     * <p>
     * Same contract as {@link #implementations(TypeHierarchy.Query)}: the default throws instead of
     * returning an empty list, because "no such type" and "this index cannot answer" must not look
     * alike.
     * </p>
     *
     * @param query name fragment, optional kind filter and limit
     * @return matching declarations, best match first
     * @throws ToolFailure when the implementation does not support the query
     */
    default Symbols.Report searchSymbols(Symbols.Query query) throws ToolFailure {
        throw ToolFailure.of(io.github.springmcp.spi.ErrorCode.INTERNAL_ERROR,
                "该索引实现不支持按类型名查询", "请升级 spring-mcp 或改用 search-by-annotation");
    }

    /**
     * Lists the subtypes of an interface or class.
     *
     * <p>
     * The default throws rather than returning an empty list: "no implementations" and "this index
     * cannot answer" must not look the same to the caller, and only one of them is worth acting on.
     * </p>
     *
     * @param query which type to look for, and whether bases and sub-interfaces count
     * @return subtypes plus the resolved target
     * @throws ToolFailure when the target cannot be resolved, is ambiguous, or the implementation
     * does not support this query
     */
    default TypeHierarchy.Report implementations(TypeHierarchy.Query query) throws ToolFailure {
        throw ToolFailure.of(io.github.springmcp.spi.ErrorCode.INTERNAL_ERROR,
                "该索引实现不支持类型层次查询", "请升级 spring-mcp 或改用 search-by-annotation 查找实现类");
    }

    /**
     * Narrows every subsequent query on this handle to the given modules.
     *
     * <p>
     * This is a view over the same snapshot, not a new index: type resolution still sees the whole
     * project, so a controller in one module that extends a base class in another keeps working.
     * Only the <em>results</em> are filtered.
     * </p>
     *
     * <p>
     * The default implementation returns {@code this} — a handle that cannot scope ignores the
     * request rather than returning nothing, because silently empty results are indistinguishable
     * from "nothing found".
     * </p>
     *
     * @param scope modules to keep; {@link QueryScope#unrestricted()} returns this handle unchanged
     * @return a handle whose queries only report results from those modules
     */
    default IndexHandle scoped(QueryScope scope) {
        return this;
    }

    /**
     * @return the scope applied to this handle, {@link QueryScope#unrestricted()} when none
     */
    default QueryScope scope() {
        return QueryScope.unrestricted();
    }

    /**
     * Lists the modules detected in the project, as project-relative paths.
     *
     * <p>
     * Empty when the project is not multi-module, or when the implementation does not detect
     * modules. Callers should treat empty as "scoping is not available" rather than "no modules".
     * </p>
     *
     * @return module paths, sorted; possibly empty
     */
    default List<String> modules() {
        return List.of();
    }

    /**
     * Maps each module to the modules it declares a dependency on, as far as that can be read from
     * build files (Maven {@code <dependencies>}, Gradle project dependencies).
     *
     * <p>
     * Used to answer "which modules should I include when I only care about this one" without
     * making the caller read every build file by hand.
     * </p>
     *
     * @return dependency edges keyed by module path; possibly empty
     */
    default Map<String, List<String>> moduleDependencies() {
        return Map.of();
    }

    /**
     * Releases the handle. Default is a no-op because the snapshot's memory is owned by the
     * server side registry.
     */
    default void close() {
    }
}

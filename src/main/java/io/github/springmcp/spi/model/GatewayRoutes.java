package io.github.springmcp.spi.model;

import java.util.List;

/**
 * Gateway routes discovered in Spring Cloud configuration files.
 *
 * <p>
 * A gateway is the front door of a microservice system, and its routes live in YAML, not in Java. Without
 * them the answer to "which URLs does this system expose" contains only the URLs the services serve
 * internally — the public paths prefix and strip segments, so they are a different set. On
 * {@code mall-swarm}, 245 internal endpoints sit behind 5 gateway routes that no Java-only analysis can
 * see.
 * </p>
 *
 * @param routes discovered routes, in file and declaration order
 * @param count number of routes, equal to {@code routes.size()}
 * @param discoveryLocatorEnabled whether the implicit {@code /{serviceId}/**} routes are turned on, which
 * means the listed routes are not the whole picture
 * @param warnings non-fatal notes, e.g. a config file that could not be parsed
 */
public record GatewayRoutes(List<Route> routes, int count, boolean discoveryLocatorEnabled, List<String> warnings) {

    public GatewayRoutes {
        routes = routes == null ? List.of() : List.copyOf(routes);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    /**
     * One route.
     *
     * @param id route id as declared, {@code null} when absent
     * @param uri target URI as declared, e.g. {@code lb://mall-admin}
     * @param serviceId service name for {@code lb:} URIs, {@code null} for absolute URLs
     * @param predicates predicate expressions as written, e.g. {@code Path=/mall-admin/**}
     * @param filters filter expressions as written, e.g. {@code StripPrefix=1}
     * @param pathPrefixes paths taken from {@code Path=} predicates, as written ({@code /mall-admin/**}).
     * Reported separately because it is the half of the mapping a caller needs, and digging it out of
     * the predicate strings is exactly the step that gets skipped
     * @param stripPrefix how many leading segments {@code StripPrefix} removes, {@code null} when the
     * route has none. A client path plus this number is the path the service actually handles
     * @param targetModule module of this project the route points at, {@code null} when none matches
     * @param file configuration file the route was declared in, relative to the project root
     */
    public record Route(String id, String uri, String serviceId, List<String> predicates, List<String> filters,
            List<String> pathPrefixes, Integer stripPrefix, String targetModule, String file) {

        public Route {
            predicates = predicates == null ? List.of() : List.copyOf(predicates);
            filters = filters == null ? List.of() : List.copyOf(filters);
            pathPrefixes = pathPrefixes == null ? List.of() : List.copyOf(pathPrefixes);
        }
    }
}

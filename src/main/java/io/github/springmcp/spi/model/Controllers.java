package io.github.springmcp.spi.model;

import java.util.List;

/**
 * HTTP endpoints exposed by {@code @Controller} / {@code @RestController} classes.
 */
public final class Controllers {

    private Controllers() {
    }

    /**
     * @param includeTests also scan {@code src/test/java}
     * @param basePathFilter only keep endpoints whose resolved path starts with this prefix,
     * {@code null} for no filtering
     * @param httpMethod only keep endpoints exposing this verb ({@code GET}, {@code POST}...),
     * {@code null} for all
     */
    public record Query(boolean includeTests, String basePathFilter, String httpMethod) {

        public Query {
            httpMethod = httpMethod == null ? null : httpMethod.trim().toUpperCase(java.util.Locale.ROOT);
        }
    }

    /**
     * One controller handler mapped to one URL.
     *
     * <p>
     * A single Java method can yield several endpoints: an array of class-level paths
     * multiplied by an array of method-level paths multiplied by several HTTP verbs. Each
     * combination becomes its own entry, because that is what a client actually calls.
     * </p>
     *
     * @param controllerFqn fully qualified controller class
     * @param classPathRaw class level {@code @RequestMapping} value exactly as written, may be
     * empty
     * @param classPathResolved {@code false} when the class path was a constant, placeholder or
     * concatenation and therefore not statically known
     * @param httpMethods verbs this combination answers, e.g. {@code ["GET"]}
     * @param path joined URL path
     * @param pathResolved {@code false} when any part of {@code path} is not a literal
     * @param unresolvedReason why the path is unresolved: {@code LITERAL_CONSTANT},
     * {@code PLACEHOLDER}, {@code CONCATENATION} or {@code null}
     * @param handlerMethod Java method name
     * @param signature human readable signature, e.g. {@code "get(String) : Order"}
     * @param file absolute source path of the handler declaration
     * @param line line of the handler declaration
     * @param handlerDeclaredIn fully qualified type that actually declared the handler; differs from
     * {@code controllerFqn} when the mapping is inherited from a base class or interface. Reported
     * because {@code file} and {@code line} then point outside the controller, which is otherwise
     * confusing
     * @param conditions annotations that decide whether this endpoint is registered at all, rendered
     * as written, e.g. {@code @Profile("dev")} or {@code @ConditionalOnProperty(name="x")}. Empty for
     * the common case. The condition is not evaluated — it is reported so a caller does not mistake a
     * dev-only endpoint for a production one
     */
    public record Endpoint(String controllerFqn, String classPathRaw, boolean classPathResolved,
            List<String> httpMethods, String path, boolean pathResolved, String unresolvedReason, String handlerMethod,
            String signature, String file, int line, String handlerDeclaredIn, List<String> conditions) {

        public Endpoint {
            conditions = conditions == null ? List.of() : List.copyOf(conditions);
        }
    }

    /**
     * @param endpoints flattened endpoint list, sorted by file then line
     * @param controllerCount number of controller types found
     * @param methodCount number of handler methods found (may be less than endpoint count)
     * @param warnings non-fatal notes, e.g. how many paths could not be resolved
     */
    public record Report(List<Endpoint> endpoints, int controllerCount, int methodCount, List<String> warnings) {

        public Report {
            endpoints = endpoints == null ? List.of() : List.copyOf(endpoints);
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }
    }
}

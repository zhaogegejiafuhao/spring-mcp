package io.github.springmcp.spi.model;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
     * Counts over the matched endpoint set, so a caller does not have to recount a list to answer
     * "how many write endpoints" or "how many are dev-only".
     *
     * <p>
     * These are always totals, never page totals: paging slices {@code endpoints}, and a model that
     * counts the visible page instead of reading these numbers will under-report the project.
     * </p>
     *
     * @param byMethod endpoints per HTTP verb, verbs with zero occurrences omitted
     * @param byController endpoints per controller simple name, largest first then by name
     * @param conditional endpoints carrying at least one {@code @Profile} / {@code @ConditionalOn*}
     * @param unresolved endpoints whose path could not be computed statically
     * @param inherited endpoints whose handler is declared in a parent type
     */
    public record Stats(Map<String, Integer> byMethod, Map<String, Integer> byController,
            int conditional, int unresolved, int inherited) {

        private static final List<String> VERBS = List.of("GET", "POST", "PUT", "DELETE", "PATCH", "ANY");

        public Stats {
            // Keep insertion order: byMethod reads GET→ANY, byController reads largest-first.
            // Map.copyOf would drop both, and a model reading an unordered map cannot cite a "biggest".
            byMethod = byMethod == null ? Map.of()
                    : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(byMethod));
            byController = byController == null ? Map.of()
                    : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(byController));
        }

        /**
         * @param endpoints endpoints to summarise, may be empty
         * @return counts for the given set
         */
        public static Stats of(List<Endpoint> endpoints) {
            Map<String, Integer> byMethod = new LinkedHashMap<>();
            Map<String, Integer> controllerTally = new HashMap<>();
            int conditional = 0;
            int unresolved = 0;
            int inherited = 0;

            for (Endpoint e : endpoints) {
                for (String m : e.httpMethods()) {
                    byMethod.merge(m, 1, Integer::sum);
                }
                controllerTally.merge(simpleName(e.controllerFqn()), 1, Integer::sum);
                if (!e.conditions().isEmpty()) {
                    conditional++;
                }
                if (!e.pathResolved()) {
                    unresolved++;
                }
                if (e.handlerDeclaredIn() != null && !e.handlerDeclaredIn().equals(e.controllerFqn())) {
                    inherited++;
                }
            }

            Map<String, Integer> orderedMethods = new LinkedHashMap<>();
            for (String verb : VERBS) {
                Integer n = byMethod.get(verb);
                if (n != null) {
                    orderedMethods.put(verb, n);
                }
            }
            byMethod.forEach(orderedMethods::putIfAbsent);

            Map<String, Integer> orderedControllers = controllerTally.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed()
                    .thenComparing(Map.Entry.comparingByKey()))
                .collect(LinkedHashMap::new,
                    (m, e) -> m.put(e.getKey(), e.getValue()),
                    LinkedHashMap::putAll);

            return new Stats(orderedMethods, orderedControllers, conditional, unresolved, inherited);
        }

        private static String simpleName(String fqn) {
            if (fqn == null || fqn.isEmpty()) {
                return "";
            }
            int dot = fqn.lastIndexOf('.');
            return dot < 0 ? fqn : fqn.substring(dot + 1);
        }
    }

    /**
     * @param endpoints flattened endpoint list, sorted by file then line
     * @param controllerCount number of controller types found
     * @param methodCount number of handler methods found (may be less than endpoint count)
     * @param stats counts over the whole matched set, never over a page
     * @param warnings non-fatal notes, e.g. how many paths could not be resolved
     */
    public record Report(List<Endpoint> endpoints, int controllerCount, int methodCount, Stats stats,
            List<String> warnings) {

        public Report {
            endpoints = endpoints == null ? List.of() : List.copyOf(endpoints);
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
            stats = stats == null ? Stats.of(endpoints) : stats;
        }

        /**
         * Convenience for callers that do not compute stats: they are derived from the endpoints.
         *
         * @param endpoints matched endpoints
         * @param controllerCount number of controller types
         * @param methodCount number of handler methods
         * @param warnings non-fatal notes
         */
        public Report(List<Endpoint> endpoints, int controllerCount, int methodCount, List<String> warnings) {
            this(endpoints, controllerCount, methodCount, null, warnings);
        }
    }
}

package io.github.springmcp.spi.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Feign clients: the outbound half of a microservice's dependency graph.
 *
 * <p>
 * Every other tool answers "who calls in". A gateway route says which service the outside world
 * reaches; a Feign client says which service <em>this</em> one reaches. Without it a service map has
 * only one direction, and the question people actually ask — "what does mall-demo depend on" — has
 * no answer.
 * </p>
 */
public final class FeignClients {

    private FeignClients() {
    }

    /**
     * @param nameFilter case-insensitive substring match on the client name or interface simple name
     */
    public record Query(String nameFilter) {
    }

    /**
     * One declared method of a client interface.
     *
     * @param httpMethods verbs implied by the mapping annotation, {@code ANY} for a bare
     * {@code @RequestMapping}
     * @param path path as declared on the method
     * @param methodName Java/Kotlin method name
     * @param pathResolved whether the path was a literal, or an expression kept as written
     * @param line 1-based line of the method declaration
     */
    public record Endpoint(List<String> httpMethods, String path, String methodName, boolean pathResolved, int line) {

        public Endpoint {
            httpMethods = httpMethods == null ? List.of() : List.copyOf(httpMethods);
        }
    }

    /**
     * @param name service name the client points at: {@code @FeignClient("mall-admin")} or the URL
     * when only {@code url} is declared
     * @param interfaceFqn fully qualified interface name
     * @param pathPrefix {@code path} attribute of the annotation, empty when absent
     * @param url absolute URL when the client bypasses discovery, otherwise {@code null}
     * @param targetModule module of this project that carries the name, {@code null} when external
     * @param endpoints declared methods, in source order
     * @param file source file of the interface
     */
    public record Client(String name, String interfaceFqn, String pathPrefix, String url, String targetModule,
            List<Endpoint> endpoints, String file) {

        public Client {
            pathPrefix = pathPrefix == null ? "" : pathPrefix;
            endpoints = endpoints == null ? List.of() : List.copyOf(endpoints);
        }
    }

    /**
     * @param clients discovered clients, sorted by name then interface
     * @param total number of clients
     * @param warnings non-fatal notes, e.g. a client without any mapping method
     */
    public record Report(List<Client> clients, int total, List<String> warnings) {

        public Report {
            clients = clients == null ? List.of() : List.copyOf(clients);
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }

        /** @return clients per target module, in discovery order */
        public Map<String, Integer> byModule() {
            Map<String, Integer> counts = new LinkedHashMap<>();
            for (Client client : clients) {
                if (client.targetModule() != null) {
                    counts.merge(client.targetModule(), 1, Integer::sum);
                }
            }
            return Collections.unmodifiableMap(counts);
        }
    }
}

package io.github.springmcp.core.analyze;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import io.github.springmcp.core.index.AnnotationArg;
import io.github.springmcp.core.index.AnnotationUse;
import io.github.springmcp.core.index.MethodSummary;
import io.github.springmcp.core.index.TypeSummary;
import io.github.springmcp.spi.model.FeignClients;

/**
 * Finds Spring Cloud OpenFeign clients.
 *
 * <p>
 * A {@code @FeignClient("mall-admin")} interface is a compile-time statement that this module calls
 * that service, and its methods name the exact URLs. That is the outbound edge of the dependency
 * graph — the one direction no other tool reports. Gateway routes describe the front door; a service
 * call graph needs both ends of every arrow.
 * </p>
 *
 * <p>
 * Everything needed is already in the summary, so this costs one pass over the types and no parsing:
 * the client name is an annotation argument, the paths are method annotations.
 * </p>
 */
public final class FeignClientAnalyzer {

    private static final String FEIGN_CLIENT = "FeignClient";

    /** Verb implied by each shortcut annotation. */
    private static final Map<String, String> VERB_BY_ANNOTATION = Map.of("GetMapping", "GET", "PostMapping", "POST",
            "PutMapping", "PUT", "DeleteMapping", "DELETE", "PatchMapping", "PATCH");

    /** Token used when {@code @RequestMapping} does not restrict the verb. */
    private static final String ANY_VERB = "ANY";

    /**
     * @param types every type of the snapshot
     * @param modulePaths modules of this project, used to resolve a client name to a directory
     * @param query caller filter, may be {@code null}
     * @return clients plus warnings, never {@code null}
     */
    public FeignClients.Report analyze(List<TypeSummary> types, List<String> modulePaths, FeignClients.Query query) {
        List<FeignClients.Client> clients = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        for (TypeSummary type : types) {
            AnnotationUse annotation = type.annotation(FEIGN_CLIENT);
            if (annotation == null) {
                continue;
            }
            String url = literal(annotation.arg("url"));
            String name = literal(annotation.arg("name"));
            if (name == null) {
                name = literal(annotation.value());
            }
            if (name == null) {
                // A client declared by URL only still names its target; falling back to the simple
                // name would point at this project instead of the remote service.
                name = url != null ? url : type.simpleName();
            }
            String pathPrefix = literal(annotation.arg("path"));

            List<FeignClients.Endpoint> endpoints = endpoints(type);
            if (endpoints.isEmpty()) {
                warnings.add(type.fqn() + " 的方法上没有映射注解，无法判断它调用哪些路径");
            }
            if (query != null && !matches(query.nameFilter(), name, type.simpleName())) {
                continue;
            }
            clients.add(new FeignClients.Client(name, type.fqn(), pathPrefix, url,
                    GatewayRouteAnalyzer.moduleOf(name, modulePaths), endpoints, type.file().toString()));
        }

        clients.sort(Comparator.comparing(FeignClients.Client::name)
            .thenComparing(FeignClients.Client::interfaceFqn));
        return new FeignClients.Report(clients, clients.size(), warnings);
    }

    private static List<FeignClients.Endpoint> endpoints(TypeSummary type) {
        List<FeignClients.Endpoint> out = new ArrayList<>();
        for (MethodSummary method : type.plainMethods()) {
            AnnotationUse mapping = mappingOf(method);
            if (mapping == null) {
                continue;
            }
            AnnotationArg pathArg = mapping.arg("path") != null ? mapping.arg("path") : mapping.value();
            String path = "";
            boolean resolved = true;
            if (pathArg != null && !pathArg.values().isEmpty()) {
                path = String.join(", ", pathArg.values());
                resolved = pathArg.allStringLiterals() && !path.contains("${");
            }
            out.add(new FeignClients.Endpoint(verbsOf(mapping), path, method.name(), resolved, method.line()));
        }
        return out;
    }

    private static AnnotationUse mappingOf(MethodSummary method) {
        for (AnnotationUse annotation : method.annotations()) {
            if (VERB_BY_ANNOTATION.containsKey(annotation.simpleName())
                    || "RequestMapping".equals(annotation.simpleName())) {
                return annotation;
            }
        }
        return null;
    }

    private static List<String> verbsOf(AnnotationUse mapping) {
        String fixed = VERB_BY_ANNOTATION.get(mapping.simpleName());
        if (fixed != null) {
            return List.of(fixed);
        }
        AnnotationArg methodArg = mapping.arg("method");
        if (methodArg == null || methodArg.values().isEmpty()) {
            return List.of(ANY_VERB);
        }
        Set<String> verbs = new LinkedHashSet<>();
        for (String raw : methodArg.values()) {
            String verb = raw;
            int dot = verb.lastIndexOf('.');
            if (dot >= 0) {
                verb = verb.substring(dot + 1);
            }
            verb = verb.replace("\"", "").trim().toUpperCase(Locale.ROOT);
            if (!verb.isEmpty()) {
                verbs.add(verb);
            }
        }
        return verbs.isEmpty() ? List.of(ANY_VERB) : List.copyOf(verbs);
    }

    private static boolean matches(String filter, String name, String simpleName) {
        if (filter == null || filter.isBlank()) {
            return true;
        }
        String needle = filter.trim().toLowerCase(Locale.ROOT);
        return name.toLowerCase(Locale.ROOT).contains(needle) || simpleName.toLowerCase(Locale.ROOT).contains(needle);
    }

    private static String literal(AnnotationArg arg) {
        if (arg == null || arg.values().isEmpty() || !arg.allStringLiterals()) {
            return null;
        }
        return arg.first();
    }
}

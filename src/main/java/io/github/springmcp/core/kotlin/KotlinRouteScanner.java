package io.github.springmcp.core.kotlin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Reads Spring Cloud Gateway routes written in the Kotlin DSL.
 *
 * <pre>
 * builder.routes {
 *     route("orders") {
 *         path("/api/orders/**")
 *         filters { stripPrefix(1) }
 *         uri("lb://order-service")
 *     }
 * }
 * </pre>
 *
 * <p>
 * The shape is recognised by block structure rather than by expression evaluation: a {@code route}
 * call followed by a lambda body, and inside that body the named calls that the DSL documents. It
 * lives in the Kotlin package, and returns plain text, so the gateway analyser stays independent of
 * how Kotlin is tokenised.
 * </p>
 */
public final class KotlinRouteScanner {

    /** Predicate calls rendered as {@code Name=value}, matching the YAML short form. */
    private static final Set<String> PREDICATES = Set.of("path", "host", "method", "header", "query", "cookie",
            "after", "before", "between", "remoteAddr", "xforwardedRemoteAddr", "weight");

    private KotlinRouteScanner() {
    }

    /**
     * One route as written in Kotlin.
     *
     * @param id route id from {@code route("id") { }}, or {@code null}
     * @param uri target URI, {@code null} when the block declares none
     * @param predicates predicate expressions such as {@code Path=/api/orders/**}
     * @param filters filter expressions such as {@code StripPrefix=1}
     * @param line 1-based line of the {@code route} call
     */
    public record Route(String id, String uri, List<String> predicates, List<String> filters, int line) {
    }

    /**
     * @param file Kotlin source that mentions {@code RouteLocator}
     * @return routes declared in it, empty when there are none or the file cannot be read
     */
    public static List<Route> scan(Path file) {
        String source;
        try {
            source = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        }
        catch (IOException e) {
            return List.of();
        }
        List<KotlinToken> tokens = KotlinLexer.tokenize(source).tokens();
        int[] match = KotlinBrackets.match(tokens);
        List<Route> out = new ArrayList<>();
        for (int k = 0; k < tokens.size(); k++) {
            if (!tokens.get(k).isIdent("route")) {
                continue;
            }
            int paren = -1;
            int after = k + 1;
            if (after < tokens.size() && tokens.get(after).isSym("(") && match[after] > after) {
                paren = after;
                after = match[after] + 1;
            }
            if (after >= tokens.size() || !tokens.get(after).isSym("{") || match[after] < after) {
                continue;
            }
            String id = paren >= 0 ? firstString(tokens, paren + 1, match[paren]) : null;
            int blockStart = after + 1;
            int blockEnd = match[after];
            out.add(readBlock(tokens, match, id, blockStart, blockEnd, tokens.get(k).line()));
            k = blockEnd;
        }
        return out;
    }

    private static Route readBlock(List<KotlinToken> tokens, int[] match, String id, int start, int end, int line) {
        String uri = null;
        List<String> predicates = new ArrayList<>();
        List<String> filters = new ArrayList<>();
        int k = start;
        while (k < end) {
            KotlinToken token = tokens.get(k);
            if (token.kind() != KotlinToken.Kind.IDENT) {
                if ((token.isSym("{") || token.isSym("(") || token.isSym("[")) && match[k] > k) {
                    k = match[k] + 1;
                    continue;
                }
                k++;
                continue;
            }
            String name = token.text();
            int next = k + 1;
            if (next < end && tokens.get(next).isSym("(") && match[next] > next) {
                List<String> args = arguments(tokens, match, next + 1, match[next]);
                if ("uri".equals(name)) {
                    uri = args.isEmpty() ? null : args.get(0);
                }
                else if (PREDICATES.contains(name)) {
                    predicates.add(capitalize(name) + "=" + String.join(",", args));
                }
                k = match[next] + 1;
                continue;
            }
            if (next < end && tokens.get(next).isSym("{") && match[next] > next) {
                if ("filters".equals(name)) {
                    collectFilters(tokens, match, next + 1, match[next], filters);
                }
                k = match[next] + 1;
                continue;
            }
            k++;
        }
        return new Route(id, uri, List.copyOf(predicates), List.copyOf(filters), line);
    }

    private static void collectFilters(List<KotlinToken> tokens, int[] match, int start, int end,
            List<String> filters) {
        int k = start;
        while (k < end) {
            KotlinToken token = tokens.get(k);
            if (token.kind() == KotlinToken.Kind.IDENT
                    && k + 1 < end && tokens.get(k + 1).isSym("(") && match[k + 1] > k + 1) {
                String name = token.text();
                String args = String.join(",", arguments(tokens, match, k + 2, match[k + 1]));
                filters.add("stripPrefix".equals(name) ? "StripPrefix=" + args : capitalize(name) + "=" + args);
                k = match[k + 1] + 1;
                continue;
            }
            if ((token.isSym("{") || token.isSym("(") || token.isSym("[")) && match[k] > k) {
                k = match[k] + 1;
                continue;
            }
            k++;
        }
    }

    /**
     * @return the argument values with literal strings unquoted, and anything else as written
     */
    private static List<String> arguments(List<KotlinToken> tokens, int[] match, int from, int to) {
        List<String> out = new ArrayList<>();
        int start = from;
        int k = from;
        while (k < to) {
            KotlinToken token = tokens.get(k);
            if ((token.isSym("(") || token.isSym("[") || token.isSym("{")) && match[k] > k) {
                k = match[k] + 1;
                continue;
            }
            if (token.isSym(",")) {
                out.add(render(tokens, start, k));
                start = k + 1;
            }
            k++;
        }
        if (start < to) {
            out.add(render(tokens, start, to));
        }
        out.removeIf(String::isEmpty);
        return out;
    }

    private static String render(List<KotlinToken> tokens, int from, int to) {
        if (to - from == 1 && tokens.get(from).kind() == KotlinToken.Kind.STRING) {
            return tokens.get(from).text();
        }
        StringBuilder out = new StringBuilder();
        for (int k = from; k < to; k++) {
            KotlinToken token = tokens.get(k);
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(token.kind() == KotlinToken.Kind.STRING ? "\"" + token.text() + "\"" : token.text());
        }
        return out.toString().trim();
    }

    private static String firstString(List<KotlinToken> tokens, int from, int to) {
        for (int k = from; k < to; k++) {
            if (tokens.get(k).kind() == KotlinToken.Kind.STRING) {
                return tokens.get(k).text();
            }
        }
        return null;
    }

    private static String capitalize(String name) {
        if (name.isEmpty()) {
            return name;
        }
        return name.substring(0, 1).toUpperCase(Locale.ROOT) + name.substring(1);
    }

}

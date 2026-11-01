package io.github.springmcp.core.kotlin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Reads functional routes written in Kotlin.
 *
 * <p>
 * Kotlin uses the same builder as Java — {@code RouterFunctions.route().GET("/x", handler)} — usually
 * spread over several lines, so the shape to recognise is a verb-named call with a literal path as
 * its first argument. The enclosing class is recovered from brace ranges, which is all the caller
 * needs to say where a route is declared.
 * </p>
 *
 * <p>
 * Like the Java side, only literal paths are reported. {@code GET(prefix + "/x")} is a route this
 * scanner cannot name, and guessing would put a wrong URL in the table.
 * </p>
 */
public final class KotlinRouterFunctionScanner {

    /** Verb-named builder methods of {@code RouterFunctions.Builder}. */
    private static final Set<String> VERBS = Set.of("GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS");

    /** Words that start a new declaration, used to stop looking for a class body. */
    private static final Set<String> DECLARATION_WORDS = Set.of("class", "interface", "object", "fun", "val", "var");

    private KotlinRouterFunctionScanner() {
    }

    /**
     * One route as written in Kotlin.
     *
     * @param enclosingType simple name of the class the route is declared in, {@code ""} when unknown
     * @param verb HTTP verb
     * @param path literal path
     * @param handler rendered handler expression, e.g. {@code handler::getPerson} or {@code (lambda)}
     * @param line 1-based line of the verb call
     */
    public record Route(String enclosingType, String verb, String path, String handler, int line) {
    }

    /**
     * @param file Kotlin source that mentions the routing API
     * @return routes with a literal path, empty when there are none or the file cannot be read
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
        List<Range> types = bodyRanges(tokens, match);
        List<Route> out = new ArrayList<>();
        for (int k = 0; k < tokens.size(); k++) {
            KotlinToken token = tokens.get(k);
            if (token.kind() != KotlinToken.Kind.IDENT || !VERBS.contains(token.text())) {
                continue;
            }
            int paren = k + 1;
            if (paren >= tokens.size() || !tokens.get(paren).isSym("(") || match[paren] < paren) {
                continue;
            }
            int close = match[paren];
            if (paren + 1 >= close || tokens.get(paren + 1).kind() != KotlinToken.Kind.STRING) {
                // A path built from an expression is not a path this scanner can name.
                continue;
            }
            String path = tokens.get(paren + 1).text();
            String handler = handler(tokens, match, k, paren + 1, close);
            out.add(new Route(enclosing(types, k), token.text(), path, handler, token.line()));
        }
        return out;
    }

    /**
     * Renders the handler, which is the last argument of the verb call — or, for
     * {@code route(GET("/x"), handler)}, the last argument of the call the predicate sits in.
     *
     * @param tokens token stream
     * @param match bracket pairs
     * @param verb index of the verb token
     * @param from first token of the verb's argument list
     * @param to the verb's closing parenthesis
     * @return rendered handler expression, {@code (lambda)} when there is nothing to name
     */
    private static String handler(List<KotlinToken> tokens, int[] match, int verb, int from, int to) {
        List<int[]> arguments = splitTopLevel(tokens, match, from, to);
        if (arguments.size() >= 2) {
            int[] last = arguments.get(arguments.size() - 1);
            return render(tokens, last[0], last[1]);
        }
        int open = verb - 1;
        if (open > 0 && tokens.get(open).isSym("(") && match[open] > open
                && tokens.get(open - 1).kind() == KotlinToken.Kind.IDENT
                && (tokens.get(open - 1).isIdent("route") || tokens.get(open - 1).isIdent("andRoute"))) {
            List<int[]> outer = splitTopLevel(tokens, match, open + 1, match[open]);
            if (outer.size() >= 2) {
                int[] last = outer.get(outer.size() - 1);
                return render(tokens, last[0], last[1]);
            }
        }
        return "(lambda)";
    }

    private static String render(List<KotlinToken> tokens, int from, int to) {
        StringBuilder out = new StringBuilder();
        for (int k = from; k < to; k++) {
            KotlinToken token = tokens.get(k);
            if (out.length() > 0 && token.kind() == KotlinToken.Kind.IDENT
                    && tokens.get(k - 1).kind() == KotlinToken.Kind.IDENT) {
                out.append(' ');
            }
            out.append(token.kind() == KotlinToken.Kind.STRING ? "\"" + token.text() + "\"" : token.text());
        }
        return out.toString();
    }

    private static List<int[]> splitTopLevel(List<KotlinToken> tokens, int[] match, int from, int to) {
        List<int[]> segments = new ArrayList<>();
        int angle = 0;
        int k = from;
        int start = from;
        while (k < to) {
            KotlinToken token = tokens.get(k);
            if ((token.isSym("(") || token.isSym("[") || token.isSym("{")) && match[k] > k) {
                k = match[k] + 1;
                continue;
            }
            if (token.isSym("<")) {
                angle++;
            }
            else if (token.isSym(">")) {
                if (angle > 0) {
                    angle--;
                }
            }
            else if (token.isSym(",") && angle == 0) {
                segments.add(new int[] { start, k });
                start = k + 1;
            }
            k++;
        }
        if (start < to) {
            segments.add(new int[] { start, to });
        }
        return segments;
    }

    private static String enclosing(List<Range> types, int index) {
        String name = "";
        int span = Integer.MAX_VALUE;
        for (Range range : types) {
            if (index > range.start() && index < range.end() && range.end() - range.start() < span) {
                name = range.name();
                span = range.end() - range.start();
            }
        }
        return name;
    }

    /**
     * @param tokens token stream
     * @param match bracket pairs
     * @return the body range of every named type declaration, so a call can be attributed to the
     * innermost one that contains it
     */
    private static List<Range> bodyRanges(List<KotlinToken> tokens, int[] match) {
        List<Range> out = new ArrayList<>();
        for (int k = 0; k < tokens.size(); k++) {
            KotlinToken token = tokens.get(k);
            if (token.kind() != KotlinToken.Kind.IDENT
                    || !("class".equals(token.text()) || "interface".equals(token.text())
                            || "object".equals(token.text()))) {
                continue;
            }
            if (k > 0 && (tokens.get(k - 1).isSym(":") || tokens.get(k - 1).isSym("."))) {
                // `Foo::class` and `a.object` are not declarations.
                continue;
            }
            if (k + 1 >= tokens.size() || tokens.get(k + 1).kind() != KotlinToken.Kind.IDENT) {
                continue;
            }
            String name = tokens.get(k + 1).text();
            int body = bodyStart(tokens, match, k + 2);
            if (body > 0 && match[body] > body) {
                out.add(new Range(name, body, match[body]));
            }
        }
        return out;
    }

    private static int bodyStart(List<KotlinToken> tokens, int[] match, int from) {
        int k = from;
        int lastLine = from > 0 ? tokens.get(from - 1).line() : 0;
        while (k < tokens.size()) {
            KotlinToken token = tokens.get(k);
            if (token.kind() == KotlinToken.Kind.EOF) {
                return -1;
            }
            if (token.isSym("{")) {
                return k;
            }
            if ((token.isSym("(") || token.isSym("[")) && match[k] > k) {
                lastLine = tokens.get(match[k]).line();
                k = match[k] + 1;
                continue;
            }
            if (token.line() > lastLine
                    && (token.kind() == KotlinToken.Kind.IDENT && DECLARATION_WORDS.contains(token.text())
                            || token.isSym("}"))) {
                return -1;
            }
            lastLine = token.line();
            k++;
        }
        return -1;
    }

    /** @param start index of the body's opening brace @param end index of its closing brace */
    private record Range(String name, int start, int end) {
    }
}

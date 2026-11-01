package io.github.springmcp.core.kotlin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Locates {@code receiver.method(...)} call sites in Kotlin source and says what it can about the
 * receiver.
 *
 * <p>
 * The Java side hands this job to JavaParser and {@code ReceiverResolver}. Kotlin has no AST here, so
 * the same three facts are recovered from the token stream: which type the call is in, which function
 * it is in, and what the receiver is declared as. The last one is the only one that needs judgement,
 * and it is deliberately conservative — a field, a parameter, or a local {@code val} with a written
 * type or a constructor initialiser. Anything else comes back as "unknown", which the caller reports
 * as unresolved instead of guessing.
 * </p>
 */
public final class KotlinCallScanner {

    /** Words that can only start a declaration; used to end an expression-bodied function. */
    private static final Set<String> DECLARATION_WORDS = Set.of("class", "interface", "object", "fun", "val", "var",
            "override", "private", "public", "protected", "internal", "companion", "init", "constructor");

    /** A construct that looks like a call but is not one. */
    private static final Set<String> NOT_CALLS = Set.of("if", "for", "while", "when", "catch", "return", "throw",
            "synchronized", "else", "do", "try", "fun", "class", "interface", "object", "constructor");

    private KotlinCallScanner() {
    }

    /**
     * One call site.
     *
     * @param receiver receiver text as written, e.g. {@code service}; {@code this} for an implicit or
     * explicit self receiver
     * @param localType type written for the receiver inside the same function — either
     * {@code val x: T} or {@code val x = T(...)}; {@code null} when the receiver is not a local
     * @param method called method name
     * @param enclosingType simple name of the innermost enclosing type, {@code ""} when unknown
     * @param enclosingMethod name of the innermost enclosing function, {@code ""} when unknown
     * @param line 1-based line of the call
     * @param column 1-based column of the call
     */
    public record Call(String receiver, String localType, String method, String enclosingType,
            String enclosingMethod, int line, int column) {
    }

    /**
     * @param file Kotlin source
     * @return call sites in source order, empty when the file cannot be read
     */
    public static List<Call> scan(Path file) {
        String source;
        try {
            source = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        }
        catch (IOException e) {
            return List.of();
        }
        List<KotlinToken> tokens = KotlinLexer.tokenize(source).tokens();
        int[] match = KotlinBrackets.match(tokens);
        List<Range> types = typeRanges(tokens, match);
        List<Range> functions = functionRanges(tokens, match, types);

        List<Call> out = new ArrayList<>();
        for (int k = 0; k < tokens.size(); k++) {
            KotlinToken token = tokens.get(k);
            if (token.kind() != KotlinToken.Kind.IDENT || k + 1 >= tokens.size() || !tokens.get(k + 1).isSym("(")
                    || NOT_CALLS.contains(token.text())) {
                continue;
            }
            int dot = k - 1;
            if (dot < 0 || !tokens.get(dot).isSym(".")) {
                // Unqualified: an implicit receiver. An annotation name and a declaration are the two
                // shapes that look the same and are not calls.
                if (dot >= 0 && (tokens.get(dot).isSym("@") || tokens.get(dot).isIdent("fun"))) {
                    continue;
                }
                Range scope = innermost(functions, k);
                out.add(new Call("this", null, token.text(), enclosingName(types, k),
                        scope == null ? "" : scope.name(), token.line(), token.column()));
                continue;
            }
            int receiverAt = dot - 1;
            if (receiverAt >= 0 && tokens.get(receiverAt).isSym("?")) {
                // `service?.detail()` — null-safe access, same receiver.
                receiverAt--;
            }
            if (receiverAt < 0) {
                continue;
            }
            KotlinToken receiverToken = tokens.get(receiverAt);
            if (receiverToken.kind() != KotlinToken.Kind.IDENT) {
                // `a.b().c()` — the receiver is an expression whose type needs inference, which this
                // scanner does not do. Reported by the caller as unresolved, never guessed.
                continue;
            }
            Range function = innermost(functions, receiverAt);
            String localType = function == null ? null
                    : localType(tokens, match, function, receiverToken.text(), receiverAt);
            out.add(new Call(receiverToken.text(), localType, token.text(),
                    enclosingName(types, receiverAt), function == null ? "" : function.name(), token.line(),
                    token.column()));
        }
        return out;
    }

    /**
     * Finds {@code val x: T} or {@code val x = T(...)} inside one function.
     *
     * @return the declared type text, or {@code null} when the name is not declared locally
     */
    private static String localType(List<KotlinToken> tokens, int[] match, Range function, String name, int before) {
        int k = function.start();
        while (k < function.end() && k < before) {
            KotlinToken token = tokens.get(k);
            if (!(token.isIdent("val") || token.isIdent("var"))) {
                k++;
                continue;
            }
            int nameAt = k + 1;
            if (nameAt >= before || tokens.get(nameAt).kind() != KotlinToken.Kind.IDENT
                    || !tokens.get(nameAt).text().equals(name)) {
                k++;
                continue;
            }
            if (nameAt + 1 < before && tokens.get(nameAt + 1).isSym(":")) {
                return typeText(tokens, match, nameAt + 2, before);
            }
            if (nameAt + 1 < before && tokens.get(nameAt + 1).isSym("=")
                    && nameAt + 2 < before && tokens.get(nameAt + 2).kind() == KotlinToken.Kind.IDENT
                    && nameAt + 3 < before && tokens.get(nameAt + 3).isSym("(")) {
                return tokens.get(nameAt + 2).text();
            }
            k++;
        }
        return null;
    }

    private static String typeText(List<KotlinToken> tokens, int[] match, int from, int limit) {
        int k = from;
        StringBuilder out = new StringBuilder();
        while (k < limit) {
            KotlinToken token = tokens.get(k);
            if (token.isSym("=") || token.isSym(")") || token.isSym(",") || token.isSym(";")) {
                break;
            }
            if ((token.isSym("(") || token.isSym("[")) && match[k] > k) {
                k = match[k] + 1;
                continue;
            }
            if (out.length() > 0 && token.kind() == KotlinToken.Kind.IDENT
                    && tokens.get(k - 1).kind() == KotlinToken.Kind.IDENT) {
                out.append(' ');
            }
            out.append(token.text());
            k++;
        }
        return out.toString().trim();
    }

    // ------------------------------------------------------------------ scopes

    /** @param start index of the body's opening token @param end index of its closing token */
    private record Range(String name, int start, int end) {
    }

    private static String enclosingName(List<Range> types, int index) {
        Range range = innermost(types, index);
        return range == null ? "" : range.name();
    }

    private static Range innermost(List<Range> ranges, int index) {
        Range best = null;
        for (Range range : ranges) {
            if (index > range.start() && index < range.end()
                    && (best == null || range.end() - range.start() < best.end() - best.start())) {
                best = range;
            }
        }
        return best;
    }

    private static List<Range> typeRanges(List<KotlinToken> tokens, int[] match) {
        List<Range> out = new ArrayList<>();
        for (int k = 0; k < tokens.size(); k++) {
            KotlinToken token = tokens.get(k);
            if (token.kind() != KotlinToken.Kind.IDENT || !(token.isIdent("class") || token.isIdent("interface")
                    || token.isIdent("object"))) {
                continue;
            }
            if (k > 0 && (tokens.get(k - 1).isSym(":") || tokens.get(k - 1).isSym("."))) {
                continue;
            }
            if (k + 1 >= tokens.size() || tokens.get(k + 1).kind() != KotlinToken.Kind.IDENT) {
                continue;
            }
            int body = bodyStart(tokens, match, k + 2);
            if (body > 0 && match[body] > body) {
                out.add(new Range(tokens.get(k + 1).text(), body, match[body]));
            }
        }
        return out;
    }

    /**
     * Records the body of every function: the brace block when there is one, the expression when the
     * body is written as {@code = ...}, which is the common style for one-line Kotlin functions.
     */
    private static List<Range> functionRanges(List<KotlinToken> tokens, int[] match, List<Range> types) {
        List<Range> out = new ArrayList<>();
        for (int k = 0; k < tokens.size(); k++) {
            if (!tokens.get(k).isIdent("fun")) {
                continue;
            }
            int paren = -1;
            int nameAt = -1;
            for (int j = k + 1; j < tokens.size(); j++) {
                KotlinToken token = tokens.get(j);
                if (token.isSym("(")) {
                    paren = j;
                    break;
                }
                if (token.kind() == KotlinToken.Kind.IDENT) {
                    nameAt = j;
                }
                if (token.isSym("{") || token.isSym("=")) {
                    break;
                }
            }
            if (paren < 0 || nameAt < 0 || match[paren] < paren) {
                continue;
            }
            String name = tokens.get(nameAt).text();
            int after = match[paren] + 1;
            int limit = enclosingEnd(types, k);
            int body = bodyStart(tokens, match, after);
            if (body > 0 && match[body] > body && body < limit) {
                out.add(new Range(name, body, match[body]));
                continue;
            }
            int k2 = after;
            while (k2 < limit && !tokens.get(k2).isSym("=")) {
                if (tokens.get(k2).isSym("{")) {
                    break;
                }
                k2++;
            }
            if (k2 < limit && tokens.get(k2).isSym("=")) {
                out.add(new Range(name, k2, expressionEnd(tokens, match, k2 + 1, limit)));
            }
        }
        return out;
    }

    private static int enclosingEnd(List<Range> types, int index) {
        Range range = innermost(types, index);
        return range == null ? Integer.MAX_VALUE : range.end();
    }

    private static int expressionEnd(List<KotlinToken> tokens, int[] match, int from, int limit) {
        int k = from;
        int lastLine = from < tokens.size() ? tokens.get(from).line() : 0;
        while (k < limit) {
            KotlinToken token = tokens.get(k);
            if (token.kind() == KotlinToken.Kind.EOF || token.isSym("}")) {
                return k;
            }
            if ((token.isSym("(") || token.isSym("[") || token.isSym("{")) && match[k] > k) {
                lastLine = tokens.get(match[k]).line();
                k = match[k] + 1;
                continue;
            }
            if (token.line() > lastLine && token.kind() == KotlinToken.Kind.IDENT
                    && DECLARATION_WORDS.contains(token.text())) {
                return k;
            }
            lastLine = token.line();
            k++;
        }
        return limit;
    }

    private static int bodyStart(List<KotlinToken> tokens, int[] match, int from) {
        int k = from;
        int lastLine = from > 0 ? tokens.get(from - 1).line() : 0;
        while (k < tokens.size()) {
            KotlinToken token = tokens.get(k);
            if (token.kind() == KotlinToken.Kind.EOF || token.isSym("}")) {
                return -1;
            }
            if (token.isSym("{")) {
                return k;
            }
            if (token.isSym("=")) {
                return -1;
            }
            if ((token.isSym("(") || token.isSym("[")) && match[k] > k) {
                lastLine = tokens.get(match[k]).line();
                k = match[k] + 1;
                continue;
            }
            if (token.line() > lastLine && token.kind() == KotlinToken.Kind.IDENT
                    && DECLARATION_WORDS.contains(token.text())) {
                return -1;
            }
            lastLine = token.line();
            k++;
        }
        return -1;
    }
}

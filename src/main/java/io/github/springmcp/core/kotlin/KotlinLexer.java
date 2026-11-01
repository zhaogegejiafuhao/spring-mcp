package io.github.springmcp.core.kotlin;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits Kotlin source into tokens.
 *
 * <p>
 * This is not a Kotlin parser and cannot become one. What it guarantees is narrower and sufficient:
 * comments, string literals and string templates are consumed as units, so the tokens that remain
 * are real code and the braces in a template {@code "${map[key]}"} can never be mistaken for a class
 * body. That one property is what makes a brace-tracking declaration parser safe.
 * </p>
 *
 * <p>
 * Templates are re-rendered in braced form: {@code "/api/$id"} becomes {@code /api/${id}}. Both
 * spellings mean the same thing in Kotlin, and the braced form is the one the rest of the server
 * already recognises as "not a constant".
 * </p>
 */
final class KotlinLexer {

    private final String src;

    private final int length;

    private final List<KotlinToken> tokens = new ArrayList<>();

    private final List<String> problems = new ArrayList<>();

    private int pos;

    private int line = 1;

    private int column = 1;

    private KotlinLexer(String src) {
        this.src = src;
        this.length = src.length();
    }

    /**
     * @param source Kotlin file content
     * @return tokens ending with {@link KotlinToken.Kind#EOF}, plus lexical problems
     */
    static Result tokenize(String source) {
        KotlinLexer lexer = new KotlinLexer(source);
        lexer.run();
        return new Result(lexer.tokens, lexer.problems);
    }

    /**
     * @param tokens the token stream, always terminated by {@code EOF}
     * @param problems recoverable lexical problems, e.g. an unterminated string
     */
    record Result(List<KotlinToken> tokens, List<String> problems) {
    }

    private void run() {
        if (length > 0 && src.charAt(0) == '\uFEFF') {
            pos = 1;
        }
        while (pos < length) {
            char c = src.charAt(pos);
            if (c == ' ' || c == '\t' || c == '\r' || c == '\n' || c == '\f') {
                bump();
                continue;
            }
            if (c == '#' && pos == 0 && src.startsWith("#!")) {
                skipLine();
                continue;
            }
            if (c == '/' && peek(1) == '/') {
                skipLine();
                continue;
            }
            if (c == '/' && peek(1) == '*') {
                skipBlockComment();
                continue;
            }
            int startLine = line;
            int startColumn = column;
            if (c == '"') {
                if (peek(1) == '"' && peek(2) == '"') {
                    rawString(startLine, startColumn);
                }
                else {
                    quotedString(startLine, startColumn);
                }
                continue;
            }
            if (c == '\'') {
                charLiteral(startLine, startColumn);
                continue;
            }
            if (c == '`') {
                backticked(startLine, startColumn);
                continue;
            }
            if (Character.isDigit(c)) {
                number(startLine, startColumn);
                continue;
            }
            if (isIdentStart(c)) {
                identifier(startLine, startColumn);
                continue;
            }
            bump();
            tokens.add(new KotlinToken(KotlinToken.Kind.SYMBOL, String.valueOf(c), startLine, startColumn, false));
        }
        tokens.add(new KotlinToken(KotlinToken.Kind.EOF, "", line, column, false));
    }

    private char peek(int ahead) {
        int at = pos + ahead;
        return at < length ? src.charAt(at) : '\0';
    }

    private char bump() {
        char c = src.charAt(pos++);
        if (c == '\n') {
            line++;
            column = 1;
        }
        else {
            column++;
        }
        return c;
    }

    private void skipLine() {
        while (pos < length && src.charAt(pos) != '\n') {
            bump();
        }
    }

    /** Kotlin block comments nest, so scanning for the first comment terminator would end them early. */
    private void skipBlockComment() {
        bump();
        bump();
        int depth = 1;
        while (pos < length) {
            char c = src.charAt(pos);
            if (c == '/' && peek(1) == '*') {
                bump();
                bump();
                depth++;
                continue;
            }
            if (c == '*' && peek(1) == '/') {
                bump();
                bump();
                depth--;
                if (depth == 0) {
                    return;
                }
                continue;
            }
            bump();
        }
    }

    private void quotedString(int startLine, int startColumn) {
        bump();
        StringBuilder content = new StringBuilder();
        boolean template = readQuoted(content, startLine);
        tokens.add(new KotlinToken(KotlinToken.Kind.STRING, content.toString(), startLine, startColumn, template));
    }

    private void rawString(int startLine, int startColumn) {
        bump();
        bump();
        bump();
        StringBuilder content = new StringBuilder();
        boolean template = readRaw(content);
        tokens.add(new KotlinToken(KotlinToken.Kind.STRING, content.toString(), startLine, startColumn, template));
    }

    private boolean readRaw(StringBuilder content) {
        boolean template = false;
        while (pos < length) {
            char c = src.charAt(pos);
            if (c == '"') {
                int quotes = 0;
                while (pos < length && src.charAt(pos) == '"') {
                    quotes++;
                    bump();
                }
                if (quotes >= 3) {
                    // Four or more quotes: the surplus belongs to the content, as in Kotlin.
                    for (int k = 3; k < quotes; k++) {
                        content.append('"');
                    }
                    return template;
                }
                for (int k = 0; k < quotes; k++) {
                    content.append('"');
                }
                continue;
            }
            if (c == '$') {
                template |= template(content);
                continue;
            }
            content.append(bump());
        }
        problems.add("原始字符串未闭合 (line " + line + ")");
        return template;
    }

    private boolean readQuoted(StringBuilder content, int startLine) {
        boolean template = false;
        while (pos < length) {
            char c = src.charAt(pos);
            if (c == '"') {
                bump();
                return template;
            }
            if (c == '\n') {
                problems.add("字符串未闭合 (line " + startLine + ")");
                return template;
            }
            if (c == '\\') {
                bump();
                escape(content);
                continue;
            }
            if (c == '$') {
                template |= template(content);
                continue;
            }
            content.append(bump());
        }
        problems.add("字符串未闭合 (line " + startLine + ")");
        return template;
    }

    private void escape(StringBuilder out) {
        if (pos >= length) {
            return;
        }
        char e = bump();
        switch (e) {
            case 'n' -> out.append('\n');
            case 't' -> out.append('\t');
            case 'r' -> out.append('\r');
            case 'b' -> out.append('\b');
            case 'f' -> out.append('\f');
            case '0' -> out.append('\0');
            case '"' -> out.append('"');
            case '\'' -> out.append('\'');
            case '\\' -> out.append('\\');
            case '$' -> out.append('$');
            case 'u' -> {
                StringBuilder hex = new StringBuilder();
                while (hex.length() < 4 && pos < length && isHex(src.charAt(pos))) {
                    hex.append(bump());
                }
                if (hex.length() == 4) {
                    out.append((char) Integer.parseInt(hex.toString(), 16));
                }
                else {
                    out.append("\\u").append(hex);
                }
            }
            default -> out.append(e);
        }
    }

    /**
     * Consumes one interpolation and appends it in braced form.
     *
     * @param out destination content
     * @return whether an interpolation was consumed; a lone {@code $} is literal text
     */
    private boolean template(StringBuilder out) {
        bump();
        if (pos < length && src.charAt(pos) == '{') {
            bump();
            StringBuilder inner = new StringBuilder();
            int depth = 1;
            while (pos < length && depth > 0) {
                char c = src.charAt(pos);
                if (c == '"') {
                    quotedInto(inner);
                    continue;
                }
                if (c == '{') {
                    depth++;
                    inner.append(bump());
                    continue;
                }
                if (c == '}') {
                    depth--;
                    bump();
                    if (depth == 0) {
                        break;
                    }
                    inner.append('}');
                    continue;
                }
                inner.append(bump());
            }
            out.append("${").append(inner).append('}');
            return true;
        }
        if (pos < length && isIdentStart(src.charAt(pos))) {
            StringBuilder name = new StringBuilder();
            while (pos < length && isIdentPart(src.charAt(pos))) {
                name.append(bump());
            }
            out.append("${").append(name).append('}');
            return true;
        }
        out.append('$');
        return false;
    }

    private void quotedInto(StringBuilder out) {
        int startLine = line;
        bump();
        StringBuilder inner = new StringBuilder();
        readQuoted(inner, startLine);
        out.append('"').append(inner).append('"');
    }

    private void charLiteral(int startLine, int startColumn) {
        bump();
        StringBuilder content = new StringBuilder();
        while (pos < length) {
            char c = src.charAt(pos);
            if (c == '\\') {
                bump();
                escape(content);
                continue;
            }
            if (c == '\'') {
                bump();
                break;
            }
            if (c == '\n') {
                break;
            }
            content.append(bump());
        }
        tokens.add(new KotlinToken(KotlinToken.Kind.LITERAL, "'" + content + "'", startLine, startColumn, false));
    }

    private void number(int startLine, int startColumn) {
        StringBuilder text = new StringBuilder();
        boolean dotSeen = false;
        while (pos < length) {
            char c = src.charAt(pos);
            if (Character.isLetterOrDigit(c) || c == '_') {
                text.append(bump());
                continue;
            }
            // `1..10` is a range and `10.toString()` is a call; neither dot is part of the number.
            if (c == '.' && !dotSeen && Character.isDigit(peek(1))) {
                dotSeen = true;
                text.append(bump());
                continue;
            }
            break;
        }
        tokens.add(new KotlinToken(KotlinToken.Kind.LITERAL, text.toString(), startLine, startColumn, false));
    }

    private void identifier(int startLine, int startColumn) {
        StringBuilder text = new StringBuilder();
        while (pos < length && isIdentPart(src.charAt(pos))) {
            text.append(bump());
        }
        tokens.add(new KotlinToken(KotlinToken.Kind.IDENT, text.toString(), startLine, startColumn, false));
    }

    private void backticked(int startLine, int startColumn) {
        bump();
        StringBuilder text = new StringBuilder();
        while (pos < length) {
            char c = src.charAt(pos);
            if (c == '`') {
                bump();
                break;
            }
            if (c == '\n') {
                break;
            }
            text.append(bump());
        }
        tokens.add(new KotlinToken(KotlinToken.Kind.IDENT, text.toString(), startLine, startColumn, false));
    }

    private static boolean isHex(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    private static boolean isIdentStart(char c) {
        return c == '_' || Character.isLetter(c);
    }

    private static boolean isIdentPart(char c) {
        return c == '_' || Character.isLetterOrDigit(c);
    }
}

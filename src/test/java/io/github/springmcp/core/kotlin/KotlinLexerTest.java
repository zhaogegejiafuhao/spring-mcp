package io.github.springmcp.core.kotlin;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The lexer is the fragile half of Kotlin support, so it is tested where it actually breaks.
 *
 * <p>
 * Every case here corresponds to a way a brace tracker can be fooled: a {@code {} that is really a
 * template, a comment terminator that is really nested, a quote that is really content. When one of
 * these slips, the failure is not a crash — it is a class body that ends early and a project that
 * quietly loses endpoints.
 * </p>
 */
class KotlinLexerTest {

    @Test
    @DisplayName("block comments nest, so the inner terminator does not end the outer comment")
    void nestedBlockComments() {
        List<KotlinToken> tokens = lex("/* outer /* inner */ still comment */ class X");

        assertThat(texts(tokens)).containsExactly("class", "X");
    }

    @Test
    @DisplayName("line comments and a shebang line produce no tokens")
    void lineCommentsAndShebang() {
        List<KotlinToken> tokens = lex("#!/usr/bin/env kotlin\n// comment\nval x = 1 // trailing");

        assertThat(texts(tokens)).containsExactly("val", "x", "=", "1");
    }

    @Test
    @DisplayName("escapes are decoded, and an escaped dollar is not a template")
    void escapesAreDecoded() {
        KotlinToken token = onlyString(lex("val s = \"a\\tb\\\"c\\$d\""));

        assertThat(token.text()).isEqualTo("a\tb\"c$d");
        assertThat(token.template()).isFalse();
    }

    @Test
    @DisplayName("$name is re-rendered as ${name}, which is the form the path analyser recognises")
    void simpleTemplate() {
        KotlinToken token = onlyString(lex("val s = \"api/$id/detail\""));

        assertThat(token.text()).isEqualTo("api/${id}/detail");
        assertThat(token.template()).isTrue();
    }

    @Test
    @DisplayName("a template containing a bracketed expression stays inside the string")
    void templateWithBrackets() {
        KotlinToken token = onlyString(lex("val s = \"${map[key]}\""));

        assertThat(token.text()).isEqualTo("${map[key]}");
        assertThat(token.template()).isTrue();
    }

    @Test
    @DisplayName("a template containing its own string literal does not end the outer string")
    void templateWithNestedString() {
        KotlinToken token = onlyString(lex("val s = \"${list.joinToString(\", \")}\""));

        assertThat(token.text()).isEqualTo("${list.joinToString(\", \")}");
        assertThat(token.template()).isTrue();
    }

    @Test
    @DisplayName("raw strings keep quotes and braces, and still interpolate")
    void rawString() {
        KotlinToken token = onlyString(lex("val s = \"\"\"{\"a\": 1} $c\"\"\""));

        assertThat(token.text()).isEqualTo("{\"a\": 1} ${c}");
        assertThat(token.template()).isTrue();
    }

    @Test
    @DisplayName("an unterminated string is reported instead of swallowing the rest of the file")
    void unterminatedString() {
        KotlinLexer.Result result = KotlinLexer.tokenize("val s = \"oops\nval y = 2");

        assertThat(result.problems()).anyMatch(problem -> problem.contains("字符串未闭合"));
        // The damage stops at the line break: the declaration below is still readable.
        assertThat(texts(result.tokens())).contains("val", "y", "2");
    }

    @Test
    @DisplayName("a range is not a decimal number")
    void rangeIsNotADecimal() {
        List<KotlinToken> tokens = lex("for (i in 1..10) { }");

        assertThat(texts(tokens)).containsExactly("for", "(", "i", "in", "1", ".", ".", "10", ")", "{", "}");
    }

    @Test
    @DisplayName("backticked identifiers are a single name")
    void backtickedIdentifier() {
        List<KotlinToken> tokens = lex("fun `when`() { }");

        assertThat(tokens.get(1).kind()).isEqualTo(KotlinToken.Kind.IDENT);
        assertThat(tokens.get(1).text()).isEqualTo("when");
    }

    @Test
    @DisplayName("characters, numbers with suffixes and annotations survive")
    void mixedTokens() {
        List<KotlinToken> tokens = lex("@GetMapping(\"/x\") fun f(c: Char = 'a', n: Long = 10L) { }");

        assertThat(texts(tokens)).containsExactly("@", "GetMapping", "(", "\"/x\"", ")", "fun", "f", "(", "c", ":", "Char",
                "=", "'a'", ",", "n", ":", "Long", "=", "10L", ")", "{", "}");
    }

    private static List<KotlinToken> lex(String source) {
        return KotlinLexer.tokenize(source).tokens();
    }

    /**
     * @param tokens a token stream
     * @return each token rendered as {@code kind-aware text}; strings keep their quotes so a single
     * literal is distinguishable from the identifier of the same name
     */
    private static List<String> texts(List<KotlinToken> tokens) {
        return tokens.stream()
            .filter(token -> token.kind() != KotlinToken.Kind.EOF)
            .map(token -> token.kind() == KotlinToken.Kind.STRING ? "\"" + token.text() + "\"" : token.text())
            .toList();
    }

    private static KotlinToken onlyString(List<KotlinToken> tokens) {
        return tokens.stream()
            .filter(token -> token.kind() == KotlinToken.Kind.STRING)
            .findFirst()
            .orElseThrow(() -> new AssertionError("no string literal in " + texts(tokens)));
    }
}

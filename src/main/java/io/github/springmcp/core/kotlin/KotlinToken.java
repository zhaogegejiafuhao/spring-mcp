package io.github.springmcp.core.kotlin;

/**
 * One lexical token of a Kotlin source file.
 *
 * <p>
 * Only what the index needs is distinguished: strings carry their decoded text, because annotation
 * paths are the one place where the exact characters matter. Everything else is either a name or a
 * punctuation mark.
 * </p>
 *
 * @param kind token category
 * @param text decoded text; for a string literal the content without the surrounding quotes, for a
 * symbol the symbol itself
 * @param line 1-based line where the token starts
 * @param column 1-based column where the token starts
 * @param template whether a string literal contains {@code $name} or {@code ${...}} interpolation,
 * which means its text is not a constant and must not be trusted as a path
 */
record KotlinToken(Kind kind, String text, int line, int column, boolean template) {

    /** Token categories the parser distinguishes. */
    enum Kind {

        /** Identifier or keyword; the parser compares the text. */
        IDENT,

        /** String literal, content decoded and quotes stripped. */
        STRING,

        /** Numeric or character literal, kept verbatim. */
        LITERAL,

        /** Punctuation, one token per character. */
        SYMBOL,

        /** End of input. */
        EOF

    }

    /**
     * @param symbol punctuation character to test, e.g. {@code "{"}
     * @return whether this token is exactly that symbol
     */
    boolean isSym(String symbol) {
        return kind == Kind.SYMBOL && text.equals(symbol);
    }

    /**
     * @param word identifier or keyword to test
     * @return whether this token is that identifier
     */
    boolean isIdent(String word) {
        return kind == Kind.IDENT && text.equals(word);
    }
}

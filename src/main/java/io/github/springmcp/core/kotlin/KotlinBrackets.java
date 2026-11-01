package io.github.springmcp.core.kotlin;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;

/**
 * Pre-computes the matching bracket of every {@code (}, {@code [} and <code>{</code>.
 *
 * <p>
 * Kotlin has no statement terminator, so every structural question — where a body ends, which
 * arguments belong together — is answered by jumping over balanced regions. Computing the pairs once
 * turns that from a scan into an array lookup, which is what keeps the whole parser linear.
 * </p>
 */
final class KotlinBrackets {

    private KotlinBrackets() {
    }

    /**
     * @param tokens token stream from {@link KotlinLexer#tokenize}
     * @return for each token index, the index of its partner, or {@code -1}; a mismatched pair is left
     * unmatched rather than paired with the wrong token
     */
    static int[] match(List<KotlinToken> tokens) {
        int[] match = new int[tokens.size()];
        Arrays.fill(match, -1);
        Deque<Integer> open = new ArrayDeque<>();
        for (int k = 0; k < tokens.size(); k++) {
            KotlinToken token = tokens.get(k);
            if (token.kind() != KotlinToken.Kind.SYMBOL) {
                continue;
            }
            String text = token.text();
            if ("(".equals(text) || "[".equals(text) || "{".equals(text)) {
                open.push(k);
                continue;
            }
            if (!")".equals(text) && !"]".equals(text) && !"}".equals(text)) {
                continue;
            }
            while (!open.isEmpty()) {
                int candidate = open.pop();
                String opener = tokens.get(candidate).text();
                if (("(".equals(opener) && ")".equals(text)) || ("[".equals(opener) && "]".equals(text))
                        || ("{".equals(opener) && "}".equals(text))) {
                    match[candidate] = k;
                    match[k] = candidate;
                    break;
                }
            }
        }
        return match;
    }
}

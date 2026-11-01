package io.github.springmcp.core.parse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParseStart;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.Providers;
import com.github.javaparser.ast.CompilationUnit;

/**
 * Creates JavaParser instances and turns files into {@link ParseOutcome}s.
 *
 * <p>
 * Two non-obvious rules live here.
 * </p>
 *
 * <p>
 * <strong>Never use {@code StaticJavaParser}.</strong> It holds a process-wide singleton that is
 * not thread-safe, and MCP tool handlers can run concurrently. A fresh {@code JavaParser} per
 * call costs microseconds and removes the whole class of problem.
 * </p>
 *
 * <p>
 * <strong>Source is read as a string, not streamed from a {@code Path} provider.</strong> The
 * file is already size-capped by the walker, and going through {@link Files#readString} makes
 * encoding behaviour explicit instead of depending on provider defaults.
 * </p>
 */
public final class JavaParserFactory {

    private static final ParserConfiguration CONFIG = new ParserConfiguration()
        // 目标工程可能用到 record / sealed / switch pattern matching，统一按最新语言级别解析。
        .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21)
        // 注释对静态分析没有价值，关掉可以显著降低内存占用（大型工程是数十 MB 级差异）。
        .setAttributeComments(false)
        .setCharacterEncoding(StandardCharsets.UTF_8);

    /**
     * Parses a file.
     * @param file source file, already known to exist
     * @return outcome, never {@code null}
     * @throws IOException when the file cannot be read
     */
    public ParseOutcome parse(Path file) throws IOException {
        SourceText source = SourceText.read(file);
        ParseOutcome parsed = parseSource(source.text());
        return new ParseOutcome(parsed.unit(), parsed.problems(), parsed.clean(), source);
    }

    /**
     * Parses source text. Exposed for tests and for callers that already hold the content.
     * @param source Java source
     * @return outcome, never {@code null}
     */
    public ParseOutcome parseSource(String source) {
        ParseResult<CompilationUnit> result = new JavaParser(CONFIG)
            .parse(ParseStart.COMPILATION_UNIT, Providers.provider(source));

        List<String> problems = new ArrayList<>();
        result.getProblems().forEach(p -> problems.add(p.getVerboseMessage()));

        // 即使 isSuccessful()==false 也可能带回部分 AST；只有完全拿不到节点时才算彻底失败。
        CompilationUnit unit = result.getResult().orElse(null);
        return new ParseOutcome(unit, problems, result.isSuccessful());
    }
}

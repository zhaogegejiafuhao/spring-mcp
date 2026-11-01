package io.github.springmcp.server.builtin;

import java.util.List;
import java.util.Locale;
import java.util.Set;

import io.github.springmcp.server.ProjectCatalog;
import io.github.springmcp.spi.Args;
import io.github.springmcp.spi.JsonSchemas;
import io.github.springmcp.spi.ToolDescriptor;
import io.github.springmcp.spi.ToolFailure;
import io.github.springmcp.spi.ToolInvocation;
import io.github.springmcp.spi.ToolOutcome;
import io.github.springmcp.spi.api.CoreAnalyzer;
import io.github.springmcp.spi.api.IndexHandle;
import io.github.springmcp.spi.api.IndexOptions;
import io.github.springmcp.spi.model.Symbols;

/**
 * {@code search-symbol}: find a type declaration by name.
 *
 * <p>
 * The cheapest useful tool in the box, and the one an agent reaches for most often: before reading
 * anything it needs the file that declares {@code OrderController}. Without this it greps, which also
 * matches comments, log strings and variables named after the class — and then it reads the wrong
 * file with full confidence.
 * </p>
 */
public final class SearchSymbolTool extends AbstractCoreTool {

    public static final String NAME = "search-symbol";

    /** Kinds the caller may filter on; the same set the index produces. */
    private static final List<String> KINDS = List.of("CLASS", "INTERFACE", "ENUM", "RECORD", "ANNOTATION");

    /**
     * @param analyzer analysis facade
     * @param catalog configured projects, may be empty
     * @param baseOptions options every query starts from
     */
    public SearchSymbolTool(CoreAnalyzer analyzer, ProjectCatalog catalog, IndexOptions baseOptions) {
        super(analyzer, catalog, baseOptions);
    }

    @Override
    public ToolDescriptor descriptor() {
        var schema = scopeSchema(projectSchema(JsonSchemas.object().title(NAME)))
            .string("name", "类型名或其片段，不区分大小写；也可以写全限定名的一部分，例如 acme.order", true, null)
            .arrayOfStrings("kinds", "只返回这些类型：CLASS/INTERFACE/ENUM/RECORD/ANNOTATION。省略表示全部。", false, KINDS)
            .integer("limit", "返回上限，默认 50", false, 1, 500)
            .bool("includeTests", "是否同时扫描 src/test/java，默认 false", false);

        return ToolDescriptor.builder(NAME)
            .title("按类型名查找")
            .description("""
                    按名字查类/接口/枚举/注解的声明位置：返回全限定名、类型种类、是否抽象、标注的注解、文件与行号。

                    结果按匹配质量排序：**完全同名**优先，其次前缀匹配，最后是包含匹配；同一档内短名字在前。\
                    因此查 OrderController 时它自己排第一，OrderControllerTest 之类排在后面。

                    与全文搜索的区别：只匹配真实声明，不会命中注释、日志字符串或「名字叫 X 的变量」。\
                    需要按注解找（如所有 @Scheduled）用 search-by-annotation，需要实现类用 find-implementations。

                    适用场景：拿到一个类名要找文件；确认某个类是否存在于本工程（还是来自依赖）。\
                    不适用：查方法名（本工具只索引类型），查 URL 映射。
                    """)
            .inputSchema(schema)
            .tags(List.of("meta", "navigation", "symbol"))
            .build();
    }

    @Override
    public ToolOutcome invoke(ToolInvocation invocation) {
        Args args = invocation.args();
        try {
            IndexHandle index = openIndex(args);
            Symbols.Report report = index.searchSymbols(new Symbols.Query(args.requireString("name"),
                    kinds(args.optionalStringList("kinds")), args.optionalInt("limit", 50, 1, 500)));

            String text = summary("类型", report.symbols().size(),
                    report.truncated() ? "共命中 " + report.total() + " 个，已截断" : null, index);
            return ToolOutcome.ok(report, text, mergeWarnings(report.warnings(), index));
        }
        catch (ToolFailure f) {
            return ToolOutcome.fail(f);
        }
    }

    /**
     * Normalises the kind filter.
     *
     * <p>
     * An unknown kind is rejected rather than dropped. Dropping it would turn a typo into "no filter",
     * and the caller would get every kind back while believing it had narrowed the search.
     * </p>
     *
     * @param raw values from the caller
     * @return validated kinds, empty for "all"
     * @throws ToolFailure when a value is not a known kind
     */
    private static Set<String> kinds(List<String> raw) throws ToolFailure {
        if (raw == null || raw.isEmpty()) {
            return Set.of();
        }
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        for (String value : raw) {
            if (value == null || value.isBlank()) {
                continue;
            }
            String kind = value.trim().toUpperCase(Locale.ROOT);
            if (!KINDS.contains(kind)) {
                throw ToolFailure.of(io.github.springmcp.spi.ErrorCode.INVALID_ARGUMENT,
                        "未知的类型种类: " + value, "可选值: " + String.join(", ", KINDS));
            }
            out.add(kind);
        }
        return Set.copyOf(out);
    }
}

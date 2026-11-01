package io.github.springmcp.server.builtin;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import io.github.springmcp.server.ProjectCatalog;
import io.github.springmcp.spi.Args;
import io.github.springmcp.spi.ErrorCode;
import io.github.springmcp.spi.JsonSchemas;
import io.github.springmcp.spi.ToolDescriptor;
import io.github.springmcp.spi.ToolFailure;
import io.github.springmcp.spi.ToolInvocation;
import io.github.springmcp.spi.ToolOutcome;
import io.github.springmcp.spi.api.CoreAnalyzer;
import io.github.springmcp.spi.api.IndexHandle;
import io.github.springmcp.spi.api.IndexOptions;
import io.github.springmcp.spi.model.Annotations;

/**
 * {@code search-by-annotation}: where does this annotation appear.
 */
public final class SearchByAnnotationTool extends AbstractCoreTool {

    public static final String NAME = "search-by-annotation";

    private static final List<String> ELEMENT_KINDS = List.of("TYPE", "METHOD", "CONSTRUCTOR", "FIELD", "PARAMETER");

    /**
     * @param analyzer analysis facade
     * @param catalog configured projects, used to resolve the {@code project} alias
     * @param includeTests whether test sources are indexed by default
     */
    public SearchByAnnotationTool(CoreAnalyzer analyzer, ProjectCatalog catalog, IndexOptions baseOptions) {
        super(analyzer, catalog, baseOptions);
    }

    @Override
    public ToolDescriptor descriptor() {
        var schema = scopeSchema(projectSchema(JsonSchemas.object().title(NAME)))
            .string("annotation", "注解名，简单名或全限定名均可，不写 @，例如 Transactional 或 "
                    + "org.springframework.transaction.annotation.Transactional", true, null)
            .arrayOfStrings("elementKinds", "只保留这些元素类型：TYPE/METHOD/CONSTRUCTOR/FIELD/PARAMETER。省略表示全部。",
                    false, ELEMENT_KINDS)
            .bool("strictFqn", "true 时只做全限定名精确匹配；默认按简单名匹配，可能命中同名自定义注解", false)
            .integer("limit", "返回命中数上限，默认 200", false, 1, 2000)
            .bool("includeTests", "是否同时扫描 src/test/java，默认 false", false);

        return ToolDescriptor.builder(NAME)
            .title("按注解搜索")
            .description("""
                    搜索被指定注解标注的元素（类/方法/构造器/字段/参数），返回所属类型、成员名、签名、文件与行号。

                    **不展开元注解**：查 @RequestMapping 不会返回 @GetMapping 标注的方法。\
                    需要端点信息请用 list-controllers，需要 mapper 信息请用 list-mappers。

                    默认按注解**简单名**匹配（查 Transactional 能命中带 import 的写法），\
                    若工程里存在同名自定义注解、需要精确匹配，请传全限定名并设 strictFqn=true。

                    适用场景：找所有 @Transactional / @Scheduled / @Cacheable 所在位置。\
                    不适用：查类型继承关系、查方法调用。
                    """)
            .inputSchema(schema)
            .tags(List.of("annotation", "search"))
            .build();
    }

    @Override
    public ToolOutcome invoke(ToolInvocation invocation) {
        Args args = invocation.args();
        try {
            IndexHandle index = openIndex(args);
            Set<Annotations.ElementKind> kinds = parseElementKinds(args.optionalStringList("elementKinds"));
            Annotations.Query query = new Annotations.Query(args.requireString("annotation"), kinds,
                    args.optionalBoolean("strictFqn", false), args.optionalInt("limit", 200, 1, 2000));

            Annotations.Report report = index.findByAnnotation(query);
            String text = "找到 " + report.total() + " 处 @" + args.optionalString("annotation", "")
                    + (report.truncated() ? "（已截断到 " + report.hits().size() + " 条）" : "") + scopeSuffix(index);
            return ToolOutcome.ok(report, text, mergeWarnings(report.warnings(), index));
        }
        catch (ToolFailure f) {
            return ToolOutcome.fail(f);
        }
    }

    private static Set<Annotations.ElementKind> parseElementKinds(List<String> raw) throws ToolFailure {
        if (raw.isEmpty()) {
            return Set.of();
        }
        Set<Annotations.ElementKind> out = new LinkedHashSet<>();
        for (String r : raw) {
            try {
                out.add(Annotations.ElementKind.valueOf(r.trim().toUpperCase(Locale.ROOT)));
            }
            catch (IllegalArgumentException e) {
                throw ToolFailure.of(ErrorCode.INVALID_ARGUMENT, "未知的 elementKinds 取值: " + r,
                        "可选值: " + String.join(", ", ELEMENT_KINDS));
            }
        }
        return out;
    }
}

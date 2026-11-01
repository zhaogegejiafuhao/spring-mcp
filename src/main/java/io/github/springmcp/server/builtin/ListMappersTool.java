package io.github.springmcp.server.builtin;

import java.util.LinkedHashSet;
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
import io.github.springmcp.spi.model.Mappers;

/**
 * {@code list-mappers}: MyBatis mappers and Spring Data repositories, interface and XML side joined.
 */
public final class ListMappersTool extends AbstractCoreTool {

    public static final String NAME = "list-mappers";

    private static final List<String> KINDS = List.of("ANNOTATION", "XML", "JPA");

    /**
     * @param analyzer analysis facade
     * @param catalog configured projects, used to resolve the {@code project} alias
     * @param includeTests whether test sources are indexed by default
     */
    public ListMappersTool(CoreAnalyzer analyzer, ProjectCatalog catalog, IndexOptions baseOptions) {
        super(analyzer, catalog, baseOptions);
    }

    @Override
    public ToolDescriptor descriptor() {
        var schema = scopeSchema(projectSchema(JsonSchemas.object().title(NAME)))
            .arrayOfStrings("kinds", "只保留这些来源：ANNOTATION(@Mapper 接口)、XML(MyBatis XML)、JPA(Spring Data Repository)。"
                    + "省略表示全部。", false, KINDS)
            .string("nameFilter", "按接口简单名或 XML 文件名的子串过滤（不区分大小写）", false, null)
            .bool("includeTests", "是否同时扫描 src/test，默认 false", false);

        return ToolDescriptor.builder(NAME)
            .title("列出持久层 Mapper")
            .description("""
                    列出工程中的持久层接口，并把同一个 mapper 的接口侧与 XML 侧合并成一条记录：

                    - ANNOTATION：带 @Mapper 的接口，方法上的 @Select/@Insert/@Update/@Delete 会转成 statement
                    - XML：resources 下的 MyBatis XML，按 namespace 关联到接口，抽取 select/insert/update/delete \
                    与 <sql> 片段及 <include> 引用；namespace 找不到对应接口的会进 orphans
                    - JPA：继承 JpaRepository/CrudRepository 等的接口，附带泛型推断出的实体类型与主键类型；\
                    实体写在中间接口的泛型实参里（CoinDao extends BaseDao<Coin>）也能沿继承链绑定，\
                    绑定不了就返回 null，不会把类型变量 T 当成实体名

                    代码里的 SQL 内容不解析（只记录 statement id、命令类型、参数/返回类型），\
                    `<if>`、`<foreach>` 等动态标签也不展开。

                    适用场景：查某个表/实体由哪个 mapper 操作、确认 XML 是否还有对应接口。\
                    不适用：查 Service 调用（find-service-callers）、查端点（list-controllers）。
                    """)
            .inputSchema(schema)
            .tags(List.of("mybatis", "jpa", "persistence"))
            .build();
    }

    @Override
    public ToolOutcome invoke(ToolInvocation invocation) {
        Args args = invocation.args();
        try {
            IndexHandle index = openIndex(args);
            Set<Mappers.Kind> kinds = parseKinds(args.optionalStringList("kinds"));
            Mappers.Report report = index.mappers(new Mappers.Query(kinds, args.optionalString("nameFilter", null)));

            String text = summary("持久层接口", report.mappers().size(),
                    report.counts().isEmpty() ? null : report.counts().toString(), index);
            return ToolOutcome.ok(report, text, mergeWarnings(report.warnings(), index));
        }
        catch (ToolFailure f) {
            return ToolOutcome.fail(f);
        }
    }

    private static Set<Mappers.Kind> parseKinds(List<String> raw) throws ToolFailure {
        if (raw.isEmpty()) {
            return Set.of();
        }
        Set<Mappers.Kind> out = new LinkedHashSet<>();
        for (String r : raw) {
            try {
                out.add(Mappers.Kind.valueOf(r.trim().toUpperCase(Locale.ROOT)));
            }
            catch (IllegalArgumentException e) {
                throw ToolFailure.of(io.github.springmcp.spi.ErrorCode.INVALID_ARGUMENT,
                        "未知的 kinds 取值: " + r, "可选值: " + String.join(", ", KINDS));
            }
        }
        return out;
    }
}

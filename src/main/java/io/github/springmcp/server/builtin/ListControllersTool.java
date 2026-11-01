package io.github.springmcp.server.builtin;

import java.util.List;

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
import io.github.springmcp.spi.model.Controllers;

/**
 * {@code list-controllers}: every HTTP endpoint a Spring project exposes.
 */
public final class ListControllersTool extends AbstractCoreTool {

    public static final String NAME = "list-controllers";

    /**
     * @param analyzer analysis facade
     * @param catalog configured projects, used to resolve the {@code project} alias
     * @param includeTests whether test sources are indexed by default
     */
    public ListControllersTool(CoreAnalyzer analyzer, ProjectCatalog catalog, IndexOptions baseOptions) {
        super(analyzer, catalog, baseOptions);
    }

    @Override
    public ToolDescriptor descriptor() {
        var schema = scopeSchema(projectSchema(JsonSchemas.object().title("list-controllers")))
            .bool("includeTests", "是否同时扫描 src/test/java，默认 false", false)
            .string("basePathFilter", "只保留以该前缀开头的端点路径，例如 /api", false, null)
            .enumString("httpMethod", "只保留该 HTTP 动词的端点", false,
                    List.of("GET", "POST", "PUT", "DELETE", "PATCH", "ANY"));

        return ToolDescriptor.builder(NAME)
            .title("列出 Controller 端点")
            .description("""
                    扫描 Spring 工程源码，列出所有 @Controller / @RestController 的 HTTP 端点：\
                    类级 @RequestMapping 前缀、每个 handler 的 HTTP 动词、拼接后的 URL、方法名、文件与行号。

                    端点带 conditions 字段：@Profile / @ConditionalOn* 这类条件装配逐条列出（不求值，只说条件是什么），\
                    回答"这个接口为什么在预发环境没有"时用得上。

                    纯静态分析：不启动 Spring 上下文。编译期常量会代入（static final String、接口常量、\
                    const val、常量套常量、内嵌类与 import static 的写法）；占位符（${...}、Kotlin 的 $name）、\
                    @Value 注入值与算不出来的拼接会原样返回并置 pathResolved=false，unresolvedReason 说明原因。

                    适用场景：想知道某个 URL 由哪个方法处理、某个 Controller 暴露了哪些接口。
                    不适用：查 Service 调用关系（用 find-service-callers）、查注解出现在哪里（用 search-by-annotation）。
                    """)
            .inputSchema(schema)
            .tags(List.of("spring", "web", "http"))
            .build();
    }

    @Override
    public ToolOutcome invoke(ToolInvocation invocation) {
        Args args = invocation.args();
        try {
            IndexHandle index = openIndex(args);
            Controllers.Report report = index.controllers(new Controllers.Query(args.optionalBoolean("includeTests", false),
                    args.optionalString("basePathFilter", null), args.optionalString("httpMethod", null)));

            String text = summary("端点", report.endpoints().size(),
                    report.controllerCount() + " 个 Controller / " + report.methodCount() + " 个 handler", index);
            return ToolOutcome.ok(report, text, mergeWarnings(report.warnings(), index));
        }
        catch (ToolFailure f) {
            return ToolOutcome.fail(f);
        }
    }
}

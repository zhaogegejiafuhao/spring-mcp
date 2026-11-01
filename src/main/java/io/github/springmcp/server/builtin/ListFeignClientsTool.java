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
import io.github.springmcp.spi.model.FeignClients;

/**
 * Lists Spring Cloud OpenFeign clients.
 *
 * <p>
 * Every other tool answers "who calls in". This one answers "what does this module call", which is
 * the other half of the arrow: with {@code list-gateway-routes} on one side and this on the other, a
 * service graph has both directions. {@code @FeignClient("mall-admin")} plus its mapped methods is
 * exactly the outbound contract, so nothing has to be guessed.
 * </p>
 */
public final class ListFeignClientsTool extends AbstractCoreTool {

    public static final String NAME = "list-feign-clients";

    /**
     * @param analyzer analysis facade
     * @param catalog configured projects, may be empty
     * @param baseOptions options every query starts from
     */
    public ListFeignClientsTool(CoreAnalyzer analyzer, ProjectCatalog catalog, IndexOptions baseOptions) {
        super(analyzer, catalog, baseOptions);
    }

    @Override
    public ToolDescriptor descriptor() {
        var schema = scopeSchema(projectSchema(JsonSchemas.object()
            .string("name", "按客户端名或接口简单名过滤，不区分大小写；省略返回全部", false, null)
            .title(NAME)));

        return ToolDescriptor.builder(NAME)
            .title("列出 Feign 客户端")
            .description("""
                    读取 Spring Cloud OpenFeign 的 @FeignClient 接口，返回每个客户端的服务名、接口全限定名、\
                    @FeignClient(path=...) 前缀、以及接口方法上的映射注解（动词 + 路径 + 方法名），\
                    并把这个服务名对应到本工程的哪个模块（解析不到则为 null，表示指向外部服务）。

                    这是**出站**方向：list-gateway-routes 说的是「外部能调进来什么」，这个工具说的是\
                    「本模块调了谁」。两者合起来才能画出服务依赖图。

                    适用场景：微服务依赖梳理；改某个服务的接口前，确认谁在调用它。\
                    不适用：单体工程的内部调用关系（用 find-service-callers）。
                    """)
            .inputSchema(schema)
            .tags(List.of("feign", "client", "discovery", "cloud"))
            .build();
    }

    @Override
    public ToolOutcome invoke(ToolInvocation invocation) {
        Args args = invocation.args();
        try {
            IndexHandle index = openIndex(args);
            FeignClients.Report report = index.feignClients(new FeignClients.Query(args.optionalString("name", null)));

            long internal = report.clients().stream().filter(client -> client.targetModule() != null).count();
            String text = summary("Feign 客户端", report.total(),
                    internal == report.total() ? null : internal + " 个指向本工程的模块", index);
            if (report.total() == 0 && report.warnings().isEmpty()) {
                text = "未找到 Feign 客户端：工程里没有 @FeignClient，或服务间调用用的是 RestClient/RestTemplate"
                        + scopeSuffix(index);
            }
            return ToolOutcome.ok(report, text, mergeWarnings(report.warnings(), index));
        }
        catch (ToolFailure f) {
            return ToolOutcome.fail(f);
        }
    }
}

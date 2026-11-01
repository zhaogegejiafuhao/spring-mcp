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
import io.github.springmcp.spi.model.GatewayRoutes;

/**
 * {@code list-gateway-routes}: the front door of a microservice system.
 *
 * <p>
 * Every other tool answers "what does this code serve". In a Spring Cloud system that is not the same
 * question as "what does a client call": the gateway prefixes and strips segments, so {@code /owners}
 * inside {@code customers-service} may well be {@code /api/customers/owners} outside. The route table is
 * the only place that mapping exists, and it lives in YAML.
 * </p>
 *
 * <p>
 * Routes declared as {@code RouteLocator} beans — Java DSL and Kotlin DSL alike — are read too, so a
 * project that moved its routing into code gets the same table. A {@code uri} that is not a literal is
 * reported as written and counted in the warnings, because returning an empty list would read like
 * "this project has no gateway".
 * </p>
 */
public final class ListGatewayRoutesTool extends AbstractCoreTool {

    public static final String NAME = "list-gateway-routes";

    /**
     * @param analyzer analysis facade
     * @param catalog configured projects, may be empty
     * @param baseOptions options every query starts from
     */
    public ListGatewayRoutesTool(CoreAnalyzer analyzer, ProjectCatalog catalog, IndexOptions baseOptions) {
        super(analyzer, catalog, baseOptions);
    }

    @Override
    public ToolDescriptor descriptor() {
        var schema = scopeSchema(projectSchema(JsonSchemas.object().title(NAME)));

        return ToolDescriptor.builder(NAME)
            .title("列出网关路由")
            .description("""
                    读取 Spring Cloud Gateway 的路由表，两个来源都读：配置文件（application*.yml / bootstrap*.yml 里的 \
                    spring.cloud.gateway.routes，以及 4.x 的 spring.cloud.gateway.server.webflux.routes）\
                    和代码里的 RouteLocator bean（Java 与 Kotlin DSL）。\
                    返回每条路由的 id、目标 uri、断言（如 Path=/mall-admin/**）、过滤器（如 StripPrefix=1）、\
                    声明所在的配置文件或类，以及这条路由指向工程内哪个模块。

                    网关路由和 list-controllers 的结果是**两套 URL**：网关通常加了前缀并 StripPrefix，\
                    所以客户端看到的路径与服务内部处理的路径不同。要回答"外部能调到什么"，两个都要看。

                    核心边界：uri 不是字面量时（@Value 注入、字符串拼接）按原文返回并在 warnings 里计数，\
                    不会猜一个 URL；discovery.locator.enabled=true 时还有 /{serviceId}/** 的隐式路由，只做提示、不展开。

                    适用场景：微服务工程的对外入口梳理；确认某个服务是否被网关暴露。\
                    不适用：单体工程的 URL 清单（用 list-controllers）。
                    """)
            .inputSchema(schema)
            .tags(List.of("gateway", "routing", "discovery", "cloud"))
            .build();
    }

    @Override
    public ToolOutcome invoke(ToolInvocation invocation) {
        Args args = invocation.args();
        try {
            IndexHandle index = openIndex(args);
            GatewayRoutes report = index.gatewayRoutes();

            long resolved = report.routes().stream().filter(route -> route.targetModule() != null).count();
            String text = summary("网关路由", report.count(), resolved == report.count()
                    ? null
                    : resolved + " 条指向本工程的模块", index);
            if (report.count() == 0 && report.warnings().isEmpty()) {
                text = "未找到网关路由：工程里没有 spring.cloud.gateway 配置，或网关在别的仓库" + scopeSuffix(index);
            }
            return ToolOutcome.ok(report, text, mergeWarnings(report.warnings(), index));
        }
        catch (ToolFailure f) {
            return ToolOutcome.fail(f);
        }
    }
}

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
import io.github.springmcp.spi.model.TypeHierarchy;

/**
 * {@code find-implementations}: which classes implement an interface, and which of them are beans.
 *
 * <p>
 * The counterpart to {@code find-service-callers}: one answers "who uses it", this answers "what
 * stands behind it". In a Spring code base the second question decides where a change has to go, and
 * grep cannot answer it — {@code implements OrderService} finds direct implementations only, missing
 * everything that reaches the interface through an abstract base.
 * </p>
 */
public final class FindImplementationsTool extends AbstractCoreTool {

    public static final String NAME = "find-implementations";

    /**
     * @param analyzer analysis facade
     * @param catalog configured projects, may be empty
     * @param baseOptions options every query starts from
     */
    public FindImplementationsTool(CoreAnalyzer analyzer, ProjectCatalog catalog, IndexOptions baseOptions) {
        super(analyzer, catalog, baseOptions);
    }

    @Override
    public ToolDescriptor descriptor() {
        var schema = scopeSchema(projectSchema(JsonSchemas.object().title(NAME)))
            .string("type", "接口或类，简单名或全限定名，例如 OrderService", true, null)
            .bool("includeAbstract", "是否把抽象类也算作实现，默认 false（抽象类不是可实例化的 Bean）", false)
            .bool("includeInterfaces", "是否把子接口也算进来，默认 false", false)
            .bool("includeTests", "是否同时扫描 src/test/java，默认 false", false);

        return ToolDescriptor.builder(NAME)
            .title("查找接口实现类")
            .description("""
                    给定接口或类，返回工程内所有实现/继承它的类型，并标注每个类型是否为 Spring Bean\
                    （Service/Component/Repository/Controller/Configuration）、是直接实现还是经由父类间接实现。

                    **默认不含抽象类与子接口**：抽象基类不能注册成 Bean，混在"实现类"里会让答案看起来比实际多。\
                    需要时传 includeAbstract / includeInterfaces，此时 warnings 会说明有多少个被跳过。

                    传递实现也会被找到（A extends AbstractBase implements OrderService），direct=false 表示不是自己直接声明的。

                    适用场景：改一个接口前确认有哪些实现要一起改；确认某个接口在工程里到底有几个实现。\
                    不适用：查谁调用了它（用 find-service-callers）、查端点（用 list-controllers）。
                    """)
            .inputSchema(schema)
            .tags(List.of("spring", "type", "hierarchy"))
            .build();
    }

    @Override
    public ToolOutcome invoke(ToolInvocation invocation) {
        Args args = invocation.args();
        try {
            IndexHandle index = openIndex(args);
            TypeHierarchy.Report report = index.implementations(new TypeHierarchy.Query(
                    args.requireString("type"), args.optionalBoolean("includeAbstract", false),
                    args.optionalBoolean("includeInterfaces", false)));

            long beans = report.implementations()
                .stream()
                .filter(implementation -> !implementation.springStereotypes().isEmpty())
                .count();
            String text = summary("实现类", report.count(),
                    beans > 0 ? beans + " 个标注为 Spring Bean" : null, index);
            return ToolOutcome.ok(report, text, mergeWarnings(report.warnings(), index));
        }
        catch (ToolFailure f) {
            return ToolOutcome.fail(f);
        }
    }
}

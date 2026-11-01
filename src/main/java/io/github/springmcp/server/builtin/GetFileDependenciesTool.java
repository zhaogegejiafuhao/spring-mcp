package io.github.springmcp.server.builtin;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;

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
import io.github.springmcp.spi.model.Dependencies;

/**
 * {@code get-file-dependencies}: what this file uses and what uses it.
 */
public final class GetFileDependenciesTool extends AbstractCoreTool {

    public static final String NAME = "get-file-dependencies";

    /**
     * @param analyzer analysis facade
     * @param catalog configured projects, used to resolve the {@code project} alias
     * @param includeTests whether test sources are indexed by default
     */
    public GetFileDependenciesTool(CoreAnalyzer analyzer, ProjectCatalog catalog, IndexOptions baseOptions) {
        super(analyzer, catalog, baseOptions);
    }

    @Override
    public ToolDescriptor descriptor() {
        var schema = scopeSchema(projectSchema(JsonSchemas.object().title(NAME)))
            .string("file", "目标源文件（.java / .kt）；绝对路径，或相对 projectRoot 的路径", true, null)
            .bool("includeSamePackage", "是否列出同包下的其它文件（同包耦合不需要 import，容易漏看），默认 false", false)
            .bool("includeTests", "是否同时扫描 src/test/java，默认 false", false);

        return ToolDescriptor.builder(NAME)
            .title("查看文件依赖")
            .description("""
                    给一个 .java 文件，返回它的双向依赖：

                    - outgoing：该文件用到哪些类型（import、import static、通配 import、同包引用、类型使用），\
                    并区分工程内与外部（JDK/三方库）
                    - incoming：工程里哪些文件用到了该文件声明的类型，包含**同包但没写 import** 的隐式耦合

                    入向依赖既看 import，也看同包内的类型名引用——很多 Spring 工程同包耦合极重，\
                    只统计 import 会得出"没人依赖它"的错误结论。

                    includeSamePackage=true 时会额外列出同包兄弟文件，用于察觉潜在耦合，不代表已确认依赖。

                    适用场景：改一个类前评估影响面、排查循环依赖。\
                    不适用：查方法级调用（用 find-service-callers）。
                    """)
            .inputSchema(schema)
            .tags(List.of("dependency", "graph"))
            .build();
    }

    @Override
    public ToolOutcome invoke(ToolInvocation invocation) {
        Args args = invocation.args();
        try {
            IndexHandle index = openIndex(args);
            Path file = toPath(args.requireString("file"));
            boolean samePackage = args.optionalBoolean("includeSamePackage", false);

            Dependencies.Report report = index.fileDependencies(file, samePackage);
            String text = "出向依赖 " + report.outgoing().size() + " 个，入向依赖 " + report.incoming().size() + " 个"
                    + (report.typeFqn() == null ? "" : "（主类型 " + report.typeFqn() + "）") + scopeSuffix(index);
            return ToolOutcome.ok(report, text, mergeWarnings(report.warnings(), index));
        }
        catch (ToolFailure f) {
            return ToolOutcome.fail(f);
        }
    }

    private static Path toPath(String raw) throws ToolFailure {
        try {
            return Path.of(raw);
        }
        catch (InvalidPathException e) {
            throw ToolFailure.of(ErrorCode.INVALID_ARGUMENT, "不是合法路径: " + raw, "检查路径中是否有非法字符", e);
        }
    }
}

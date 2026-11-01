package io.github.springmcp.example;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.springmcp.spi.Args;
import io.github.springmcp.spi.ErrorCode;
import io.github.springmcp.spi.JsonSchemas;
import io.github.springmcp.spi.PluginContext;
import io.github.springmcp.spi.SpringMcpPlugin;
import io.github.springmcp.spi.ToolDescriptor;
import io.github.springmcp.spi.ToolFailure;
import io.github.springmcp.spi.ToolInvocation;
import io.github.springmcp.spi.ToolOutcome;
import io.github.springmcp.spi.ToolProvider;
import io.github.springmcp.spi.api.IndexHandle;
import io.github.springmcp.spi.api.IndexOptions;
import io.github.springmcp.spi.model.Annotations;

/**
 * Reference implementation of a spring-mcp plugin.
 *
 * <p>
 * Copy this class as the starting point for a real plugin. Three things are needed and nothing else:
 * </p>
 * <ol>
 * <li>implement {@link SpringMcpPlugin}, returning one or more {@link ToolProvider}s</li>
 * <li>declare the class in {@code META-INF/services/io.github.springmcp.spi.SpringMcpPlugin}</li>
 * <li>drop the jar into the server's {@code plugins/} directory</li>
 * </ol>
 *
 * <p>
 * This copy lives in the test source tree on purpose: it is compiled and exercised by
 * {@code ExamplePluginTest}, so the sample cannot rot, and it never ships inside the server jar
 * (a plugin bundled into the server itself would be dead weight — plugins are loaded from
 * {@code plugins/}, not from the server's own classpath).
 * </p>
 *
 * <p>
 * Note what the plugin does <em>not</em> do: it never touches JavaParser, never parses a file, and
 * never sees an MCP protocol type. It asks {@link PluginContext#analyzer()} for one of the existing
 * analyses and reshapes the answer. That is the intended extension pattern — reach for raw AST access
 * only when the built-in analyses genuinely cannot answer the question.
 * </p>
 */
public final class ExamplePlugin implements SpringMcpPlugin {

    private PluginContext context;

    @Override
    public String id() {
        return "io.github.springmcp.example";
    }

    @Override
    public String version() {
        return "0.1.0";
    }

    @Override
    public String displayName() {
        return "spring-mcp example plugin";
    }

    @Override
    public void initialize(PluginContext context) throws ToolFailure {
        this.context = context;
        context.logger().info("示例插件已初始化，工具: list-scheduled-jobs");
    }

    @Override
    public List<ToolProvider> tools() {
        return List.of(new ScheduledJobsTool(context));
    }

    /**
     * A tool that lists {@code @Scheduled} methods.
     *
     * <p>
     * Deliberately built on top of {@code findByAnnotation} rather than its own parser: it is a
     * realistic example of an extension that adds a new <em>view</em> over data the core already
     * understands.
     * </p>
     */
    static final class ScheduledJobsTool implements ToolProvider {

        private final PluginContext context;

        ScheduledJobsTool(PluginContext context) {
            this.context = context;
        }

        @Override
        public ToolDescriptor descriptor() {
            return ToolDescriptor.builder("list-scheduled-jobs")
                .title("列出定时任务")
                .description("""
                        列出工程中所有被 @Scheduled 标注的方法，附带所属类、方法签名、cron 表达式与文件行号。

                        这是 spring-mcp 的示例插件，用于演示插件扩展方式。它基于内建的注解搜索能力实现，\
                        因此与内建工具一样，只做 AST 字面分析：cron 表达式写成常量引用时无法解析。

                        适用场景：盘点一个工程的定时任务。\
                        不适用：查 HTTP 端点（用 list-controllers）。
                        """)
                .inputSchema(JsonSchemas.object()
                    .title("list-scheduled-jobs")
                    .string("projectRoot", "被分析工程的根目录；省略时使用服务启动参数 --project 的值", false, null))
                .build();
        }

        @Override
        public ToolOutcome invoke(ToolInvocation invocation) {
            Args args = invocation.args();
            try {
                IndexHandle index = open(args);
                Annotations.Report hits = index.findByAnnotation(
                        new Annotations.Query("Scheduled", Set.of(Annotations.ElementKind.METHOD), false, 500));

                List<Map<String, Object>> jobs = new ArrayList<>();
                for (Annotations.Hit hit : hits.hits()) {
                    Map<String, Object> job = new LinkedHashMap<>();
                    job.put("type", hit.ownerType());
                    job.put("method", hit.memberName());
                    job.put("signature", hit.signature());
                    job.put("file", hit.file());
                    job.put("line", hit.line());
                    jobs.add(job);
                }

                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("jobs", jobs);
                payload.put("count", jobs.size());
                payload.put("warnings", hits.warnings());

                return ToolOutcome.ok(payload, "找到 " + jobs.size() + " 个定时任务", hits.warnings());
            }
            catch (ToolFailure failure) {
                return ToolOutcome.fail(failure);
            }
        }

        private IndexHandle open(Args args) throws ToolFailure {
            Path root = args.optionalExistingDirectory("projectRoot", context.defaultProjectRoot());
            if (root == null) {
                throw ToolFailure.of(ErrorCode.INVALID_ARGUMENT, "缺少必填参数 'projectRoot'",
                        "传工程根目录绝对路径，或用 --project 启动服务设置默认值");
            }
            return context.analyzer().open(root, IndexOptions.defaults());
        }
    }
}

package io.github.springmcp.server.builtin;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.springmcp.server.ProjectCatalog;
import io.github.springmcp.spi.Args;
import io.github.springmcp.spi.ErrorCode;
import io.github.springmcp.spi.JsonSchemas;
import io.github.springmcp.spi.ToolDescriptor;
import io.github.springmcp.spi.ToolFailure;
import io.github.springmcp.spi.ToolInvocation;
import io.github.springmcp.spi.ToolOutcome;
import io.github.springmcp.spi.ToolProvider;
import io.github.springmcp.spi.api.CoreAnalyzer;
import io.github.springmcp.spi.api.IndexHandle;
import io.github.springmcp.spi.api.IndexOptions;

/**
 * {@code list-modules}: which modules a multi-module project has, and what each depends on.
 *
 * <p>
 * Exists because {@code scope} would otherwise be unusable: the analysis tools cannot enumerate
 * module names in their schemas without opening the index while building descriptors at startup, so
 * a caller would have to guess names from the directory tree and would get an empty answer when it
 * guessed wrong.
 * </p>
 *
 * <p>
 * Unlike {@code list-projects}, this tool does open the index — module detection is part of the
 * snapshot. That is the same cost the next analysis call would pay anyway, so it is not wasted.
 * </p>
 */
public final class ListModulesTool implements ToolProvider {

    public static final String NAME = "list-modules";

    /** How many module names to put in the summary before trimming to a count. */
    private static final int SUMMARY_NAMES = 6;

    private final CoreAnalyzer analyzer;

    private final ProjectCatalog catalog;

    private final IndexOptions baseOptions;

    /**
     * @param analyzer analysis facade
     * @param catalog configured projects
     * @param baseOptions options every query starts from
     */
    public ListModulesTool(CoreAnalyzer analyzer, ProjectCatalog catalog, IndexOptions baseOptions) {
        this.analyzer = analyzer;
        this.catalog = catalog == null ? ProjectCatalog.empty() : catalog;
        this.baseOptions = baseOptions == null ? IndexOptions.defaults() : baseOptions;
    }

    @Override
    public ToolDescriptor descriptor() {
        var schema = JsonSchemas.object().title(NAME)
            .string("projectRoot", "被分析工程的根目录绝对路径；省略时用启动参数 --project 的默认工程。", false, null);
        if (!catalog.isEmpty()) {
            schema.string("project", "工程别名（可选值: " + String.join(", ", catalog.names()) + "）。与 projectRoot 互斥。",
                    false, null);
        }
        schema.bool("includeDependencies", "是否附带每个模块声明依赖的其他模块，默认 true", false);

        return ToolDescriptor.builder(NAME)
            .title("列出工程模块")
            .description("""
                    列出多模块工程（Maven <modules>、Gradle include）的模块清单，以及每个模块声明依赖了哪些\
                    其他模块。返回的 module 值可以直接传给其他工具的 `scope` 参数。

                    scope 只过滤**结果**，不减少索引构建时间：索引始终覆盖整个工程，否则跨模块的类型\
                    就无法解析。想减少耗时应把 projectRoot 指向更小的目录，代价是丢失跨模块解析。

                    写上级目录会包含其下所有子模块（services 覆盖 services/order）。\
                    单模块工程返回空列表并说明原因——此时不需要 scope。\
                    模块边界只从构建文件读取，不按目录名猜：识别不到时宁可不提供 scope，也不给出错误的边界。

                    适用场景：大型仓库里只关心某几个模块；想知道改 common 会影响哪些模块。\
                    不适用：查源码内容（用 list-controllers 等）。
                    """)
            .inputSchema(schema)
            .tags(List.of("meta", "discovery", "module"))
            .build();
    }

    @Override
    public ToolOutcome invoke(ToolInvocation invocation) {
        Args args = invocation.args();
        try {
            IndexHandle index = ProjectRoots.requireSources(resolveRoot(args),
                    analyzer.open(resolveRoot(args), baseOptions));
            List<String> modules = index.modules();

            if (modules.isEmpty()) {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("modules", List.of());
                payload.put("count", 0);
                payload.put("reason", "未识别到多模块结构");
                return ToolOutcome.ok(payload,
                        "该工程未识别到多模块结构（少于 2 个模块），scope 参数不适用；直接查询即可。");
            }

            boolean withDependencies = args.optionalBoolean("includeDependencies", true);
            Map<String, List<String>> dependencies = withDependencies ? index.moduleDependencies() : Map.of();

            List<Map<String, Object>> entries = new ArrayList<>();
            for (String module : modules) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("module", module);
                if (withDependencies) {
                    entry.put("dependsOn", dependencies.getOrDefault(module, List.of()));
                }
                entries.add(entry);
            }

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("modules", entries);
            payload.put("count", entries.size());
            payload.put("root", index.root().toString());
            if (withDependencies && dependencies.isEmpty()) {
                payload.put("dependencyNote", "未从构建文件读到模块间依赖声明，dependsOn 均为空");
            }

            return ToolOutcome.ok(payload, summary(modules));
        }
        catch (ToolFailure f) {
            return ToolOutcome.fail(f);
        }
    }

    private Path resolveRoot(Args args) throws ToolFailure {
        String alias = args.optionalString("project", null);
        if (alias != null && args.has("projectRoot")) {
            throw ToolFailure.of(ErrorCode.INVALID_ARGUMENT, "参数 'project' 与 'projectRoot' 不能同时使用",
                    "二者选一：传 'project' 用配置好的别名，或传 'projectRoot' 用绝对路径");
        }
        if (alias != null) {
            return catalog.byAlias(alias)
                .orElseThrow(() -> ToolFailure.of(ErrorCode.INVALID_ARGUMENT, "未知的工程别名: " + alias,
                        "可用别名: " + catalog.describeAvailable()))
                .root();
        }
        Path configured = args.optionalExistingDirectory("projectRoot", catalog.defaultRoot());
        if (configured == null) {
            throw ToolFailure.of(ErrorCode.INVALID_ARGUMENT, "缺少工程参数",
                    "请传 'projectRoot'（绝对路径），或在启动服务时用 --project=<dir> 设默认值");
        }
        return configured;
    }

    private static String summary(List<String> modules) {
        List<String> shown = modules.size() <= SUMMARY_NAMES ? modules : modules.subList(0, SUMMARY_NAMES);
        String names = String.join(", ", shown) + (modules.size() > SUMMARY_NAMES ? " 等" : "");
        return "识别到 " + modules.size() + " 个模块：" + names
                + "。把其中某个传给其他工具的 scope 参数即可限定结果范围。";
    }
}

package io.github.springmcp.server.builtin;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    /** Upper bound for limit / offset, so a typo cannot turn into an unbounded loop. */
    private static final int MAX_PAGE = 100_000;

    /**
     * Page size when the caller does not ask. A 246-endpoint repository serialises to ~140 KB, which
     * is roughly 36k tokens — enough to end a session. Interactive callers get a page and a warning;
     * an audit passes {@code limit=0} because a truncated inventory is not an inventory.
     */
    private static final int DEFAULT_LIMIT = 200;

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
                    List.of("GET", "POST", "PUT", "DELETE", "PATCH", "ANY"))
            .integer("limit", "本次最多返回多少条端点。省略时默认 200 条（防止大仓库一次撑爆上下文）；"
                    + "传 0 表示不截断，全量返回。controllerCount / methodCount / stats 始终是全量",
                    false, 0, MAX_PAGE)
            .integer("offset", "从第几条匹配的端点开始返回，配合 limit 翻页，默认 0", false, 0, MAX_PAGE)
            .enumString("detail", "compact 只回回答问题必需的字段（路径相对化、不再重复全限定名），"
                    + "约为 full 的三分之一；需要 signature / classPathRaw / 绝对路径时用 full", false,
                    List.of("full", "compact"));

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

            List<String> pageWarnings = new ArrayList<>();
            int total = report.endpoints().size();
            int limit = args.optionalInt("limit", DEFAULT_LIMIT, 0, MAX_PAGE);
            int offset = args.optionalInt("offset", 0, 0, MAX_PAGE);

            // limit=0 是显式的「全要」，默认值则是一页。两者必须区分开：否则审计拿不到全量，
            // 交互式调用又会被一个 140 KB 的响应噎死。
            if (limit > 0 || offset > 0) {
                int from = Math.min(offset, total);
                int to = limit > 0 ? Math.min(total, from + limit) : total;
                boolean capped = limit > 0 && to < total;
                report = new Controllers.Report(report.endpoints().subList(from, to), report.controllerCount(),
                        report.methodCount(), report.stats(), report.warnings());
                if (capped) {
                    // 页码要说清楚：不说明的话，endpoints 的长度会被当成「工程只有这么多端点」。
                    pageWarnings.add("端点列表已分页：本次返回第 " + (from + 1) + "–" + to + " 条，共 " + total
                            + " 条。controllerCount / methodCount / stats 仍是全量；继续翻页请用 offset=" + to
                            + "，要全量请传 limit=0");
                }
            }

            String text = summary("端点", total,
                    report.controllerCount() + " 个 Controller / " + report.methodCount() + " 个 handler", index);
            List<String> warnings = new ArrayList<>(pageWarnings);
            warnings.addAll(report.warnings());
            List<String> merged = mergeWarnings(warnings, index);

            if ("compact".equalsIgnoreCase(args.optionalString("detail", "full"))) {
                return ToolOutcome.ok(compact(report, total, resolveRoot(args)), text, merged);
            }
            return ToolOutcome.ok(report, text, merged);
        }
        catch (ToolFailure f) {
            return ToolOutcome.fail(f);
        }
    }

    /**
     * Compact projection: only the fields an answer actually needs.
     *
     * <p>
     * Two invariants make the smaller shape safe: every row in {@code endpoints} has a resolved path
     * (anything unresolved moves to {@code unresolved} with its reason), and a {@code conditions} field
     * is present only when the handler is gated. Absent therefore means "none", which is the one
     * omission the compact shape is allowed — the counts and {@code stats} are always totals.
     * </p>
     *
     * @param report the page to project
     * @param total matched endpoints before paging
     * @param root project root, used to make file paths relative
     * @return an ordered map ready for structured content
     */
    private static Map<String, Object> compact(Controllers.Report report, int total, Path root) {
        List<Map<String, Object>> rows = new ArrayList<>();
        List<Map<String, Object>> unresolved = new ArrayList<>();

        for (Controllers.Endpoint e : report.endpoints()) {
            String file = relative(root, e.file());
            if (!e.pathResolved()) {
                Map<String, Object> u = new LinkedHashMap<>();
                u.put("path", e.path());
                u.put("handler", e.handlerMethod());
                u.put("file", file);
                u.put("line", e.line());
                u.put("reason", e.unresolvedReason());
                unresolved.add(u);
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("method", String.join(",", e.httpMethods()));
            row.put("path", e.path());
            row.put("handler", e.handlerMethod());
            row.put("controller", simpleName(e.controllerFqn()));
            row.put("file", file);
            row.put("line", e.line());
            if (!e.conditions().isEmpty()) {
                row.put("conditions", e.conditions());
            }
            rows.add(row);
        }

        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("endpoints", total);
        counts.put("controllers", report.controllerCount());
        counts.put("handlers", report.methodCount());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("counts", counts);
        out.put("endpoints", rows);
        if (!unresolved.isEmpty()) {
            out.put("unresolved", unresolved);
        }
        out.put("stats", report.stats());
        return out;
    }

    private static String relative(Path root, String file) {
        try {
            return root.relativize(Path.of(file)).toString().replace('\\', '/');
        }
        catch (RuntimeException e) {
            // Not under the root (a linked source dir, a different drive): keep the absolute path
            // rather than emit a misleading "../.." chain.
            return file;
        }
    }

    private static String simpleName(String fqn) {
        if (fqn == null || fqn.isEmpty()) {
            return "";
        }
        int dot = fqn.lastIndexOf('.');
        return dot < 0 ? fqn : fqn.substring(dot + 1);
    }
}

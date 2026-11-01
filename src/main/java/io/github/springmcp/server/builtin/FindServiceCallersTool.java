package io.github.springmcp.server.builtin;

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
import io.github.springmcp.spi.model.ServiceCalls;

/**
 * {@code find-service-callers}: who calls this service.
 */
public final class FindServiceCallersTool extends AbstractCoreTool {

    public static final String NAME = "find-service-callers";

    /**
     * @param analyzer analysis facade
     * @param catalog configured projects, used to resolve the {@code project} alias
     * @param includeTests whether test sources are indexed by default
     */
    public FindServiceCallersTool(CoreAnalyzer analyzer, ProjectCatalog catalog, IndexOptions baseOptions) {
        super(analyzer, catalog, baseOptions);
    }

    @Override
    public ToolDescriptor descriptor() {
        var schema = scopeSchema(projectSchema(JsonSchemas.object().title(NAME)))
            .string("serviceClass", "目标 Service 的简单类名或全限定名，例如 OrderService", true, null)
            .string("methodName", "只查该方法的调用点；省略则返回该 Service 所有方法的调用点", false, null)
            .bool("includeSelfCalls", "是否包含 Service 内部对自身的调用，默认 true", false)
            .bool("includeUnresolved", "是否列出名字对得上但接收者类型无法解析的调用点，默认 true", false)
            .bool("includeLowConfidence",
                    "是否包含按变量名推断（如 orderService -> OrderService）的低置信度结果，默认 false，容易误报", false)
            .integer("limit", "返回调用点的上限，默认 500", false, 1, 5000)
            .integer("depth", "影响面层数：1=只看直接调用（默认），2=再看一层（谁调用了直接调用方的类型）",
                    false, 1, 2)
            .integer("maxUpstreamTypes", "depth=2 时最多向上展开多少个调用方类型，默认 20", false, 1, 100)
            .bool("includeTests", "是否同时扫描 src/test/java，默认 false", false);

        return ToolDescriptor.builder(NAME)
            .title("查找 Service 调用方")
            .description("""
                    给定一个 Service 类（可限定方法名），返回工程内所有**直接调用点**：\
                    调用方类、调用方方法、接收者表达式、解析出的接收者类型、文件、行号、源码片段，\
                    以及命中依据（rule）和置信度（confidence）。

                    默认只返回**直接调用点**。需要影响面时设 depth=2，会再向上展开一层：\
                    对第一层出现的每个调用方类型，查「谁调用了这个类型」。

                    **depth=2 是近似**：第二层是「调用了第一层类型的方法」，无法限定到具体方法，\
                    因此可能包含与该目标无关的调用。它给的是影响面边界，不是精确清单。\
                    展开的类型数受 maxUpstreamTypes 限制，超出部分会在 warnings 里说明。

                    纯语法解析，不解析动态代理与泛型推断。链式调用 getX().y()、方法引用、lambda 形参\
                    会进入 unresolved 列表并给出原因，而不是猜一个类型。

                    rule 含义：FIELD/METHOD_PARAM/CONSTRUCTOR_PARAM/LOCAL_VAR 表示类型来自真实声明，\
                    可信度高；NAME_CONVENTION 是按变量名猜的，默认不返回。

                    适用场景：判断改一个 Service 方法会影响哪些地方。\
                    不适用：查一个类依赖了谁（用 get-file-dependencies）、查 URL 映射（用 list-controllers）。
                    """)
            .inputSchema(schema)
            .tags(List.of("spring", "service", "callgraph"))
            .build();
    }

    @Override
    public ToolOutcome invoke(ToolInvocation invocation) {
        Args args = invocation.args();
        try {
            IndexHandle index = openIndex(args);
            ServiceCalls.Query query = new ServiceCalls.Query(args.requireString("serviceClass"),
                    args.optionalString("methodName", null), args.optionalBoolean("includeSelfCalls", true),
                    args.optionalBoolean("includeUnresolved", true),
                    args.optionalBoolean("includeLowConfidence", false), args.optionalInt("limit", 500, 1, 5000));

            ServiceCalls.Report report = index.findServiceCallers(query);
            int depth = args.optionalInt("depth", 1, 1, 2);

            String summary = "在 " + report.targetFqn() + " 上找到 " + report.resolvedCount() + " 处直接调用"
                    + (report.unresolvedCount() > 0 ? "，另有 " + report.unresolvedCount() + " 处无法解析" : "")
                    + (report.truncated() ? "（结果已截断）" : "") + scopeSuffix(index);
            if (depth < 2) {
                return ToolOutcome.ok(report, summary, mergeWarnings(report.warnings(), index));
            }

            int maxTypes = args.optionalInt("maxUpstreamTypes", 20, 1, 100);
            return upstream(index, report, maxTypes, summary, mergeWarnings(report.warnings(), index));
        }
        catch (ToolFailure f) {
            return ToolOutcome.fail(f);
        }
    }

    /**
     * Expands one more level: who calls the types that call the target.
     *
     * <p>
     * Built on top of the same single-level analysis instead of a recursive one on purpose. A
     * recursive version would have to define what "calls a type" means for overloads, and in doing so
     * would quietly change the meaning of the depth=1 answer. Keeping level two in a separate,
     * explicitly labelled list lets a caller trust level one and treat level two as a boundary.
     * </p>
     *
     * @param index scoped index handle
     * @param direct level-one report
     * @param maxTypes cap on how many caller types are expanded
     * @param summary headline already composed for level one
     * @param warnings warnings already collected
     * @return an outcome whose payload separates the two levels
     */
    private ToolOutcome upstream(IndexHandle index, ServiceCalls.Report direct, int maxTypes, String summary,
            List<String> warnings) {
        List<String> callerTypes = direct.callSites()
            .stream()
            .map(ServiceCalls.CallSite::callerType)
            .filter(type -> type != null && !type.equals(direct.targetFqn()))
            .distinct()
            .sorted()
            .toList();

        List<ServiceCalls.CallSite> expanded = new ArrayList<>();
        List<String> notes = new ArrayList<>(warnings);
        int scanned = 0;
        for (String callerType : callerTypes) {
            if (scanned >= maxTypes) {
                notes.add("向上展开的类型达到上限 " + maxTypes + "，还有 " + (callerTypes.size() - scanned)
                        + " 个调用方类型未展开；可提高 maxUpstreamTypes 或先用 methodName 收窄第一层");
                break;
            }
            scanned++;
            try {
                ServiceCalls.Report report = index.findServiceCallers(new ServiceCalls.Query(callerType, null,
                        false, false, false, 200));
                expanded.addAll(report.callSites());
            }
            catch (ToolFailure e) {
                // A caller type can be ambiguous by simple name; skip it rather than failing the whole
                // impact analysis, and say which one and why.
                notes.add("无法展开 " + callerType + ": " + e.getMessage());
            }
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("target", direct.targetFqn());
        payload.put("depth", 2);
        payload.put("direct", direct);
        payload.put("upstream", expanded);
        payload.put("upstreamCallSites", expanded.size());
        payload.put("expandedTypes", scanned);
        payload.put("callerTypes", callerTypes.size());

        String text = summary + "；再向上一层展开 " + scanned + " 个调用方类型，得到 " + expanded.size()
                + " 处间接调用（depth=2 为近似影响面）";
        if (!expanded.isEmpty()) {
            notes.add("depth=2 表示「调用了第一层调用方类型的方法」，无法限定到具体方法，可能包含与目标无关的调用");
        }
        return ToolOutcome.ok(payload, text, notes);
    }
}

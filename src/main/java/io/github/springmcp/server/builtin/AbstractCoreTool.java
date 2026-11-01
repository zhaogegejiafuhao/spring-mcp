package io.github.springmcp.server.builtin;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import io.github.springmcp.server.ProjectAlias;
import io.github.springmcp.server.ProjectCatalog;
import io.github.springmcp.spi.Args;
import io.github.springmcp.spi.ErrorCode;
import io.github.springmcp.spi.JsonSchemas;
import io.github.springmcp.spi.ToolFailure;
import io.github.springmcp.spi.ToolProvider;
import io.github.springmcp.spi.api.CoreAnalyzer;
import io.github.springmcp.spi.api.IndexHandle;
import io.github.springmcp.spi.api.IndexOptions;
import io.github.springmcp.spi.api.QueryScope;

/**
 * Shared plumbing for the built-in tools.
 *
 * <p>
 * The built-ins are ordinary plugins internally — they implement the same {@link ToolProvider} SPI a
 * third party would, and reach the index through the same {@link CoreAnalyzer} facade. That is
 * deliberate: if the SPI were too thin for the five tools shipped in the box, it would be too thin
 * for anyone else.
 * </p>
 */
public abstract class AbstractCoreTool implements ToolProvider {

    /** Above this many aliases the schema description points at {@code list-projects} instead. */
    private static final int ALIASES_INLINE_LIMIT = 20;

    protected final CoreAnalyzer analyzer;

    protected final ProjectCatalog catalog;

    /**
     * Options every call starts from. Shared by all built-ins so that one server process builds one
     * index rather than one per tool: {@link io.github.springmcp.core.IndexRegistry} keys its cache
     * on these values.
     */
    protected final IndexOptions baseOptions;

    protected final boolean includeTestsByDefault;

    /**
     * @param analyzer analysis facade
     * @param catalog configured projects, may be empty
     * @param baseOptions options every query starts from; {@code null} means defaults
     */
    protected AbstractCoreTool(CoreAnalyzer analyzer, ProjectCatalog catalog, IndexOptions baseOptions) {
        this.analyzer = analyzer;
        this.catalog = catalog == null ? ProjectCatalog.empty() : catalog;
        this.baseOptions = baseOptions == null ? IndexOptions.defaults() : baseOptions;
        this.includeTestsByDefault = this.baseOptions.includeTests();
    }

    /**
     * Works out which project a call is about.
     *
     * <p>
     * Two ways in: an alias from the configured catalog, or an explicit absolute path. Supplying both
     * is rejected rather than resolved by a rule, because every rule a caller might assume ("the
     * alias wins", "the path wins") is equally plausible, and half of them would assume wrong.
     * </p>
     *
     * @param args tool arguments
     * @return the project root to analyse
     * @throws ToolFailure when neither is available, both were given, or the alias is unknown
     */
    protected Path resolveRoot(Args args) throws ToolFailure {
        String alias = args.optionalString("project", null);
        if (alias != null) {
            if (args.has("projectRoot")) {
                throw ToolFailure.of(ErrorCode.INVALID_ARGUMENT,
                        "参数 'project' 与 'projectRoot' 不能同时使用",
                        "二者选一：传 'project' 用配置好的别名，或传 'projectRoot' 用绝对路径");
            }
            Optional<ProjectAlias> resolved = catalog.byAlias(alias);
            if (resolved.isEmpty()) {
                throw ToolFailure.of(ErrorCode.INVALID_ARGUMENT, "未知的工程别名: " + alias,
                        "可用别名: " + catalog.describeAvailable() + "。也可以改用 'projectRoot' 传绝对路径");
            }
            return resolved.get().root();
        }

        Path root = args.optionalExistingDirectory("projectRoot", catalog.defaultRoot());
        if (root == null) {
            throw ToolFailure.of(ErrorCode.INVALID_ARGUMENT, "缺少工程参数",
                    "请传 'projectRoot'（绝对路径），或在启动服务时用 --project=<dir> 设默认值");
        }
        return root;
    }

    /**
     * Opens the index for the project the caller asked about, narrowed to {@code scope} when given.
     *
     * <p>
     * Scoping happens after the index is open, never inside the build options: putting modules into
     * {@link IndexOptions} would change the cache key and turn every scope switch into a full
     * rebuild.
     * </p>
     *
     * @param args tool arguments
     * @return a snapshot handle, possibly a scoped view
     * @throws ToolFailure when no root is available, the path is invalid, or a scope was requested
     * for a project without module structure
     */
    protected IndexHandle openIndex(Args args) throws ToolFailure {
        Path root = resolveRoot(args);
        boolean includeTests = includeTestsByDefault || args.optionalBoolean("includeTests", false);
        IndexOptions options = baseOptions.withIncludeTests(includeTests);

        List<String> modules = args.optionalStringList("scope");
        if (modules.isEmpty()) {
            return ProjectRoots.requireSources(root, analyzer.open(root, options));
        }

        IndexHandle handle = ProjectRoots.requireSources(root, analyzer.open(root, options));
        if (handle.modules().isEmpty()) {
            throw ToolFailure.of(ErrorCode.INVALID_ARGUMENT,
                    "该工程未识别到多模块结构，scope 不可用: " + root,
                    "scope 只对多模块工程（Maven <modules> / Gradle include）有效；"
                            + "单模块工程直接查即可，不必限定作用域");
        }
        return handle.scoped(QueryScope.of(modules, List.of()));
    }

    /**
     * Adds the project selection properties to a tool schema.
     *
     * <p>
     * {@code project} only appears when at least one alias is configured, so a single-project setup
     * keeps a byte-identical schema and an existing client sees no change.
     * </p>
     *
     * <p>
     * The alias list goes into the description rather than a JSON Schema {@code enum} on purpose. With
     * an enum, an unknown alias is rejected by the SDK's input validator before the tool runs, and the
     * rejection carries no {@code structuredContent} — so the caller loses the {@code {ok:false,
     * error:{code,hint}}} envelope that every other failure uses, and cannot branch on an error code.
     * Keeping it a plain string means the model still sees the valid values, and a wrong one produces
     * our own {@code INVALID_ARGUMENT} with the list in the hint.
     * </p>
     *
     * @param schema builder to add to
     * @return the same builder
     */
    protected JsonSchemas.SchemaBuilder projectSchema(JsonSchemas.SchemaBuilder schema) {
        schema.string("projectRoot",
                "被分析工程的根目录绝对路径。省略时使用启动参数 --project 指定的默认工程；目录下必须有 Java 或 Kotlin 源码。",
                false, null);
        if (!catalog.isEmpty()) {
            List<String> names = catalog.names();
            // A long list would bloat every tool description; past a threshold, point at the tool that
            // enumerates them instead.
            String available = names.size() <= ALIASES_INLINE_LIMIT ? String.join(", ", names)
                    : "共 " + names.size() + " 个，用 list-projects 查询";
            schema.string("project",
                    "工程别名（可选值: " + available + "）。与 projectRoot 互斥；省略则使用默认工程 " + catalog.defaultName()
                            + "。",
                    false, null);
        }
        return schema;
    }

    /**
     * Adds the {@code scope} property.
     *
     * <p>
     * It is offered unconditionally rather than only for multi-module projects, because deciding
     * whether a project is multi-module means opening its index — the exact cost that must not be
     * paid while building tool descriptors at startup. A caller that passes {@code scope} for a
     * single-module project gets an {@code INVALID_ARGUMENT} that says so, which is the honest
     * alternative to silently ignoring it.
     * </p>
     *
     * @param schema builder to add to
     * @return the same builder
     */
    protected JsonSchemas.SchemaBuilder scopeSchema(JsonSchemas.SchemaBuilder schema) {
        schema.arrayOfStrings("scope",
                "只在多模块工程的指定模块内查找。值为工程相对路径（如 services/order）；"
                        + "写上级目录会包含其下所有子模块。省略则查全部模块。"
                        + "可用 list-modules 查看模块清单。",
                false);
        return schema;
    }

    /**
     * Builds a one-line "found N things" summary, which is what a caller reads first.
     * @param label what was found, e.g. {@code "endpoint"}
     * @param count number of items
     * @param extra optional extra sentence, may be {@code null}
     * @return summary text
     */
    protected static String summary(String label, int count, String extra) {
        StringBuilder sb = new StringBuilder("找到 ").append(count).append(" 个").append(label);
        if (extra != null && !extra.isBlank()) {
            sb.append("。").append(extra);
        }
        return sb.toString();
    }

    /**
     * Same as {@link #summary(String, int, String)}, but names the scope.
     *
     * <p>
     * A scoped "found 0" and an unscoped "found 0" mean different things, and a caller reading only
     * the headline must not have to infer which one it got.
     * </p>
     *
     * @param label what was found
     * @param count number of items
     * @param extra optional extra sentence
     * @param index handle the answer came from
     * @return summary text, scope appended when one was applied
     */
    protected static String summary(String label, int count, String extra, IndexHandle index) {
        String text = summary(label, count, extra);
        QueryScope scope = index.scope();
        if (!scope.isUnrestricted()) {
            text = text + "（作用域：" + scope.describe() + "）";
        }
        return text;
    }

    /**
     * @param index handle the answer came from
     * @return {@code ""} when unscoped, otherwise a parenthesised note naming the scope
     */
    protected static String scopeSuffix(IndexHandle index) {
        QueryScope scope = index.scope();
        return scope.isUnrestricted() ? "" : "（作用域：" + scope.describe() + "）";
    }

    /**
     * Appends index-level warnings (unparsable files, empty project, applied scope) to the tool's
     * own warnings.
     *
     * <p>
     * Surfacing them matters: an analysis that silently skipped three broken files looks complete and
     * would let a caller conclude "nothing calls this service" when the truth is "we could not read
     * it". The scope note follows the same logic — a filtered answer must never look like a complete
     * one.
     * </p>
     * @param toolWarnings warnings produced by the analysis
     * @param index handle the answer came from
     * @return merged warnings
     */
    protected static List<String> mergeWarnings(List<String> toolWarnings, IndexHandle index) {
        List<String> merged = new ArrayList<>(toolWarnings);
        merged.addAll(index.metadata().warnings());
        QueryScope scope = index.scope();
        if (!scope.isUnrestricted()) {
            merged.add("结果已限定在作用域内（" + scope.describe() + "）；其他模块的同类结果不会出现");
        }
        return merged;
    }
}

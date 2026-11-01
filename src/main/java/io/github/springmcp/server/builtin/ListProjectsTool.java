package io.github.springmcp.server.builtin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.springmcp.server.ProjectAlias;
import io.github.springmcp.server.ProjectCatalog;
import io.github.springmcp.spi.JsonSchemas;
import io.github.springmcp.spi.ToolDescriptor;
import io.github.springmcp.spi.ToolInvocation;
import io.github.springmcp.spi.ToolOutcome;
import io.github.springmcp.spi.ToolProvider;

/**
 * {@code list-projects}: tells the caller which projects it can ask about.
 *
 * <p>
 * Without this, an agent has no way to discover that {@code petclinic} is a valid {@code project}
 * alias — it would have to ask the user for an absolute path on every conversation. The tool is
 * therefore about capability discovery, not analysis.
 * </p>
 *
 * <p>
 * Deliberately does <strong>not</strong> open any index. Opening one on a large repository costs
 * seconds, and listing names should never do that; whether a project is indexed stays a question for
 * the tool that actually needs the index.
 * </p>
 */
public final class ListProjectsTool implements ToolProvider {

    public static final String NAME = "list-projects";

    private final ProjectCatalog catalog;

    /**
     * @param catalog configured projects
     */
    public ListProjectsTool(ProjectCatalog catalog) {
        this.catalog = catalog == null ? ProjectCatalog.empty() : catalog;
    }

    @Override
    public ToolDescriptor descriptor() {
        return ToolDescriptor.builder(NAME)
            .title("列出已配置的工程")
            .description("""
                    列出服务启动时通过 --project 配置的工程别名、绝对路径，以及哪个是默认工程。

                    返回的 alias 可以直接传给其他工具的 `project` 参数，省去每次拼绝对路径。\
                    本工具只做能力发现，不打开索引、不分析源码，因此非常快。

                    别名列表为空时，说明服务是以单工程或未配置方式启动的，\
                    此时请改用其他工具的 `projectRoot` 参数传绝对路径。

                    适用场景：确认能查哪些工程、拿到别名。
                    不适用：查源码内容（用 list-controllers 等）。
                    """)
            .inputSchema(JsonSchemas.object().title(NAME))
            .tags(List.of("meta", "discovery"))
            .build();
    }

    @Override
    public ToolOutcome invoke(ToolInvocation invocation) {
        List<Map<String, Object>> projects = new ArrayList<>();
        String defaultName = catalog.defaultName();

        for (ProjectAlias project : catalog.all()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("alias", project.name());
            entry.put("path", project.root().toString());
            entry.put("isDefault", project.name().equals(defaultName));
            entry.put("exists", java.nio.file.Files.isDirectory(project.root()));
            projects.add(entry);
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("projects", projects);
        payload.put("count", projects.size());
        payload.put("default", defaultName);

        String summary = projects.isEmpty()
                ? "未配置任何工程别名。请用 --project=<目录> 启动服务，或在其他工具里直接传 projectRoot 绝对路径。"
                : "配置了 " + projects.size() + " 个工程，默认是 '" + defaultName + "'。"
                        + "把 alias 传给其他工具的 project 参数即可。";

        return ToolOutcome.ok(payload, summary);
    }
}

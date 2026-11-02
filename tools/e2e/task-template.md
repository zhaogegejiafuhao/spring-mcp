# 任务：给一个没见过的仓库做体检

你是一个刚接到任务的 AI，之前从没见过下面这个仓库，也没有任何关于它的背景知识。

你手上只有一个工具可调用：

```
bash /d/springMCP/.realworld/mcp-tool.sh <tool> projectRoot=<仓库路径> [参数=值 ...]
```

这是 MCP 工具的本地直通管道，输出与 MCP 工具返回的 JSON **完全一致**。可用工具与参数：

| 工具 | 参数 | 作用 |
|---|---|---|
| `list-projects` | — | 列出已登记的工程 |
| `list-modules` | `projectRoot` | 列出模块与模块间依赖 |
| `list-controllers` | `projectRoot`、`scope`、`basePathFilter`、`httpMethod`、`includeTests` | 列出 HTTP 端点 |
| `list-gateway-routes` | `projectRoot` | 列出网关路由 |
| `list-feign-clients` | `projectRoot`、`name` | 列出 Feign 出站客户端 |
| `list-mappers` | `projectRoot`、`kinds`、`nameFilter` | 列出 MyBatis / JPA 持久层接口 |
| `find-service-callers` | `projectRoot`、`serviceClass`、`methodName` | 谁调用了某个 Service |
| `find-implementations` | `projectRoot`、`type` | 某类型的所有实现 |
| `search-symbol` | `projectRoot`、`name` | 按名字搜类型 |
| `search-by-annotation` | `projectRoot`、`annotation`、`elementKinds`、`strict` | 按注解搜 |
| `get-file-dependencies` | `projectRoot`、`file` | 某文件的依赖 |

## 硬性规则

1. **只准调用上面这个命令。** 不许 `cat`、`grep`、`find`、`grep -r` 源码，不许读任何 `.java` / `.kt` / `.xml` / `.yml` 文件。这个任务要测的就是「只靠这套工具能不能答对」。
2. **不要替工具补全它没给出的信息。** 工具说路径无法确定就是无法确定，不要把它猜成一个看起来合理的 URL。
3. **数字要精确**，不要写「大约」「几十个」。如果拿不准，就说拿不准。
4. 答案里出现的每个数字、每条路径，必须来自工具的某次输出，不许凭印象。

## 交付

把答案写成 JSON，保存到 `<输出文件>`，结构如下：

```json
{
  "repo": "<仓库名>",
  "answers": {
    "q1": "...",
    "q2": "..."
  },
  "calls": [
    {"tool": "list-controllers", "args": {"root": "..."}, "note": "这次调用想确认什么"}
  ]
}
```

- `answers` 里每道题一个字符串。需要列清单时用 JSON 数组字符串，或换行分隔的文本。
- `calls` 记录你实际调用过的每一次（按顺序），用于复核。

最后在回复正文里给出同样的答案（简短即可）。

## 题目

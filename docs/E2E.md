> 本文是过程记录。结论汇总与四层证据见 [`VALIDATION.md`](../VALIDATION.md)。

# 冷启动实测：三个「没见过这个仓库」的 AI，只用这套工具体检

要回答的问题：**一个全新的 AI，拿到这个 MCP 和任意一个 Spring 仓库，能不能查对？**

不是「工具有没有 bug」，是「端到端能不能出对的结论」。做法是：找三个没人告诉过背景的仓库，
交给三个冷启动会话，只给工具、不给源码，看它们能不能答对；答错的地方，再判断是工具的责任还是它们自己的。

## 怎么测的

| 环节 | 做法 |
|---|---|
| 被测对象 | 3 个新起的子会话，彼此不知道对方存在，也没读过这个项目的代码 |
| 提供的东西 | 工具清单（11 个工具的参数与用途）+ 一条命令 `tools/corpus/mcp-tool.sh <tool> projectRoot=...` |
| 管道 | `McpTool` 直接构造与 MCP 服务相同的 registry / plugin / `ToolInvocation`，打印 `ToolOutcome` 的结构化载荷；参数名与 `tools/list` 一致 |
| 禁止的事 | 不许 cat / grep / find 任何源码或配置；答案里每个数字必须来自工具输出 |
| 仓库 | 三个都没在工具里出现过：`Naccl/NBlog`（单模块博客）、`dromara/RuoYi-Vue-Plus`（39 模块后台）、`itwanger/paicoding`（社区系统） |
| 判分 | 16 条断言（12 题 + paicoding 的工具层复测 4 题），答案事先从工具输出里量好，脚本逐条比对 |

每份仓库 4 道题，其中至少 2 道是**陷阱**：工具明明说了「不知道」，看它会不会补一个看起来合理的答案。

## 结果：16/16

| 仓库 | 端点数 | 模块数 | 答对 | 调用次数 |
|---|---|---|---|---|
| `Naccl/NBlog` | 79 | 单模块 | 4/4 | 10 |
| `dromara/RuoYi-Vue-Plus` | 286 | 39 | 4/4 | 7 |
| `itwanger/paicoding`（dev CLI） | 343 | 单模块 | 4/4 | 5 |
| `itwanger/paicoding`（工具层复测） | 343 | 单模块 | 4/4 | 7 |

22 次调用里，3 次是测试脚本自己的 bug（`list-projects` 映射写错）、1 次是参数名笔误
（`list_modules` 写成下划线），其余有效。没有一次编造数字——三份答案里的
`controllerCount` / `methodCount` / 端点数，与工具输出逐字一致。

复测那一轮换了管道（DevCli → 真正的工具层），同一个仓库、同一批问题，四个答案与首轮**逐字一致**。

## 三个陷阱，都没踩

**陷阱一：占位符路径会不会被「顺手补全」**

`NBlog` 有一条 `POST /tg/${tg.bot.token}`。一个着急的 AI 很容易把它写成 `/tg/webhook` 之类。

> 回答：`pathResolved=false，unresolvedReason=PLACEHOLDER`。原因：映射路径里写的是 Spring 占位符
> `${tg.bot.token}`，值在运行时由外部配置注入才能确定……静态分析只能拿到占位符原文，拿不到 token
> 字面量，所以工具按原文返回并标记为未解析，而不是猜一个 URL。

**陷阱二：`${x:默认值}` 里的默认值算不算答案**

`RuoYi-Vue-Plus` 的 `SseController` 写的是 `${message.path:/resource/message}`，默认值看上去就是个现成路径。

> 回答：原样保留，不解析、不改写。两条路径都保留占位符片段，只把其后的字面量 `/close` 拼在后面，
> 仍标记为未解析。

**陷阱三：正则约束会不会被「清理」成正常路径**

`paicoding` 有 `/article/detail/{articleId:\d+}`——正是之前被 `PathJoiner` 改坏成 `/d+` 的那条。

> 回答：`/article/detail/{articleId:\d+}`、`/article/detail/{articleId:\d+}/{urlSlug}`，
> 反斜杠、花括号与正则约束均按工具输出照抄，未做任何清理。

另外两题的答案同样是「没有」而不是编一个：`paicoding` 的网关路由 0 条、Feign 0 个，回答明确写了
「不是『没查到』，工具明确返回了空数组和计数 0」。

## 这一轮抓到的问题

### ① 工具缺陷：Maven 过滤符让整份 YAML 作废（已修）

`RuoYi-Vue-Plus` 那题只问 SseController，会话顺手调了一次 `list-gateway-routes`，回来说
「5 份网关配置解析失败」。查下去是真的：

`@logging.level@` 是 Maven 的资源过滤符，打包时被替换掉。YAML 规范里 plain scalar 不能以 `@`
开头，所以这份文件在解析器眼里整份非法——**里面的路由一条都读不到**，只剩一条类名警告。

修法：读文件时先把 `@name@` 改写成 `${name}`，语义不变（都是「只有构建或环境才知道的值」），
解析器就接受了。

| | 修复前 | 修复后 |
|---|---|---|
| 110 仓库里的 YAML 解析失败 | 18 条 | **0** |
| 网关告警总数 | 35 条 | 17 条 |
| 9 个基线仓库的 golden | — | **全部不变** |

golden 不变是意料之中的：基线仓库里没有一份配置用了 `@x@`，所以这是一次纯健壮性修复——
它不会让已有答案变好，只会让「本该有答案」的工程不再整份作废。110 个仓库里也没有一个
把网关路由写在这种文件里，所以这次**没有一条路由被救回来**；修的是这一类失败。

写这个修复时我自己也踩了一次 `Matcher.replaceAll(Function)` 的坑：返回值仍按替换模板解析，
`${logging.level}` 被当成命名分组引用而抛异常。新加的两个测试抓出来了，已改用 `Matcher.quoteReplacement`。

### ② 工具缺陷：指错目录会得到「0 个端点」（已修）

冷启动时最容易犯的错是指错 `projectRoot`——传了父目录、传了前端目录、传了多模块工程的聚合模块。
修复前，这几种都会得到：

```json
{"endpoints": [], "controllerCount": 0, "methodCount": 0, "warnings": []}
```

和「这个服务确实什么都不暴露」从外面看是同一句话，调用方没有办法区分，而它会相信后者。

现在这些工具会直接报错，并给出下一步：

```json
{"ok": false, "code": "INVALID_ARGUMENT",
 "message": "目录下没有 Java/Kotlin 源码: D:\\repo\\blog-cms",
 "hint": "确认 projectRoot 是工程根目录……可先用 list-modules 看模块清单，再指向具体模块。"}
```

检查放在工具入口而不是索引层：空工程本身是合法的快照，它要能缓存、不能反复重建
（`IndexCacheStoreTest.emptyProjectStillCaches` 守着这条）。只有对外的工具出口需要拒绝它。

### ③ 测试管道缺陷：Git Bash 改写了我传的参数

复测那一轮，会话报告「`basePathFilter=/article/detail` 只回了 1 条未解析端点，过滤器好像没生效」。
查下去：MSYS 把 `basePathFilter=/article` 当成 Unix 路径，改写成 `C:/Program Files/Git/article`
再交给 JVM，于是前缀匹配一个都不中。

**这不是工具的问题**，MCP 的参数走 JSON、不经过 shell。但它暴露了两件事：

- 我的管道一开始用 DevCli，绕过了工具层的参数处理（`projectRoot` 解析、`scope`、新增的守卫），
  后来换成工具层调用器直接进 `ToolProvider.invoke`（现在随仓库发布，就是 `tools/corpus/McpTool.java`），
  才是真的「和 MCP 一样」；
- 换管道后同一批问题重测，答案逐字不变——说明之前那一轮的结论不是靠 DevCli 的特性得到的。

顺带修了一处文档与实现不符：README 里写「`RouteLocator` 不解析」，实际早就解析了
（110 仓库里有 18 条路由来自代码 DSL）。

## 这批结论的边界

- **传输层没测全**：`McpTool` 走的是同一套 registry / plugin / ToolInvocation，但没有过
  stdio 的 JSON-RPC 帧（这部分有 `StdioProtocolTest` 兜底，只是没做端到端）。
- **只测了 3 个仓库**：都是 Java、都能顺利索引。被索引拒绝的仓库（无源码、路径不存在）没测。
- **不许读源码是人为约束**：真实使用里 AI 会同时 grep 源码，那时它的答案可能比工具更全——
  但也就无法区分「工具答对了」还是「AI 自己看出来的」。这次测的是工具单独够不够。
- **判分脚本按关键词判**：几道题是「说没说出来」，不是严格语义匹配。答案原文都留在
  `e2e/answer-*.json`，可以人工复核。

## 复现

```bash
# 单次查询（参数名与 MCP 的 tools/list 一致）
bash tools/corpus/mcp-tool.sh list-controllers projectRoot=<目录> basePathFilter=/api

# 四份答案 + 工具层复测的判分，脚本和答案都在仓库里
python tools/e2e/score_e2e.py
```

| 文件 | 内容 |
|---|---|
| `tools/corpus/McpTool.java` | 工具层调用器：构造真实的 plugin，调 `ToolProvider.invoke`，打印 `ToolOutcome` |
| `tools/corpus/mcp-tool.sh` | 薄封装，处理 MSYS 的路径改写，可重复跑上面那条命令 |
| `tools/e2e/task-template.md` | 发给会话的任务书（规则、工具表、输出格式） |
| `tools/e2e/answer-*.json` | 四份原始答案，含每次调用的用途 |
| `tools/e2e/score_e2e.py` | 判分脚本，16 条断言，`python tools/e2e/score_e2e.py` 直接跑 |

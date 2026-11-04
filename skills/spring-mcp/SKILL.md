---
name: spring-mcp
description: Turn any Spring Boot or Spring Cloud repository into answers about its HTTP surface, service call graph, persistence layer, Feign edges and gateway routes, using the spring-mcp MCP server for static analysis. Use it when asked which method serves a URL, what a controller exposes, who calls a service method, which mapper handles a table, what the gateway routes to, or for an endpoint inventory of a Java or Kotlin Spring project. Triggers include Spring 端点, 接口在哪, 谁调用了这个 Service, controller 清单, mapper, Feign, 网关路由.
---

# spring-mcp

A driver for the **spring-mcp** MCP server: static analysis of a Spring codebase.
It reads source and answers questions. It never compiles, starts, or classloads the target project.

This skill contains **no analyzer**. It contains the judgement an agent needs to use one:
which tool answers which question, what the result fields actually mean, and where a wrong
answer is more likely than no answer.

---

## Step 0 — is the analyzer there?

Call `list-projects` first. It costs milliseconds (no index is opened) and its return proves the
server is alive.

- **Tools visible** → continue. If `list-projects` returns `count: 0`, no `--project` was configured;
  every subsequent call must pass `projectRoot` as an absolute path.
- **Tools not visible** → the server is not installed in this client. Do **not** substitute
  `grep` and present it as the same thing. Say one sentence, then give the install path: full steps
  are in `INSTALL.md` at the root of this skill, and the short version is below.

```
spring-mcp needs JDK 21+ and an MCP client that can launch a stdio server.

1. Download spring-mcp.zip (or spring-mcp-all.jar) from
   https://github.com/zhaogegejiafuhao/spring-mcp/releases
2. Unzip somewhere stable, e.g. ~/tools/spring-mcp/
3. Add to the client's MCP config and restart it:

   "mcpServers": {
     "spring-mcp": {
       "command": "/abs/path/spring-mcp/spring-mcp.sh",
       "args": ["--project=/abs/path/to/your-spring-app"]
     }
   }

   Windows: "command": "C:/abs/path/spring-mcp/spring-mcp.cmd"
4. `bash scripts/preflight.sh` checks java, the jar, and prints the exact JSON for this machine.
```

Then stop. The question cannot be answered without the server, and a `grep`-based answer presented
as equivalent is the failure mode this skill exists to prevent.

`scripts/preflight.sh` is read-only: it looks for `java`, checks the version is 21+, looks for the
jar, and prints a config snippet. It downloads nothing and writes nothing.

---

## Non-negotiables

1. **Every number and every path in your answer comes from a tool result.** No estimates, no
   "roughly", no counting from a file listing. If you did not call a tool, you do not know.
2. **A field that says "unresolved" is the answer.** Never complete a placeholder into a URL that
   looks plausible. `${tg.bot.token}` is a complete answer; `/tg/webhook` is a fabrication.
3. **Absence only counts if the path was right.** "0 endpoints" is a claim about the project only
   after `projectRoot` was confirmed to be the source root — not a parent directory, not a frontend
   directory, not a Maven aggregator module.

---

## Choosing a tool

| The question | Tool |
|---|---|
| Which URL does this handler serve? What does this app expose? | `list-controllers` |
| Who calls `OrderService.create`? What breaks if I change it? | `find-service-callers` |
| What implements `PaymentGateway`? Is it a Spring bean? | `find-implementations` |
| Which mapper / XML statement handles this table? | `list-mappers` |
| Where is class `OrderController` declared? | `search-symbol` |
| What is annotated with `@Transactional`? | `search-by-annotation` |
| What does this file depend on, and who depends on it? | `get-file-dependencies` |
| What can an external client actually call? | `list-gateway-routes` |
| What does this module call out to? | `list-feign-clients` |
| Which projects / modules exist here? | `list-projects` / `list-modules` |

Full parameters for all eleven: `reference/tools.md`.

### Call order on an unfamiliar repo

1. `list-projects` — aliases, default project, does the path exist.
2. `list-modules` — module list. Multi-module repos: this is where `scope` values come from.
3. `search-symbol` — the cheapest way to locate a file before reading anything.
4. The domain tool (`list-controllers`, `list-mappers`, …) — may open the index, so spend it once.

The index is built once per project per process and cached on disk, so the first domain call is
the slow one — measured on 98 unfamiliar repositories: median 0.39 s, p90 2.3 s, worst 14 s on a
23k-file project.

---

## Reading the result

These five fields decide whether the answer is true. Detail: `reference/reading.md`.

| Field | What it means | What you do |
|---|---|---|
| `pathResolved: false` | The path contains something only the runtime knows | Quote the path as-is, name `unresolvedReason`, say the URL is not statically known |
| `unresolvedReason` | `PLACEHOLDER` / `LITERAL_CONSTANT` / `CONCATENATION` | Report which one. They mean different things to the user |
| `conditions` | `@Profile`, `@ConditionalOn*` on the handler | Copy verbatim. "Exists in prod" is not something this tool knows |
| `rule` + `confidence` on call sites | How the receiver type was inferred (`FIELD`, `CONSTRUCTOR_PARAM`, …) | `NAME_CONVENTION` is a guess and is **off by default** — never enable it to make a count look better |
| `warnings[]` | Truncation, inherited handlers, inferred controllers, unparsed files | Surface them. A warning you dropped becomes a wrong answer |

`scope` filters **results**, not the index. A scoped result of 0 is not "the project has none" —
say "in scope `billing`, 0".

---

## Anti-patterns

These are the six ways this tool gets used wrong. Each one produces a confident wrong answer.

1. **`grep '@GetMapping'` instead of `list-controllers`.** Inherited mappings vanish: a controller
   extending `AbstractCrudController<Owner, Long>` exposes four endpoints declared in the parent
   file. Text search finds none of them. The tool reports them with `handlerDeclaredIn`.
2. **Completing a placeholder.** `${api.prefix}/orders` does not become `/api/orders`. Not even
   when `${x:默认值}` supplies a default — that default applies only when the property is unset.
3. **Reporting 0 without checking the root.** The tools error out on a directory with no Java/Kotlin
   sources, but a module directory that does have sources can legitimately return 0 while the repo
   has hundreds. Run `list-modules` before believing a zero.
4. **Passing `scope` to a single-module project.** It returns `INVALID_ARGUMENT` by design rather
   than silently returning everything.
5. **Reading Feign `lb://order-service` as a URL.** It resolves to a module in *this* repository, or
   to `null` when the target lives elsewhere. Never invent the other service's address.
6. **Promising runtime behaviour.** Actuator endpoints, dynamically registered mappings, AOP proxy
   targets, `RestTemplate` URLs and SpEL are out of scope by design. Say "not visible to static
   analysis", not "does not exist".

---

## Answer format

- Lead with the answer, then the evidence: `file:line` for every endpoint or symbol.
- Quote counts exactly as returned. If `truncated: true`, say the list is partial and how to narrow it.
- Unresolved items get their own line, never folded into a total.
- When the answer is "the tool cannot know this", say that in one sentence and name what would be
  needed (a running instance, a config value, another repository).

---

## Errors → the next move

| `code` | Meaning | Do this |
|---|---|---|
| `INVALID_ARGUMENT` | Bad parameter, or no sources under `projectRoot` | Re-read the hint; run `list-modules` |
| `PATH_NOT_FOUND` / `PATH_NOT_DIRECTORY` | Path is wrong | Ask for the absolute path, do not guess |
| `CLASS_NOT_FOUND` | Name does not match a declaration | Try `search-symbol` with a fragment |
| `AMBIGUOUS_CLASS` | Two types share a simple name | Re-run with the fully qualified name |
| `INDEX_TOO_LARGE` | Over `--max-indexed-files` (default 20000) | Narrow `projectRoot` to a module |
| `TIMEOUT` | Index build exceeded the budget | Narrow the root, or raise the timeout |

Full envelope (`ok` / `data` / `warnings` / `error`): `reference/reading.md`.

---

## 中文速查

- 先调 `list-projects` 确认服务在；看不到工具就直说没装，别用 grep 冒充。
- 十一个工具怎么选、参数是什么：`reference/tools.md`。
- `pathResolved=false` 就是答案，不许补全；`warnings` 不许吞。
- 说「0 个」之前先确认 `projectRoot` 指对了。
- 输出必须带 `文件:行号`，数字必须来自工具原文。

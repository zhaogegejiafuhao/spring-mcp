---
name: spring-mcp-audit
display_name: Spring 仓库体检
display_name_en: Spring Surface Audit
description: Produce a full inventory report of a Spring Boot or Spring Cloud repository as one markdown deliverable, covering every HTTP endpoint, the persistence layer, Feign edges, gateway routes, unresolved paths and cross-checks between them. Use it when asked for a repository audit, an endpoint inventory, an API surface report, an onboarding document for an unfamiliar Spring codebase, or a pre-migration review of a Java or Kotlin Spring project. Triggers include 仓库体检, 接口清单, 端点盘点, API 盘点, 微服务调用梳理, Spring 项目调研报告.
description_zh: 给一个 Spring Boot / Spring Cloud 仓库出体检报告：全部端点（含文件与行号）、持久层、Feign 出站边、网关路由、未能静态确定的路径，以及网关前缀与控制器、Feign 与目标模块之间的五项交叉校验。
description_en: One markdown report per repository — every endpoint with file and line, the persistence layer, Feign edges, gateway routes, unresolved paths, and five cross-checks a grep cannot do.
examples_zh:
  - "给我盘点一下这个仓库都暴露了哪些接口"
  - "网关改写之后老是 404，帮我查一下客户端实际该调哪个路径"
  - "做个微服务调用关系梳理，出一份调研报告"
examples_en:
  - "Give me a full endpoint inventory of this repository"
  - "We keep getting 404s after the gateway rewrite — what path should clients actually call?"
  - "Map which service calls which, and write it up as a report"
version: 0.1.1
author: zhaogegejiafuhao
tags: [spring, spring-boot, spring-cloud, codebase-audit, api-inventory, mcp]
---

# spring-mcp audit

One deliverable: a report that says what this Spring repository exposes, what it depends on, and
what the analyzer could not determine.

This skill assumes the **spring-mcp** MCP server is installed. If the tools `list-controllers`,
`list-mappers` and `list-projects` are not visible, hand the user `INSTALL.md` (in this skill) and
stop — an audit written from `grep` is worse than no audit, because every number in it will be
wrong in a way the reader cannot see.

Read `reference/reading.md` before interpreting any field. The rules below only make sense on top
of it.

---

## The one rule

**Every number in the report comes from a tool call, and the appendix lists those calls.**

A reader must be able to re-run the audit and get the same numbers. If a section could not be
produced, it says so and says what was missing — it is never dropped, and never filled with an
estimate.

---

## Workflow

Run the steps in this order. Each one is cheap relative to the next, and later steps reuse the
index the earlier ones built.

### 1. Scope

```
list-projects                                → aliases, default project, path exists?
list-modules  projectRoot=<root>             → module list + inter-module dependencies
```

Record: repository path, module count, module names, dependency edges. Fewer than two modules
means there is no module structure — say "single module" and never use `scope`.

### 2. HTTP surface

```
list-controllers projectRoot=<root>
list-controllers projectRoot=<root> scope=[<module>]   (per module, when multi-module)
```

Never pass `limit` here. Paging is for interactive questions on huge repositories; an audit that
claims "every endpoint" must have every endpoint, and a truncated list with a warning is still a
truncated report.

Record per endpoint: verb, path, `controllerFqn`, `handlerMethod`, `file`, `line`,
`pathResolved`, `conditions`, `handlerDeclaredIn`.

Group by module, then by controller. Sort by path so a reader can scan it.

### 3. Outbound edges

```
list-feign-clients projectRoot=<root>
list-gateway-routes projectRoot=<root>
```

Feign: client name, target module, `pathPrefix`, endpoint count. Gateway: route id, `pathPrefixes`,
`stripPrefix`, `targetModule`, and whether `targetModule` is null (route leaves this repository).

### 4. Persistence

```
list-mappers projectRoot=<root>
```

Per mapper: kind (`ANNOTATION` / `XML` / `JPA`), simple name, entity type, statement count.
Call out `orphans` — XML with no matching interface — separately; they are usually a namespace typo
or a mapper that lives in another repository.

### 5. Cross-checks

This is the part a reader cannot do with `grep`, and the reason the report has value. Run all five
and report the outcome even when nothing is wrong.

| Check | How | What a hit means |
|---|---|---|
| Gateway → service | For each route: `pathPrefixes` minus `stripPrefix` segments = the prefix the service should see. Does a controller exist under it? | A route pointing at nothing, or a global prefix applied in config |
| Feign → service | For each Feign endpoint, does `targetModule` contain a controller with that path? | A stale client, or a contract that only exists at runtime |
| Conditional endpoints | Count endpoints with non-empty `conditions` | These may be absent in a given environment |
| Controller without endpoints | Any `controllerFqn` in `list-controllers` warnings, or a `search-by-annotation @Controller` hit with no endpoint | RSocket/GraphQL controllers, or a non-Spring web framework |
| Unresolved paths | All `pathResolved: false` | The URL depends on configuration |

Do not silently drop a check that returns zero findings. "0 orphan XML" is information.

### 6. Write the report

Fill `reference/report-template.md`. Language: the language the user wrote in. Keep tables; prose
only where a decision needs explaining.

---

## Report contract

Every report has these sections, in this order:

1. **Scope** — path, modules, module dependency edges, how the audit was run.
2. **Summary** — endpoint / controller / mapper / Feign / route counts, and how many endpoints are
   conditional or unresolved. Counts only, no adjectives.
3. **HTTP surface** — the endpoint table, grouped by module.
4. **Outbound** — Feign clients and gateway routes.
5. **Persistence** — mappers, statements, orphan XML.
6. **Cross-checks** — the five checks above, each with its result.
7. **Not determined** — every `pathResolved: false` entry with file and line; the boundary list
   from `reference/reading.md` §8, trimmed to what applies here.
8. **Appendix: calls** — the exact tool calls, in order, with their arguments.

Rules for the numbers:

- Quote `controllerCount`, `methodCount`, `counts` as returned. Do not recompute from the table by
  hand and do not "clean up" a discrepancy — report it.
- `stats` carries the totals that are easy to get wrong by counting: `byMethod`, `byController`,
  `conditional`, `unresolved`, `inherited`. Use them; a model counting rows will miss.
- An endpoint whose `pathResolved` is false still counts. It is a real mapping.
- `truncated: true` or a hit `limit` means the table is partial: say so in the section header.
- Inherited handlers: `file`/`line` point at the declaration. Say so once, in the HTTP section, so
  a reader does not conclude the subclass is empty.

---

## Failure handling

| Situation | What the report says |
|---|---|
| A tool errors (`INVALID_ARGUMENT`, `PATH_NOT_FOUND`) | Stop. Fix the path first. A report built on a wrong root is worse than none |
| `INDEX_TOO_LARGE` | Narrow `projectRoot` to one module and audit per module; state that the audit is partial |
| A module cannot be indexed | Report the other modules, list the failed one under "Not determined" with the error code |
| The repository is not Spring | Say so after step 2: "N controllers, 0 endpoints — this looks like a non-Spring framework (Solon, JAX-RS, Quarkus), which is out of scope" |
| No Feign / no gateway | Say "none declared in source" — not "no outbound calls". `RestTemplate` / `WebClient` are invisible to this analysis |

---

## 中文速查

- 顺序：`list-projects` → `list-modules` → `list-controllers` → `list-feign-clients` +
  `list-gateway-routes` → `list-mappers` → 五项交叉校验 → 出报告。
- 报告八节固定，最后一节列出发起过的每一条工具调用，别人要能复现。
- 交叉校验（网关前缀 ↔ 控制器、Feign ↔ 目标模块、条件端点、有控制器无端点、未解析路径）
  是这份报告区别于 grep 的地方，零发现也要写。
- 数字来自工具原文，不许手算；`pathResolved=false` 的端点仍然要计入。

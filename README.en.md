# spring-mcp

Static analysis of a Spring Boot / Spring Cloud codebase, exposed as an MCP server. Reads Java and Kotlin.

**Static only.** It does not start the Spring context, does not compile the target project, and does not
put its classes on a classpath. Point it at a repository root and ask: *which method serves this URL*,
*who calls this service*, *which mapper owns this table*. It reads source and answers.

```
MCP client  ──stdio (JSON-RPC)──▶  spring-mcp  ──JavaParser / Kotlin lexer──▶  your Spring sources
```

<sub>中文文档：[README.md](README.md)</sub>

---

## What it sees, and what it does not

Read this table before deciding whether it fits. Every row is a deliberate trade-off, not a TODO.

| Sees | Does not see |
|---|---|
| Annotation endpoints: `@GetMapping` and friends on `@RestController` / `@Controller` | Actuator endpoints — registered by a starter, no mapping in source |
| `RouterFunction` / `coRouter` routes with literal paths, including same-file `nest(...)` prefixes | Cross-class route assembly (a `nest` applied by another class): warned about, not glued together |
| Class prefix × method path, with compile-time constant folding (`Const.BASE + "/x"`, `const val`) | Mappings registered at runtime via `RequestMappingHandlerMapping` |
| MyBatis interfaces and XML statements, JPA repositories, Feign clients, gateway routes | `${...}` placeholders and `@Value` injection — the value lives in config, so the raw text is returned and marked unresolved |
| Conditional assembly (`@Profile`, `@Conditional*`) **listed verbatim**, never evaluated | Non-Spring web frameworks (Solon, JAX-RS, Quarkus): reported as "controllers, no endpoints" |

One line: **what annotations declare, it reads. What configuration or the runtime decides, it says it
does not know.**

## Quick start

Needs **JDK 21+**. The repository ships a Maven wrapper; no local Maven required.

```bash
./mvnw package          # Windows: mvnw.cmd package
# → target/spring-mcp-all.jar   (runnable fat jar)
# → target/spring-mcp.jar       (project classes only)
./mvnw verify           # 320 tests
```

Wire it into an MCP client — the launcher script finds `java` and the jar for you:

```json
{
  "mcpServers": {
    "spring-mcp": {
      "command": "/path/to/spring-mcp/spring-mcp.sh",
      "args": ["--project=/path/to/your-spring-app"]
    }
  }
}
```

Windows: `"command": "C:/path/to/spring-mcp/spring-mcp.cmd"`.

`--project` sets a default root, so tool calls can omit `projectRoot`. Pass `projectRoot` per call to
switch projects inside one session.

| Flag | Meaning |
|---|---|
| `--project=<dir>` | Project root; repeatable, the first one is the default |
| `--project=<alias>:<dir>` | Same, and exposes the alias to the `project` parameter |
| `--dev <cmd>` | Bypass MCP and run a one-shot query (see below) |
| `--include-tests` | Also index `src/test/java` |
| `--index-cache=on\|off` | Reuse a previously built index across processes (default `on`) |
| `--max-indexed-files=<n>` | Fail with `INDEX_TOO_LARGE` above this (default 20000) |
| `--plugins=<dir>` | Plugin directory |
| `--allow-external-commands` | Let config-declared external process tools run (default off) |

## The eleven tools

| Tool | Answers |
|---|---|
| `list-controllers` | Every HTTP endpoint: path, verb, handler, file and line |
| `find-service-callers` | Who calls this service method, with the inference rule and confidence |
| `find-implementations` | What implements this interface, direct or through a base class |
| `list-mappers` | MyBatis interface + XML statements, JPA repositories, merged per mapper |
| `search-symbol` | Where a class, interface, enum or record is declared |
| `search-by-annotation` | What carries this annotation |
| `get-file-dependencies` | Both directions of coupling for one file |
| `list-gateway-routes` | Gateway routes from config and from `RouteLocator` beans |
| `list-feign-clients` | Outbound `@FeignClient` contracts and their endpoints |
| `list-projects` | Configured project aliases (no index opened — milliseconds) |
| `list-modules` | Modules and inter-module dependencies |

Every analysis tool also accepts `scope` (limit to modules) and `includeTests`.

Results carry `warnings[]`, and paths that cannot be resolved carry `pathResolved=false` plus an
`unresolvedReason`. That is the design: **an answer that looks complete but is wrong is worse than an
answer that says it does not know.**

## Why the numbers hold

Four independent lines of evidence, documented in [VALIDATION.md](VALIDATION.md) (Chinese):

| Evidence | Scale | Result |
|---|---|---|
| Reconciled against URLs the project authors wrote (frontend API files, integration tests) | 4,777 URLs across 231 repositories | 67.4% hit; **79.0%** on paths an application actually calls |
| Every endpoint traceable to source | **20,834** endpoints | **100%** point at a real mapping declaration |
| Reproducible | 110 repositories × 5 counts = **550 numbers** | 0 disagreements, recomputed by eight cold sessions that never saw the project |
| Generalisation | **98** unseen repositories, 136k source files | 98/98 analysed, 0 crashes, slowest 14 s |

The document also records seven real bugs this process found, and two conclusions it had to overturn —
including one where five sessions agreed with each other and were all wrong.

## Offline CLI

When a result looks wrong, split the layers: is it the analysis or the protocol?

```bash
J="java -jar target/spring-mcp-all.jar"
$J --dev controllers --root fixtures/demo-web --http-method GET
$J --dev callers     --root fixtures/layered-callers --service OrderService --method create
$J --dev mappers     --root fixtures/mybatis-xml
$J --dev gateway     --root fixtures/gateway
```

Service-level flags (`--index-cache`, `--project`, `--plugins`) must come **before** `--dev`.

## Boundaries

Deliberate, not missing:

- **No runtime.** No HTTP verb conflicts, no runtime-registered endpoints, no AOP proxy targets.
- **No cross-repository resolution.** One directory per analysis; other repositories are "external".
- **No deeper-than-2 call chains.** `depth=2` gives an impact *boundary*, not a precise list.
- **No meta-annotation expansion.** `@GetMapping` is not treated as `@RequestMapping`.
- **Kotlin is declaration-level.** A property whose type comes from an expression stays unknown.

Full list: [README.md § 读不出来的东西](README.md#读不出来的东西).

## Use it from an agent

`skills/` contains two SKILL.md packages — the reading rules, the tool-selection table and the
anti-patterns that make this server produce correct answers instead of confident ones. Without them an
agent will happily complete a `${placeholder}` into a plausible-looking URL.

```bash
cp -r skills/spring-mcp ~/.claude/skills/     # Claude Code
bash skills/build.sh                           # packaged zips → target/skills/dist/
```

## Engineering

173 source files, 28,452 lines. 320 tests. Zero `TODO`/`FIXME`. CI runs four jobs: build + zero-warning
gate, Windows smoke, an 11-repository corpus, and golden baselines over 9 real repositories pinned by
commit.

Deeper documents (Chinese): [architecture](docs/ARCHITECTURE.md), [extending with plugins](docs/EXTENDING.md),
[end-to-end evaluation](docs/E2E.md), [generalisation](docs/GENERALIZATION.md).

## License

Apache License 2.0 — see [LICENSE](LICENSE). Chosen over MIT for the explicit patent grant: this code is
meant to be embedded in companies, extended by third-party plugins, and redistributed.

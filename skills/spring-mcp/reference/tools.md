# Tool reference

Eleven tools. Nine analyse source, two exist so the model can discover what it is allowed to ask
about (`list-projects`, `list-modules`).

## Install

The analyzer is not part of this skill; it is an MCP server that has to be running. Full steps,
including troubleshooting: `INSTALL.md` at the root of this skill.

1. JDK 21 or newer.
2. Download `spring-mcp.zip` (or `spring-mcp-all.jar`) from
   <https://github.com/zhaogegejiafuhao/spring-mcp/releases>.
3. Add it to the client's MCP config, then restart the client:

```json
{
  "mcpServers": {
    "spring-mcp": {
      "command": "/abs/path/spring-mcp/spring-mcp.sh",
      "args": ["--project=/abs/path/to/your-spring-app"]
    }
  }
}
```

Windows: `"command": "C:/abs/path/spring-mcp/spring-mcp.cmd"`.

`scripts/preflight.sh` (or `preflight.cmd`) is read-only: it finds java, checks the version, locates
the jar and prints the exact JSON for the machine it runs on. It installs and downloads nothing.

Verify by asking the agent to call `list-projects`: it must return JSON, not an error.

---

Every tool returns the same envelope:

```json
{ "ok": true, "data": { "...": "tool-specific" }, "warnings": ["..."] }
```

and, alongside it, a one-line human summary in the text block (`找到 8 个端点。4 个 Controller / 7 个 handler`).
**Treat the text block as a caption, not as data.** Counts in an answer must come from `data`.

Common parameters, present on every analysis tool:

| Param | Type | Meaning |
|---|---|---|
| `projectRoot` | string | Absolute path to the project root. Omit to use the `--project` default |
| `project` | string | Alias configured at startup. **Only appears in the schema when aliases exist** |
| `scope` | string[] | Restrict to modules (project-relative paths, e.g. `services/order`). Parent dirs include children |
| `includeTests` | boolean | Also scan `src/test/java`, default `false` |

`projectRoot` and `project` are mutually exclusive: passing both is `INVALID_ARGUMENT`, not a
silent precedence rule.

---

## list-controllers

Every HTTP endpoint, with the file and line of the handler that declares it.

| Param | Type | Notes |
|---|---|---|
| `basePathFilter` | string | Keep only paths starting with this prefix, e.g. `/api` |
| `httpMethod` | enum | `GET` `POST` `PUT` `DELETE` `PATCH` `ANY` |
| `includeTests` | boolean | default `false` |

Returns `data.endpoints[]`, `controllerCount`, `methodCount`, `warnings[]`.

Per endpoint: `path`, `httpMethods[]`, `pathResolved`, `unresolvedReason`, `classPathRaw`,
`classPathResolved`, `controllerFqn`, `handlerMethod`, `signature`, `file`, `line`,
`handlerDeclaredIn`, `conditions[]`.

- Class prefix × method path × verbs is a cartesian product: one handler mapped to several URLs
  becomes several entries.
- Inherited mappings are included. `file`/`line` point at the **declaration** (often the parent
  class), and `handlerDeclaredIn` says where.
- `conditions` carries `@Profile` / `@ConditionalOn*` verbatim. It is not evaluated.

## find-service-callers

Direct call sites of a service class, optionally limited to one method.

| Param | Type | Notes |
|---|---|---|
| `serviceClass` | string **required** | Simple or fully qualified name |
| `methodName` | string | Omit for all methods |
| `includeSelfCalls` | boolean | default `true` |
| `includeUnresolved` | boolean | default `true` — name matched, receiver type not |
| `includeLowConfidence` | boolean | default `false` — infers types from variable names |
| `limit` | integer | default `500` |
| `depth` | integer | `1` direct only (default), `2` adds one caller layer |
| `maxUpstreamTypes` | integer | cap on expansion at `depth=2`, default `20` |

Each call site carries `rule` (`FIELD`, `SUPER_FIELD`, `CONSTRUCTOR_PARAM`, `METHOD_PARAM`,
`LOCAL_VAR`, `LOOP_VARIABLE`, `STATIC_CLASS`, `STATIC_IMPORT`, `IMPLICIT_THIS`, `NAME_CONVENTION`)
and `confidence`.

`depth=2` is an approximation: the second layer means "types that call a method of a first-layer
caller type", not a precise method-to-method edge. Report it as a boundary, not a list.

## find-implementations

Implementations and subclasses of an interface or class.

| Param | Type | Notes |
|---|---|---|
| `type` | string **required** | Simple or fully qualified name |
| `includeAbstract` | boolean | default `false` — abstract classes cannot be beans |
| `includeInterfaces` | boolean | default `false` |

Each result carries `springStereotypes[]` and `direct` (false when reached through a base class).
`direct: false` is exactly the case `grep "implements PaymentGateway"` cannot see.

## list-mappers

Persistence layer: MyBatis interfaces, MyBatis XML, Spring Data repositories — merged per mapper.

| Param | Type | Notes |
|---|---|---|
| `kinds` | array | `ANNOTATION`, `XML`, `JPA`; omit for all |
| `nameFilter` | string | Substring of interface or XML file name, case-insensitive |

Returns `mappers[]`, `orphans[]` (XML with no interface), `counts`, `warnings[]`.

Per mapper: `kind`, `interfaceFqn`, `simpleName`, `xmlPath`, `entityType`, `idType`,
`declaredMethods[]`, `statements[]`, `issues[]`.

`entityType` is `null` when the type argument is a type variable — that is "unknown", not `"T"`.

## search-symbol

Locate a type declaration by name. Cheapest useful call: know the file before reading it.

| Param | Type | Notes |
|---|---|---|
| `name` | string **required** | Name, fragment, or part of an FQN |
| `kinds` | array | `CLASS` `INTERFACE` `ENUM` `RECORD` `ANNOTATION` |
| `limit` | integer | default `50` |

Matches **declarations only** — a comment or a variable named `orderController` never matches.
Ranking: exact name, then prefix, then substring; shorter names first within a tier.

## search-by-annotation

Elements carrying an annotation.

| Param | Type | Notes |
|---|---|---|
| `annotation` | string **required** | Without `@`, e.g. `Transactional` |
| `elementKinds` | array | `TYPE` `METHOD` `CONSTRUCTOR` `FIELD` `PARAMETER` |
| `strictFqn` | boolean | `true` = exact FQN only; default matches by simple name |
| `limit` | integer | default `200` |

Simple-name matching can hit an unrelated annotation with the same name; the result says so.
Meta-annotations are **not** expanded: searching `RequestMapping` does not return `@GetMapping`.

## get-file-dependencies

Both directions of coupling for one source file.

| Param | Type | Notes |
|---|---|---|
| `file` | string **required** | Absolute, or relative to `projectRoot` |
| `includeSamePackage` | boolean | default `false` — same-package references need no import |

`outgoing` covers imports (plain, static, wildcard), same-package references and type usages.
`incoming` counts both imports and unimported same-package references — Spring codebases are
heavily same-package coupled, so import-only counting reports "nobody depends on this".

## list-gateway-routes

Spring Cloud Gateway: config files and `RouteLocator` beans in one table.

No parameters beyond the common ones.

Per route: `id`, `uri`, `serviceId`, `predicates[]`, `filters[]`, `pathPrefixes[]`,
`stripPrefix`, `targetModule`, `file`.

`pathPrefixes` comes from the `Path=` predicate; `stripPrefix` is `N` from `StripPrefix=N`.
`targetModule` maps `lb://name` onto a module **in this repository**, and is `null` for absolute
URIs or other services.

## list-feign-clients

The outbound half of the contract: `@FeignClient` interfaces and the endpoints they declare.

| Param | Type | Notes |
|---|---|---|
| `name` | string | Filter by client name or interface simple name |

Per client: `name`, `interfaceFqn`, `pathPrefix`, `url`, `targetModule`, `endpoints[]` where each
endpoint has `httpMethods[]`, `path`, `pathResolved`, `methodName`, `line`.

Clients declared without any mapping annotation are reported as "cannot tell which paths it
calls" rather than with an empty list.

## list-projects

Aliases configured through `--project`. Does **not** open the index — milliseconds even on a huge
repository. Returns `projects[]` (`alias`, `path`, `isDefault`, `exists`), `count`, `default`.

Returns an empty list plus an explanation when no aliases were configured. That is not an error.

## list-modules

Module list and inter-module dependencies.

| Param | Type | Notes |
|---|---|---|
| `includeDependencies` | boolean | default `true` |

Sources: Maven `<modules>`, Gradle `include`; if neither exists, directories containing
`src/main/java`. **Fewer than two modules is treated as no module structure**, and `scope` then
becomes invalid for that project.

---

## Error envelope

```json
{ "ok": false, "error": { "code": "CLASS_NOT_FOUND", "message": "找不到类: Ghost",
                          "hint": "确认类名拼写，或提供全限定名" } }
```

Codes: `INVALID_ARGUMENT`, `PATH_NOT_FOUND`, `PATH_NOT_DIRECTORY`, `PATH_NOT_READABLE`,
`NOT_A_SOURCE_FILE`, `CLASS_NOT_FOUND`, `AMBIGUOUS_CLASS`, `INDEX_BUILD_FAILED`,
`INDEX_TOO_LARGE`, `PARSE_ERROR`, `XML_PARSE_ERROR`, `TIMEOUT`, `PLUGIN_ERROR`, `INTERNAL_ERROR`.

A directory with no Java/Kotlin sources is `INVALID_ARGUMENT`, not "0 endpoints". That distinction
exists because a wrong `projectRoot` used to be indistinguishable from an application that
exposes nothing.

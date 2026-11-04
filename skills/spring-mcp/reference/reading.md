# Reading results

The tool is built around one trade-off: **a plausible wrong answer is worse than an honest
"I don't know"**. Most of the fields below exist to make that distinction visible instead of
letting it collapse into a confident-looking result.

---

## 1. Two payloads, one truth

Every successful call carries a text block and structured content:

```
text                找到 8 个端点。4 个 Controller / 7 个 handler
structuredContent   { ok, data, warnings }
```

The text block is a caption. Numbers, paths, file names and flags come from `data`.
When they disagree, `data` wins — and the disagreement is worth mentioning.

`warnings[]` must reach the user. They are not decoration: they mark truncation, inherited
handlers, inferred controllers, files that failed to parse, unexpanded route prefixes. A dropped
warning turns into a wrong answer two sentences later.

---

## 2. `pathResolved` / `unresolvedReason`

| Value | What actually happened |
|---|---|
| `pathResolved: true` | The path was fully computed, including compile-time constant folding |
| `pathResolved: false` + `PLACEHOLDER` | `${api.prefix}` or Kotlin `$name` — the value belongs to config or runtime |
| `pathResolved: false` + `LITERAL_CONSTANT` | A field that is not a compile-time constant (`@Value`, reassignable) |
| `pathResolved: false` + `CONCATENATION` | Part of the concatenation could not be computed |

Correct answer:

> `GET /tg/${tg.bot.token}` — `pathResolved=false`, reason `PLACEHOLDER`. The mapping is declared at
> `TelegramController.java:41`; the real URL depends on the configured token, so it cannot be
> determined statically.

Wrong answer: `GET /tg/webhook` (invented), or omitting the endpoint (hides a real route), or
`GET /tg/` (silently dropping the placeholder).

Constant folding is aggressive on purpose: `static final String`, interface constants, `const val`,
constants defined in terms of constants, nested classes, `import static`, and `"/a" + Const.B` are
all resolved. If a path is still unresolved, it is genuinely not knowable from source.

`${x:默认值}` is **not** resolved to the default. The default applies only when the property is
unset, which is a deployment fact, not a source fact.

---

## 3. `conditions`

`@Profile("dev")`, `@ConditionalOnProperty(...)`, and project-specific composed annotations are
copied into `conditions[]` verbatim. They are never evaluated.

So:

- Never drop a conditional endpoint. It exists in the source and may exist in production.
- Never say "this endpoint is live in prod" either. What you know is the condition's text.
- Useful phrasing: "gated by `@Profile(\"dev\")` — present in the source, active only when that
  profile is on."

---

## 4. Call sites: `rule` and `confidence`

`find-service-callers` reports how it decided the receiver's type. This matters more than the count.

| `rule` | Evidence | Trust |
|---|---|---|
| `FIELD`, `SUPER_FIELD` | Receiver is a field of this class or its parent | high |
| `CONSTRUCTOR_PARAM`, `METHOD_PARAM` | Injected through the constructor or the method | high |
| `LOCAL_VAR`, `LOOP_VARIABLE` | Local variable or loop variable | high |
| `STATIC_CLASS`, `STATIC_IMPORT` | Static call | high |
| `IMPLICIT_THIS` | No receiver, same class | high |
| `NAME_CONVENTION` | `orderService` guessed to be `OrderService` | **guess, off by default** |

Do not switch on `includeLowConfidence` to make a list longer. If the user needs completeness over
precision, say that is the trade being made.

`unresolved[]` lists call sites where the name matched but the receiver type could not be proven.
Chained calls (`getX().y()`), method references and lambda parameters land there. They are not
"no callers" — they are "callers we cannot prove".

---

## 5. `depth=2` is a boundary, not a list

`depth: 1` returns direct call sites. `depth: 2` adds one more layer: for each caller **type** found
in layer one, who calls that type. It cannot be restricted to a specific method.

Report it as "the impact reaches at most these types", never as "these are the callers". Expansion
is capped by `maxUpstreamTypes` (default 20) and the cap is announced in `warnings`.

---

## 6. `scope`

`scope` filters **results after** the index is built. The index always covers the whole repository,
so cross-module resolution still works inside a scope.

Consequences:

- A scoped 0 is not "the project has none". Say "0 within `billing`".
- `scope` does not make the call faster. Only a narrower `projectRoot` does, at the cost of
  cross-module resolution.
- On a single-module project, `scope` is `INVALID_ARGUMENT` rather than ignored.

---

## 7. Limits and truncation

`truncated: true` (symbols, annotations) or a hit `limit` (call sites, default 500) means the list
is partial. Say so and narrow the query — a filter, a smaller `scope`, a specific method — instead
of presenting a partial list as complete.

---

## 8. Boundaries worth quoting to the user

These are deliberate, not missing features. The right phrasing is "not visible to static
analysis", not "does not exist".

| Not visible | Why |
|---|---|
| Actuator / starter-registered endpoints | Registered by a library at runtime; no mapping in source |
| Mappings added by `RequestMappingHandlerMapping.addMapping` at runtime | Not in source |
| AOP proxy targets (`@Transactional`, `@Cacheable`) | Woven at runtime |
| `RestTemplate` / `RestClient` / `WebClient` URLs | Assembled at the call site; no declarative contract |
| SpEL and `@Value` expressions | Evaluated against the environment |
| `web.xml` suffix mappings (`*.do`) | Only annotations and `RouterFunctions` are recognised |
| Meta-annotation expansion | `@GetMapping` is not treated as `@RequestMapping` |
| Cross-repository resolution | One directory per analysis; other repos are always "external" |
| Call chains deeper than 2 | Needs a type-level call graph |
| Generic type inference | No symbol solver; types come from syntax |

Kotlin is parsed at declaration level: classes, interfaces, objects, functions, properties,
annotations, supertypes, nested and `companion object`. A property whose type comes from an
expression (`val repo = findRepo()`) stays unknown.

---

## 9. A worked answer

Illustrative: the repository is invented; only the shape of the answer matters here.

**Question:** "What does this service expose? Anything dev-only?"

**Calls**

```
list-projects                                   → default: petclinic
list-modules projectRoot=/work/petclinic        → ["."], single module, so no scope
list-controllers projectRoot=/work/petclinic    → 14 endpoints, 4 warnings
```

**Answer**

> 14 endpoints across 4 controllers (`OwnerController`, `PetController`, `VetController`,
> `WelcomeController`).
>
> Three are gated by conditions and will not be live in production:
> `GET /debug/env` (`@Profile("dev")`, `DebugController.java:22`),
> `POST /debug/reload` (same file, line 31), and `GET /admin/metrics`
> (`@ConditionalOnProperty("admin.metrics.enabled")`, `AdminController.java:18`).
>
> Five handlers come from `AbstractCrudController`; their `file`/`line` point at the parent class,
> as reported in `handlerDeclaredIn`.
>
> One mapping is not statically resolvable: `GET /report/${report.format}` at
> `ReportController.java:57` (`pathResolved=false`, `PLACEHOLDER`). The real URL depends on the
> configured format.

Note what the answer does: it keeps unresolved items inside the count, names the condition instead
of guessing its truth, and attributes inherited handlers to the file that actually declares them.

---

## 10. When not to call a tool at all

- The question is about runtime behaviour ("is this endpoint slow", "why does it 500") — static
  analysis has nothing to say.
- The user asks about a non-Spring framework. Solon, JAX-RS and Quarkus use similar annotations
  with different semantics; the tool will report "controllers but no endpoints", which is true and
  useless. Say the framework is outside scope.
- The repository is not on disk. Ask for the path; do not guess from a package name.

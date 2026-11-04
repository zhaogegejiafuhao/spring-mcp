# <repository name> — Spring surface audit

<one line: what this repository is, as far as the code says>

---

## 1. Scope

| | |
|---|---|
| Repository | `<absolute path>` |
| Modules | `<n>` — `<module>, <module>, …` (or "single module") |
| Module edges | `<a> → <b>, <c>` (or "none declared") |
| Analysed at | `<git rev or date>` |
| Method | spring-mcp static analysis — source read, nothing compiled or started |

## 2. Summary

| Metric | Count |
|---|---|
| HTTP endpoints | `<list-controllers → endpoints.length>` |
| Controllers | `<controllerCount>` |
| Handler methods | `<methodCount>` |
| Mappers | `<list-mappers → mappers.length>` |
| Feign clients | `<list-feign-clients → total>` |
| Gateway routes | `<list-gateway-routes → count>` |
| Endpoints with conditions | `<count>` |
| Endpoints not statically resolved | `<count>` |

## 3. HTTP surface

<Inherited handlers: `file`/`line` point at the declaring class, not the subclass.>

### `<module>`

| Verb | Path | Handler | Controller | File:line | Conditions |
|---|---|---|---|---|---|
| GET | `/api/orders` | `list` | `OrderController` | `OrderController.java:31` | — |

<Repeat per module. Mark unresolved paths with the raw text and a footnote.>

## 4. Outbound

### Feign clients

| Client | Target module | Path prefix | Endpoints |
|---|---|---|---|
| `OrderClient` | `ordering` | `/api/orders` | 3 |

<or: none declared in source>

### Gateway routes

| Route | Path | StripPrefix | Target module | Effect |
|---|---|---|---|---|
| `orders` | `/api/orders/**` | 1 | `order-service` | external `/api/orders/x` → internal `/orders/x` |

<or: no gateway configuration in this repository>

## 5. Persistence

| Mapper | Kind | Entity | Statements |
|---|---|---|---|
| `OrderMapper` | ANNOTATION + XML | `Order` | 12 |

Orphan XML (no matching interface): `<n>` — `<paths>` / none.

## 6. Cross-checks

| Check | Result |
|---|---|
| Gateway prefix → controller | `<n>` routes match a controller prefix, `<m>` do not — `<details>` |
| Feign endpoint → target module | `<n>` matched, `<m>` unmatched — `<details>` |
| Conditional endpoints | `<n>` endpoints gated, listed in §3 |
| Controller without endpoints | `<n>` — `<names or "none">` |
| Unresolved paths | `<n>` — see §7 |

## 7. Not determined

| Path | File:line | Reason |
|---|---|---|
| `/report/${report.format}` | `ReportController.java:57` | `PLACEHOLDER` — value comes from configuration |

Out of scope for this analysis, and therefore not claimed either way:

- Actuator and starter-registered endpoints
- Mappings registered at runtime
- `RestTemplate` / `RestClient` / `WebClient` URLs
- AOP proxy targets (`@Transactional`, `@Cacheable`)
- Cross-repository references — other services are always "external" here

## 8. Appendix — calls

```
list-projects
list-modules                      projectRoot=<root>
list-controllers                  projectRoot=<root>
list-controllers                  projectRoot=<root> scope=[<module>]
list-feign-clients                projectRoot=<root>
list-gateway-routes               projectRoot=<root>
list-mappers                      projectRoot=<root>
```

<Add any follow-up call made to resolve an ambiguity, with the reason.>

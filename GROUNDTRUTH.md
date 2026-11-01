> 本文是过程记录。结论汇总与四层证据见 [`VALIDATION.md`](VALIDATION.md)。

# 用「项目自己写下的契约」验收（2026-10-31）

问题：前面那些数字（13,246 个端点、常量 258→4）都对吗？

办法：不拿工具的输出跟自己对账，而是找**项目作者自己写下来的 URL**——前端 `src/api/**` 里调用的地址，
和集成测试里 `mockMvc.perform(get("/x"))` 打过的路径——再拿工具的输出去比。

## 结论先说

| | |
|---|---|
| 有可用契约的仓库 | 36 / 110（6 个带前端、31 个带集成测试，1 个两者都有） |
| 契约 URL 总数 | **939** |
| 工具端点能匹配上 | **743（79.1%）** |
| 其中靠「整条路径都是变量」匹配的弱证据 | 28 |
| 严格口径 | **715 / 939 = 76.1%** |
| 完全一致的来源 | **20 / 37** |
| 覆盖到的端点数 | 7,159（全量 13,246 的 54%） |

不一致的 17 个来源逐条核对过：**没有一条是「工具漏了控制器」**，但抓到 1 条工具把路径写错了（已修），
以及 2 条「值不在源码里」的边界（已写进告警/文档）。

## 怎么比的

- **前端**：`src/api/**/*.js|ts` 里所有以 `/` 开头的字符串字面量（`url: '/x'` 与 `postRequest('/x')` 两种写法）。
  前缀从 `baseURL: '/admin'` 或 `baseURL: 'http://host/admin/'` 里取，再试 `/api`、`/prod-api`、`/dev-api`。
- **测试**：`src/test/**` 里 `get("/x")` / `post("/x")` / `.uri("/x")` / `.path("/x")` 的字面量，跳过注释行与含 `$` 的模板。
- **匹配**：按 `/` 分段，逐段相等，或任一侧是 `{id}` / `{id:\d+}` / `*` / `**`。
- **去噪**：`http(s)://`、`/@/`（Vite 别名）、`/node_modules` 一律不算。

## 完全一致：20 个来源

| 仓库 | 来源 | URL 数 |
|---|---|---|
| `liyupi/yu-ai-code-mother` | 前端 | 27 |
| `istonikula/realworld-api`（RealWorld 规范实现） | 测试 | 15 |
| `springboot-angular2-tutorial/boot-app` | 测试 | 12 |
| `techa03/goodsKill` | 测试 | 12 |
| `yudaocode/SpringBoot-Labs` | 测试 | 11 |
| `ityouknow/spring-boot-leaning` | 测试 | 9 |
| `itwanger/paicoding` | 测试 | 7 |
| `ityouknow/spring-boot-examples` | 测试 | 7 |
| `sdeleuze/geospatial-messenger` | 测试 | 6 |
| `sqshq/piggymetrics` | 测试 | 6 |
| `moshowgame/SpringBootCodeGenerator` | 测试 | 5 |
| `Baeldung/spring-security-registration` | 测试 | 4 |
| `alibaba/spring-cloud-alibaba` | 测试 | 4 |
| `dyc87112/SpringBoot-Learning` | 测试 | 3 |
| `spring-guides/tut-spring-boot-kotlin` | 测试 | 3 |
| `loda-kun/spring-boot-learning` | 测试 | 2 |
| `spring-guides/gs-testing-web` | 测试 | 2 |
| `dyc87112/SpringCloud-Learning` | 测试 | 1 |
| `namastack/namastack-outbox` | 测试 | 1 |
| `wuyouzhuguli/SpringAll` | 测试 | 1 |

`paicoding` 这一行是这次验收的意义所在：修复前是 **2/7**，因为工具把 `{articleId:\d+}` 里的反斜杠
改写成了 `/`（详见下节）。修完 7/7。

## 不一致的 17 个来源：逐条归类

| 仓库 | 命中 | 原因（已核对源码） |
|---|---|---|
| `spring-cloud/spring-cloud-kubernetes` | 6/66 | 测试里的 URL 是给**模拟的 K8s API server** 用的（`/api/v1/namespaces/...`），不是本应用的端点 |
| `spring-cloud/spring-cloud-config` | 1/35 | `@RequestMapping("${spring.cloud.config.server.prefix:}/{name}/{profiles}")`：前缀由配置决定，工具按占位符返回 |
| `mateaix/matecloud` | 129/158 | `/auth/captcha` 由 `captcha-plus` starter 自动注册；`/admin/dict-types` 等在仓库里搜不到 |
| `1024-lab/smart-admin` | 181/197 | 前端调 `/support/dict/key/query`，后端只有 `/support/dict/queryPage`，全仓库搜不到 `dict/key` → 前后端版本不一致 |
| `techa03/goodsKill`（前端） | 0/13 | 前端调 `/seckill/getSeckillList`，后端只有 `/seckill/list`；`getSeckillList` 只出现在 Feign 客户端里 |
| `moxi624/mogu_blog_v2` | 197/206 | `/adminRole/*`、`/collect/*` 在仓库里没有对应控制器（前端比后端新） |
| `Naccl/NBlog` | 54/59 | 5 条是 GitHub API（`/users/{name}/repos`）与 `/user`，不是本站接口 |
| `YunaiV/yudao-cloud` | 0/3 | `WebMvcRegistrations.setPathPrefixes` 加 `/admin-api` 前缀，前缀来自配置 |
| `hs-web/hsweb-framework` | 3/9 | 测试类里**自己声明**了 `@RequestMapping("/tree")` 的控制器，属测试源码 |
| `Tencent/spring-cloud-tencent` | 0/7 | 同上：`/test` 来自测试类里的内部控制器；`/echo` 在 examples 里但测试打的是带上下文前缀的地址 |
| `ZHENFENG13/ssm-demo` | 0/3 | `*.do` 后缀映射（web.xml），不是注解路由 |
| `spring-petclinic/spring-petclinic-microservices` | 7/11 | 2 条是 WebJar 静态资源（`/webjars/missing.js`），1 条是 BFF 路由 `/owners/details/3` |
| `CodingDocs/springboot-guide` | 6/8 | 测试写的是 `/api/person`，后端是 `/api/persons`：**测试自己写错了** |
| `Baeldung/spring-security-oauth` | 3/4 | `/oauth/token` 由 Spring Security OAuth 的 `TokenEndpoint` 提供，不在仓库里 |
| `spring-projects/spring-security-samples` | 18/20 | `/ott/generate`、`/login/ott` 由 Security 的 OTT 过滤器与成功处理器注册，不是 `@Controller` |
| `spring-cloud/spring-cloud-netflix` | 0/1 | `/apps` 是测试里 mock server 的路径 |
| `bastman/spring-kotlin-exposed` | 0/1 | `/health` 是 actuator |

## 这次验收抓到的三个问题

### ① 路径里的反斜杠被当成目录分隔符（真 bug，已修）

`@GetMapping("/article/detail/{articleId:\\d+}")` 在源码里是 `{articleId:\d+}`，工具输出成了
`/article/detail/{articleId:/d+}`——一个**看起来像路由、实际不存在**的路径，而且拿它跟任何真实请求都对不上。
根因是 `PathJoiner.clean()` 里的 `replace('\\', '/')`（本意是容忍 Windows 风格的用户输入）。

修法：URL 路径不再改写反斜杠；只有调用方传入的 `basePathFilter` 走 `cleanFilter()`。
补了两个测试（单测 + 端到端），`paicoding` 的契约匹配从 2/7 变成 7/7。

### ② 全局路径前缀不可见（已加告警）

`yudao-cloud` 用 `WebMvcRegistrations.setPathPrefixes` 给所有 controller 加 `/admin-api`，
前缀从 `application.yaml` 的 `yudao.web.admin-api.prefix` 读——注解里一个字都没有。
现在 `list-controllers` 会说明：

```
发现 1 处可能改写全局路径前缀的配置（WebMvcRegistrations / configurePathMatch）：
这里给的是注解里的路径，外部实际 URL 可能还带一层前缀，网关与前端看到的是后者: ...YudaoWebAutoConfiguration
```

### ③ `${x:default}` 不展开（写进不支持清单）

`spring-cloud-config` 的 35 条、`RuoYi-Vue-Plus` 的 SseController 都是这个形状。它的值由
"属性有没有被覆盖"决定，静态分析只能看到默认值。工具继续按 `PLACEHOLDER` 原样返回，
不假装知道——这是四条"没答案"里唯一一条本可以再往前一步的。

## 交叉验证：注解数 vs 端点数（110 个仓库）

再用一个和 URL 无关的口径核对召回：grep 数出所有 `@XxxMapping`（在含 `@RestController`/`@Controller`
的文件里），和工具输出的端点数比。

- **106 / 110 个仓库工具 ≥ grep**（多出来的是方法级 `@RequestMapping`、继承来的 handler、`RouterFunctions`）
- **4 个仓库工具 < grep**，逐个查过：
  - `SpringBoot-Labs` −7：5 条来自 `//@RestController`（注释掉的注解），2 条是注释掉的 `@GetMapping`
  - `java-common-mistakes` −6：同类，注释里的注解
  - `matecloud` −4：`GenCodeTemplate.java` 是代码生成模板，里面的注解是**字符串内容**
  - `RuoYi-Vue-Plus` −1：`SseController` 的 `${message.path:...}` 占位符（见上）

也就是说，在能自动对账的 110 个仓库里，没有发现"控制器存在但端点消失"的情况。

## 这套方法的局限

1. **前端与后端版本不一致时，"不一致"不等于"工具错"**。`smart-admin`、`mogu`、`goodsKill` 都属于这种，
   我逐条 grep 过：前端调的路径在后端源码里根本不存在。
2. **只有 6 个仓库前后端同仓**，其余 104 个没有书面契约可比，只能退回到"注解数 vs 端点数"这种弱口径。
3. **匹配只看路径形状，不看 HTTP 动词**。同一个路径 GET/POST 都认。
4. **弱匹配 28 条**：靠 `/{a}` 这种整条都是变量的模式命中的，不算强证据，已从严格口径里剔除。

## 复现

```
.realworld/DumpEndpoints.java   导出 110 个仓库的全部端点 → endpoints.jsonl
.realworld/compare.py           前端/测试 URL 对账 → groundtruth.json
.realworld/classify.py          未命中 URL 的粗分类
.realworld/recall.py            注解数 vs 端点数 → recall.json
```

> 本文是过程记录。结论汇总与四层证据见 [`VALIDATION.md`](VALIDATION.md)。
>
> **数字已过期**：本文里的 20,475 / 97 个仓库，是在检出残缺时算的。修复后是 **20,834 / 98 个**，原因见 `VALIDATION.md` 末尾的「更正」。

# 两组验证：同一批仓库能被复现，换一批仓库仍然正确

两个问题，两个实验：

1. **复现**：换一个从没参与过的 AI，用这套工具把原来 110 个仓库重新量一遍，数字对不对得上？
2. **泛化**：换 100 个没调过的、更大的项目，结论站不站得住？

---

## 一、复现：110 个仓库 × 5 个字段 = 550 个数字，0 处不一致

### 做法

把 110 个仓库切成 8 份，交给 8 个冷启动会话。每个会话只拿到两样东西：工具清单，和一条命令
`mcp-tool.sh <tool> projectRoot=<目录>`。规则写死：只准调用工具，不许 cat/grep 源码，每个数字必须来自一次真实调用。

每个仓库记 5 个数：`endpoints`、`controllers`、`mappers`、`feignClients`、`gatewayRoutes`。

基线是之前那次脚本扫描（`Scan.java` 直接调分析器）写下的 `results.jsonl`。两边除了同一批 checkout，
没有任何共享——工具名、参数、JSON 字段都是独立走的。

> 说明：8 个会话跑完 93 个仓库；剩下 17 个是我用同一条工具命令串行补的（机器配置有限，改为不并行）。
> 这 17 个仓库的比对结果与其余 93 个没有区别，但口径上要写清楚。

### 结果

```
baseline repos: 110, answered repos: 110, duplicates: 0
参与比对: 110 个仓库 × 5 个字段 = 550 个数字
不一致: 0 处
```

550 个数字逐个相等，没有一个仓库、一个字段对不上。这说明两件事：

- **工具层的数字等于分析器内部的数字**，中间没有损耗或改写；
- **一个只会读 JSON 的调用方拿到的就是内部真值**，不需要额外知识。

它不能说明的：这不是「AI 会不会用工具」的测试（上一份 `E2E.md` 测的是那个），这里测的是稳定性。

---

## 二、泛化：100 个新的大型项目

### 选样本

GitHub 搜索 API，8 组关键词（spring / spring-boot / springcloud / mybatis / microservice / admin / mall / kotlin），
取 290 个候选，过滤掉已克隆的 110 个，再按 `language ∈ {Java, Kotlin}`、`size ≥ 12MB`、`stars ≥ 300` 筛，
按体积取前 100 个。

| | |
|---|---|
| 体积 | 中位 **94 MB**，最大 249 MB，合计约 11.5 GB |
| 源码量 | 135,676 个 `.java`/`.kt`，单仓库中位 524 个，最大 23,270 个 |
| 克隆 | **97/100 成功**；3 个在这台机器上根本检不出来（见下） |

### 结果

| | |
|---|---|
| 成功分析 | **97 / 97**（0 崩溃、0 超时、0 工具异常） |
| 端点 | **20,475** |
| Controller | 5,234 |
| MyBatis/JPA 接口 | 3,057 |
| Feign 客户端 | 309 |
| 网关路由 | 132 |
| 未解析路径 | 47（占 0.23%） |
| 单仓库耗时 | 中位 **0.39 秒**，p90 2.3 秒，最慢 14.0 秒（`eugenp/tutorials`） |

3 个仓库没跑成，原因都在 checkout 本身，与工具无关：`NotFound9/interviewGuide` 有一个以点结尾的路径
（`static/640-1487645.`）、`mercyblitz/segmentfault-lessons` 有带尾随空格的中文文件名、`cheese10yun/blog-sample`
依赖未安装的 git-lfs。这三个目录里只有十几个文件、没有一个 `.java`，工具给的是
`INVALID_ARGUMENT: 目录下没有 Java/Kotlin 源码`——不是「0 个端点」。上一轮加的守卫在这里正好派上用场：
没有它，这三个会变成三条「这个服务不暴露任何接口」的假答案。

### 一致性：同一批仓库跑两遍

用**空缓存**重跑一遍（`LOCALAPPDATA` 指向新目录，强制重建全部索引），与温缓存那遍逐字段对比：

```
mismatched fields between warm-cache and cold-cache runs: 0
endpoint lists identical: True
```

两万条端点的路径、方法、`resolved` 标记，两次运行逐条相同。缓存没有改变结论。

### 正确性（一）：项目自己写下的契约

和第一批同样的口径：以前端 `src/api/**` 的 URL、集成测试里的 `get("/x")` 作为标准答案，两边都不看注解。

| | 第一批（110 个） | 第二批（97 个） |
|---|---|---|
| 契约 URL | 939 | 3,115 |
| 命中 | 743（**79.1%**） | 2,225（**71.4%**） |
| 完全一致的来源 | 20 / 37 | 11 / 45 |

第二批低 8 个点，逐条查过未命中的来源，**没有一条是「工具漏了控制器」**，主要三类：

| 类型 | 例子 |
|---|---|
| 测试里的**虚构路径**（用来测过滤器/拦截器的任意字符串） | `spring-security` 的 `/path`、`/PATH_NOT_MATCHING_CONFIG_ATTRIBUTE`；`spring-framework` 的 STOMP 目的地 `/topic/...` |
| 客户端把 base 和 path 拆成两段字面量 | `frostmourne` 前端 `baseURL=/api/monitor-api/admin` + `url:/findById`；工具确实报出了 `/api/monitor-api/admin/findById` |
| 端点由别的服务/starter 提供 | `bizspring-shop` 的 `/auth/oauth/token`、`/upms/user/info` 不在本仓库源码里 |

顺带修了一个**评估口径**问题：前端走 dev 代理把 `/system/ad/position/list` 转到后端 `/api/system/ad/...` 时，
原来的匹配器只做「去掉前缀」不做「补上前缀」，200 条被误判为未命中。补上之后第二批从 65.2% 升到 71.4%
（第一批不变，79.1%）。

### 正确性（二）：注解数 vs 端点数（不依赖契约）

对每个仓库数 `src/main` 里 `@GetMapping`/`@PostMapping`… 的出现次数（去掉注释、去掉 `@FeignClient` 接口，
它们是出站契约不是入站端点），与工具的端点数比：

```
tool >= 注解数: 92/97   （相等 33，更多 59）
tool <  注解数: 5
```

「更多」是正常的：一个 handler 挂多个路径、类级前缀展开、继承来的 handler 都会让端点数大于注解数。
剩下 5 个逐个看过，缺口都在**接口类型**上（`OutinterfaceModelServiceApi` 这类 Dubbo/内部 API 定义，
带映射注解但不是 Spring 控制器），以及 `spring-tools` 那种把 `@RequestMapping` 当字符串处理的扫描器类。

### 这一轮抓到的真缺陷：元注解控制器

`codecentric/spring-boot-admin` 报 **0 个端点**。它是 Spring Boot Admin 的服务端，REST API 是它的全部意义。

查下去：它的控制器用的是自定义标记注解 `@AdminController` + `@ResponseBody`，并注册了自己的
`RequestMappingHandlerMapping`（`isHandler()` 返回 `hasAnnotation(beanType, AdminController.class)`）。
Spring 默认的映射只认类型上的 `@Controller` / `@RequestMapping`，所以工具一个都没找到。

修法：一个类型如果**有方法级映射注解**，并且**类上有 `@ResponseBody`（这正是 `@RestController` 的另一半）
或者注解名以 `Controller` 结尾**，就按控制器计入，同时在 warnings 里点名这是推断的：

```
6 个类带方法级映射注解但没有 @Controller/@RestController，按 @ResponseBody 或自定义 *Controller
注解推断为控制器（常见于自定义 RequestMappingHandlerMapping）：
ApplicationsController, InstancesController, InstancesProxyController, NotificationFilterController, UiController
```

效果是外科手术式的——100 个仓库里只有 3 个变化，其余 97 个一个数字都没动：

| 仓库 | 端点 | 控制器 |
|---|---|---|
| `codecentric/spring-boot-admin` | 0 → **25** | 0 → 6 |
| `springfox/springfox` | 258 → 260 | 33 → 34 |
| `eugenp/tutorials` | 2261 → 2262 | 1030 → 1031 |

9 个基线仓库的 golden **全部不变**，110 个仓库重扫 **0 个字段变化**。
新增 4 个测试锁住这条规则的边界（标记 + 映射方法才算；只有标记没有映射不算；只有映射方法没有标记也不算），
全量 **294 个测试全绿**。

### 13 个零端点仓库，都核对过

`mybatis-3`、`spring-batch`、`spring-ai`、`dubbo`、`helidon`、`eventmesh`、`spring-boot` 等是框架/库，本身不暴露 HTTP 端点；
`ToDou/appbarlayout-spring-behavior` 是 Android 库；`417511458/jbone`、`you-series/ureport` 的主源码里没有映射注解。

唯一需要说明的是 `opensolon/solon`：它用 `@Controller` 但配自己的 `@Mapping`，不是 Spring MVC。工具报 67 个控制器、
0 个端点，并写了「67 个 controller 没有任何 HTTP 映射」——它说的是实话，没有编端点出来。

---

## 三、结论

| 问题 | 答案 | 依据 |
|---|---|---|
| 换个 AI 重跑，结果一样吗 | **一样** | 110 × 5 = 550 个数字，0 处不一致 |
| 换一批没见过的项目，还能跑吗 | **能** | 97/97 成功，135,676 个源文件，最慢 14 秒 |
| 跑两遍结果一样吗 | **一样** | 空缓存重跑，两万条端点逐条相同；第三遍对前 95 个仓库零变化 |
| 结果对不对 | **大部分对，错的能解释** | 契约 71.4%；注解口径 92/97；未命中逐条查过 |

这一轮的实际收获不是数字，是**一个真缺陷**：元注解 + 自定义 handler mapping 的控制器此前完全看不见。
它只在 3/210 个仓库里出现，但其中一个（spring-boot-admin）是「整套 API 全部消失」的量级。

## 四、边界

- **3 个仓库没跑成**：两处 Windows 非法文件名、一处缺 git-lfs，checkout 本身就建不起来；工具正确拒绝了它们，没产生假答案。
- **契约只覆盖 45 个来源**（97 个仓库里）：其余仓库没有前端也没有集成测试，只能退回注解口径。
- **注解口径本身不完美**：它数不出「一个 handler 挂多个路径」，也数不出继承来的 handler，只用来发现「工具明显少报」。
- **元注解推断有误报风险**：`@ResponseBody` + 映射方法的类如果没被任何 handler mapping 注册，会多报端点。
  这类类在实践中极其罕见（本轮 100 个仓库只命中 3 个，都对得上），但它不是零风险，所以每一项都写进 warnings。
- **Solon / JAX-RS / Quarkus 不是这套工具的适用范围**：它们用 `@Controller` 但方法注解不同，工具会说「没有映射」，不会瞎编。

## 五、复现

```bash
# 复现比对
cd .realworld && python repro/diff.py

# 第二批扫描（串行，一条命令一个仓库）
java -cp target/spring-mcp-all.jar .realworld/ScanTools.java \
     .realworld/repos2 .realworld/results2b.jsonl .realworld/endpoints2b.jsonl

# 契约对账
REALWORLD_REPOS=D:/springMCP/.realworld/repos2 \
REALWORLD_ENDPOINTS=D:/springMCP/.realworld/endpoints2b.jsonl \
REALWORLD_OUT=D:/springMCP/.realworld/groundtruth2.json python compare.py
```

| 文件 | 内容 |
|---|---|
| `repro/slice-*.json`、`repro/out-*.jsonl` | 8 个会话的分片与原始答案 |
| `repro/diff.py` | 550 个数字的比对脚本 |
| `ScanTools.java` | 走 `ToolProvider.invoke` 的批量扫描器，支持断点续跑 |
| `results2d.jsonl` / `endpoints2d.jsonl` | 第二批 100 个仓库（97 成功）的最终结果与逐端点明细 |
| `results1-final.jsonl` | 第一批用最终版本的回归重扫，用于证明修复没有污染基线 |
| `groundtruth2.json` | 第二批的契约对账明细 |

# spring-mcp

Spring Boot 源码静态扫描的 MCP（Model Context Protocol）服务，Java 与 Kotlin 都读。

**只做静态分析**（Java 走 JavaParser 的 AST，Kotlin 走声明级解析）：不启动 Spring 上下文，不把目标工程的类加载进 classpath，不编译目标工程。给它一个工程目录，它读源码、建索引，然后回答"这个 URL 谁处理""这个 Service 谁在调""这个表由哪个 mapper 操作"。

```
MCP client  ──stdio(JSON-RPC)──▶  spring-mcp  ──JavaParser / Kotlin 词法+声明解析──▶  你的 Spring 工程源码
```

两种语言进的是**同一份索引**：Kotlin 声明被规约成与 Java 完全相同的 `CuSummary`，所以工具层一行代码都不用区分语言。
一个 Kotlin 的 `@RestController` 和一个 Java 写的 `@RestController`，在 `list-controllers` 里没有区别。

### 它看得见什么，看不见什么

先看这张表再决定要不要用——这里的每一条都是取舍，不是待办。

| 看得见 | 看不见 |
|---|---|
| `@RestController` / `@Controller` 上的 `@GetMapping` 等注解式端点 | Actuator 端点（`/actuator/**`）：由 starter 注册，源码里没有映射 |
| `RouterFunction` / `coRouter` 的函数式路由（字面量路径），含同文件内 `nest(...)` 的字面量前缀 | 跨类的路由装配（A 类的 `nest` 套 B 类返回的路由）：只告警说这层前缀存在且未应用，不拼路径 |
| 类前缀 + 方法路径的拼接，含常量折叠（`Const.BASE + "/x"`） | 运行时才注册的映射（`RequestMappingHandlerMapping` 动态 add） |
| MyBatis / JPA 接口与 XML 的语句、Feign 客户端、网关路由 | `${...}` 占位符与 `@Value` 注入：值由配置决定，只报原文并标未解析 |
| 条件装配（`@Profile` / `@Conditional*`）**原样列出**，不求解 | 非 Spring 的 Web 框架（Solon 的 `@Mapping`、JAX-RS 的 `@Path`）：会明说"有控制器、没端点" |
| `nest(...)` 前缀是非字面量（`path("/apis/" + gv)`）时，端点标 `pathResolved=false` | 前缀分散在多个 `nest` 上时，只拼能读出来的那几层 |
| 全局路径前缀：能读出字面量时报警告并给出候选前缀 | 全局前缀来自配置项（`${yudao.web.api.prefix}`）时，只告警，不猜值 |

一句话：**注解写下来的，它读得到；由配置或运行时决定的，它说不知道。**

---

## 目录

**用它**

- [它看得见什么，看不见什么](#它看得见什么看不见什么) — 先看这张表，再决定要不要用
- [快速开始](#快速开始) — 构建、接进 MCP 客户端、验证
- [内置工具](#内置工具) — 十一个工具的参数与行为
- [结果与错误格式](#结果与错误格式) — 成功与失败分别长什么样

**扩展它**

- [写一个插件](docs/EXTENDING.md#写一个插件) — SPI、classloader 隔离
- [声明式扩展](docs/EXTENDING.md#声明式扩展) — 改配置就能加工具，不用写 Java

**维护它**

- [工程结构](docs/ARCHITECTURE.md#工程结构) — 包分层、索引、缓存
- [离线命令行](#离线命令行) — 不经过 MCP，直接查
- [CI 与发版](#ci-与发版)

**边界**

- [读不出来的东西](#读不出来的东西) — 刻意不做的事，以及做到哪一层
- [环境备忘](#环境备忘)
- [许可](#许可)

**证据**——想知道它准不准，看这几篇，不要看功能列表

| 文档 | 回答什么问题 |
|---|---|
| [`VALIDATION.md`](VALIDATION.md) | 那些数字凭什么可信：四层互相独立的证据 |
| [`docs/E2E.md`](docs/E2E.md) | 一个没见过该仓库的 AI，只给工具能不能答对（答案与判分脚本在 `tools/e2e/`） |
| [`docs/GENERALIZATION.md`](docs/GENERALIZATION.md) | 换成 98 个没调过的仓库还成不成立 |
| [`docs/GROUNDTRUTH.md`](docs/GROUNDTRUTH.md) | 跟前端和集成测试里写死的 URL 逐条对账 |
| [`docs/REALWORLD.md`](docs/REALWORLD.md) | 110 个仓库的扫描过程与踩到的坑 |
| [`docs/TOOLSELECT.md`](docs/TOOLSELECT.md) | 工具该不该合并，以及怎么测的 |
| [`tools/corpus/check-kotlin.py`](tools/corpus/check-kotlin.py) | Kotlin 侧：用另一份实现（正则）跟工具逐条对账 |

---

## 快速开始

### 构建

只需要 **JDK 21+**。仓库自带 Maven Wrapper，不用预装 Maven：

```bash
./mvnw package            # Windows: mvnw.cmd package
# 产物：target/spring-mcp-all.jar        ← 可运行
#      target/spring-mcp.jar            ← 不含依赖，仅本工程 class
```

首次执行会自动下载 Maven 发行版（约 10 MB）到 `~/.m2/wrapper`，之后离线可用。

跑测试（含 16 个 fixture 工程的端到端分析用例）：

```bash
./mvnw verify
```

### 配置到 MCP 客户端

用自带的启动脚本，客户端不需要知道 java 在哪、jar 在哪：

```json
{
  "mcpServers": {
    "spring-mcp": {
      "command": "C:/path/to/spring-mcp/spring-mcp.cmd",
      "args": ["--project=C:/work/my-spring-app"]
    }
  }
}
```

Linux / macOS 用 `spring-mcp.sh`。脚本会自动找 `JAVA_HOME`、找 jar（同目录或 `target/`），并统一加上 `-Dfile.encoding=UTF-8`；找不到 Java 时给出可操作的提示，而不是一句 `java: command not found`。

不想用脚本也可以直接写 java 命令：

```json
{ "command": "java", "args": ["-jar", "/abs/path/spring-mcp-all.jar", "--project=/abs/path/app"] }
```

`--project` 设默认工程根，之后调用工具时可以省略 `projectRoot`；一个会话里要切多个工程时，在工具参数里传 `projectRoot` 覆盖即可。

### 命令行参数

| 参数 | 说明 |
|---|---|
| `--project=<dir>` | 工程根目录；**可重复**，第一个是默认工程。别名默认取目录名 |
| `--project=<别名>:<dir>` | 同上，并把别名暴露给工具的 `project` 参数 |
| `--config=<file>` | 声明式扩展文件（默认在插件目录里找 `spring-mcp.yaml`/`.yml`/`.json`） |
| `--allow-external-commands` | 允许配置里声明的外部进程工具运行，**默认关闭** |
| `--plugins=<dir>` | 插件目录（默认 `<jar 所在目录>/plugins`，其次 `./plugins`） |
| `--include-tests` | 默认也扫描 `src/test/java` |
| `--max-indexed-files=<n>` | 单次索引的文件数上限（默认 20000），超限报 `INDEX_TOO_LARGE` |
| `--index-cache=on\|off` | 跨进程复用已建好的索引，默认 `on`（见下文「索引落盘」） |
| `--dev <cmd>` | 不进 MCP，直接跑离线命令行（见「离线命令行」） |
| `-h`, `--help` | 用法 |

环境变量：`SPRING_MCP_PLUGINS_DIR`、`SPRING_MCP_INDEX_REVALIDATE`（`stat` / `ttl` / `never`，默认 `stat`）、
`SPRING_MCP_INDEX_CACHE`（`on` / `off`，默认 `on`）。

配多个工程后，客户端一条命令就能查全部：

```json
{ "command": "spring-mcp.cmd",
  "args": ["--project=petclinic:C:/work/petclinic", "--project=api:C:/work/order-service"] }
```

### 进程生命周期

服务按 MCP 的 stdio 约定工作：stdin 收请求，stdout 只写 JSON-RPC。

| 事件 | 行为 |
|---|---|
| 客户端关闭 stdin | 0.5 s 内自行退出，退出码 0 |
| `SIGTERM` / `Ctrl-C` | 走 shutdown hook，关闭 server 与插件 classloader |
| 任何 `System.out` 输出 | 启动时就被重定向到 stderr，插件的 `println` 也污染不了协议通道 |

那 0.5 s 不是「等新请求」，而是留给「请求与关闭同批到达」的响应写完：`printf '<request>' | java -jar ...`
这种批量用法里，请求和 EOF 是一起到的，拆得太快会把还没写出去的响应一起带走（实测：拆得早时这条管道读到 0 字节）。
真正的客户端不会在调用中途关管道，所以它只影响批量脚本。

---

## 内置工具

十一个工具：九个做源码/配置分析，两个用来发现「能查哪些工程 / 工程里有哪些模块」。
工具清单（描述 + inputSchema）约 21 KB，每个会话付一次。

**选错工具比答错更隐蔽，所以它也被测过**：30 个真实中文问题，只给 `tools/list` 的原文、不给源码，
三个独立会话各答一遍——90/90 命中首选工具，三份答案逐个相同，两道「没有工具能回答」的陷阱题都答了 NONE。
三个语义相邻的「面」工具（controllers / gateway / feign）曾计划合并成一个 `analyze-surface` + `mode`，
合并版实测也是 90/90，但只省 15%（21 KB → 17.9 KB），且出现了 1 次「mode 与参数不匹配」的错误——
分开时这种错误在结构上不可能发生，所以没有合并。细节与复现脚本见 [`docs/TOOLSELECT.md`](docs/TOOLSELECT.md)。

所有分析工具接受两个互斥的工程参数：

- `projectRoot`：绝对路径
- `project`：别名（仅当启动时配了别名才出现在 schema 里）；省略则用默认工程

两者同时给会报 `INVALID_ARGUMENT`，而不是按某个隐含优先级猜。

**指向一个没有 Java/Kotlin 源码的目录会直接报错**，而不是返回「0 个端点」。传错路径（父目录、
前端目录、多模块工程的聚合模块）是这个工具最容易被用错的方式，而「0 个端点」和「这个服务确实
什么都不暴露」从外面看是同一句话——所以这里选了报错，并在 hint 里给出下一步（先 `list-modules`）。

九个分析工具还都接受 `scope`（限定模块，见下文 `list-modules` 与 `scope`）。

| 工具 | 回答什么 |
|---|---|
| `list-controllers` | 有哪些 HTTP 端点：路径、方法、handler、文件与行号 |
| `find-service-callers` | 谁在调这个 Service 方法（Java 取 AST，Kotlin 取 token） |
| `find-implementations` | 接口或抽象类的实现，含间接继承 |
| `list-mappers` | MyBatis 接口与 XML 的语句、JPA repository |
| `search-symbol` | 按名字找类 / 方法 / 字段 |
| `search-by-annotation` | 按注解找元素（默认按简单名匹配） |
| `get-file-dependencies` | 一个文件的双向依赖：它用到谁、谁用到它 |
| `list-gateway-routes` | 网关路由表：配置文件 + `RouteLocator` |
| `list-feign-clients` | Feign 客户端，以及它声明的接口 |
| `list-projects` | 配了哪些工程别名（不开索引，毫秒级） |
| `list-modules` | 工程里有哪些模块与模块间依赖（要开索引） |

### `list-projects`

列出通过 `--project` 配置的工程别名、路径、哪个是默认。**不打开索引**，所以在超大工程上也是毫秒级。

它存在的意义是让模型自己发现能力边界：没有它，每次对话都得让人报一遍绝对路径。

```json
{"projects":[{"alias":"petclinic","path":"...","isDefault":true,"exists":true}],"count":1,"default":"petclinic"}
```

未配置别名时返回空列表 + 说明，不报错。

`list-modules` 是它的姊妹工具，回答「这个工程里有哪些模块」；两者都只做能力发现，区别是后者要开索引。

### `list-modules` 与 `scope`：只看某几个模块

多模块仓库里，「这个端点在哪」常常等价于「哪个模块的端点」。`list-modules` 返回模块清单与模块间依赖，
`scope` 把它用到其他分析工具上：

```json
// 请求
{"name": "list-controllers", "arguments": {"scope": ["services/order"]}}

// 返回摘要
找到 21 个端点。7 个 Controller / 21 个 handler（作用域：仅 services/order）
```

规则与边界：

| 项 | 行为 |
|---|---|
| 值的含义 | 工程相对路径（`services/order`）。写上级目录（`services`）包含其下所有子模块 |
| 模块从哪来 | 只读构建文件：Maven `<modules>`、Gradle `include`；都没有时退化为「含 `src/main/java` 的目录」；**少于 2 个模块就当没有**，单模块工程不会出现 `scope` 语义 |
| 过滤范围 | `list-controllers` / `search-by-annotation` / `list-mappers` 过滤候选类型；`find-service-callers` 过滤调用点所在文件；`get-file-dependencies` **只过滤 incoming 与同包兄弟，outgoing 一律保留** |
| 跨模块解析 | 不受影响。索引始终覆盖整个工程，`scope` 只在结果组装时过滤 —— 所以 `scope=billing` 仍能解析到 `ordering` 里的 `OrderService` |
| 代价 | **不减少索引构建时间**。想减少耗时要缩 `projectRoot`，代价是丢跨模块解析 |
| 单模块工程传了 `scope` | 报 `INVALID_ARGUMENT` 并说明原因，不返回空结果 |

作用域一旦生效，摘要里带「作用域：…」、`warnings` 里也多一条，避免把「限定后的 0」误读成「工程里没有」。

### `search-symbol`

按名字查类型声明——最便宜也最常用的一步：读任何文件之前，先知道 `OrderController` 在哪个文件。

```json
{"symbols":[{"typeFqn":"com.acme.web.OrderController","kind":"CLASS","abstractType":false,
             "annotations":["RestController"],"file":".../OrderController.java","line":6}],
 "total":1,"truncated":false}
```

排序不是随便定的：**完全同名 → 前缀匹配 → 包含匹配**，同档内短名字在前。所以查 `OrderController`
时它自己排第一，`OrderControllerTest` 之类排在后面。

与全文搜索的区别是它只匹配**真实声明**：注释里、日志字符串里、名字叫 `orderController` 的变量都不会命中。
（这也正是 grep 最容易骗人的地方——搜不到正确的名字会让人得出"没有这个类"的结论。）

`kinds` 过滤只接受 `CLASS`/`INTERFACE`/`ENUM`/`RECORD`/`ANNOTATION`。schema 里带了 `enum`，写错的值会被
客户端/协议层直接拒绝（不是被当成"不过滤"——那会把笔误变成"返回全部"）。

### `list-controllers`

列出 `@Controller` / `@RestController` 暴露的 HTTP 端点。

| 参数 | 必填 | 说明 |
|---|---|---|
| `basePathFilter` | 否 | 只保留该前缀的路径 |
| `httpMethod` | 否 | `GET` / `POST` / `PUT` / `DELETE` / `PATCH` / `ANY` |
| `includeTests` | 否 | 是否扫描测试源码 |

类级前缀 × 方法路径 × HTTP 动词做笛卡尔积，一个 handler 映射多个 URL 时会拆成多条。

**继承的映射也算数**。父类/接口上的 `@RequestMapping` 前缀与 handler 方法都会被收集：

```java
@RestController @RequestMapping("/owners")
class OwnerController extends AbstractCrudController<Owner, Long> { }   // 4 个端点全部来自父类
```

此时 `file` / `line` 指向**声明处**（父类文件），`handlerDeclaredIn` 给出声明类型，并在 warnings 里
说明有几个 handler 是继承来的。修这条之前，这类端点是**整个消失且不报错**——`BaseCrudController<T>`
正是企业项目里最常见的写法。

**路径能算出来就给出真路径，算不出来才说算不出来**。`@RequestMapping(ApiConst.BASE)` 里的常量就写在
同一个工程里，工具会把它代入，并沿着下面这些写法一路折叠：

- `static final String` / 接口里的 `String BASE = "/x"`（隐式 `static final`）/ Kotlin 的 `const val`
- 常量定义在常量里：`API_FRONT = API + "/front"`，跨类、跨模块都跟，最多 8 跳，环形引用会停
- 嵌套类 `ApiConst.Support.PREFIX`、`import static` 的裸名字、同一类型里的兄弟常量（`@GetMapping(SUFFIX)`）
- 拼接：`"/a" + ApiConst.B`；Kotlin 的 `arrayOf("/a")` 也是路径

真正留不下来的只有三种，都会置 `pathResolved=false` 并给出 `unresolvedReason`：

| `unresolvedReason` | 含义 |
|---|---|
| `PLACEHOLDER` | 值里有 `${...}` 或 Kotlin 的 `$name` 模板，由配置/运行时决定 |
| `LITERAL_CONSTANT` | 引用了一个不是编译期常量的字段（`@Value` 注入、可被重新赋值） |
| `CONCATENATION` | 拼接里有算不出来的部分 |

110 个真实仓库里，258 条路径属于「常量就在同一个工程」这一类，单个仓库最高占 46%、有一个仓库是 100%。
不折叠它们的时候，这些端点全都以变量名的形式返回——看起来给了答案，其实什么都没说。

**条件装配会写在 `conditions` 里，但不求值**。`@Profile("dev")`、`@ConditionalOnProperty(...)`、
项目自己组合出来的 `@ConditionalOnEnable` 都不改变 URL，它们决定这个 handler **在运行的应用里存不存在**。
工具不去猜环境，而是把注解原文照抄进 `conditions`：

```json
{"path": "/debug", "conditions": ["@Profile(\"dev\")"]}
```

于是「这个接口线上为什么没有」这类问题有据可查，而不是被当成工具漏抓。110 个仓库里 99 个端点带条件
（0.74%），最集中的 `RuoYi-Vue-Plus` 有 61 个——它们的 `@ConditionalOnEnable` 实际是
`@ConditionalOnProperty("warm-flow.enabled", havingValue="true")` 的元注解，工具只报名字，不展开。

**不写 `@Controller` 的控制器也认**。有的项目注册自己的 `RequestMappingHandlerMapping`，控制器靠自定义标记注解
识别——`spring-boot-admin` 就是 `@AdminController` + `@ResponseBody`。规则是：一个类有方法级映射注解，并且类上有
`@ResponseBody`（`@RestController` 的另一半）或注解名以 `Controller` 结尾，就按控制器计入，同时在 warnings 里点名

```json
{"path": "/applications", "controllerFqn": "de.codecentric.boot.admin.server.web.ApplicationsController"}
```

这是推断，不是声明，所以它一定会出现在 `warnings` 里，并且这一条规则在 210 个实测仓库里只多认了 3 个仓库的 28 个端点。

### `find-service-callers`

给定 Service 类（可限定方法名），返回工程内所有**直接调用点**。

| 参数 | 必填 | 说明 |
|---|---|---|
| `serviceClass` | 是 | 简单名或全限定名 |
| `methodName` | 否 | 只查该方法的调用 |
| `includeSelfCalls` | 否 | 是否包含 Service 内部自调用，默认 true |
| `includeUnresolved` | 否 | 是否列出无法解析接收者类型的调用点，默认 true |
| `includeLowConfidence` | 否 | 是否启用按变量名推断的兜底，默认 **false** |
| `limit` | 否 | 调用点上限，默认 500 |

每条结果都带 `rule`（命中依据）与 `confidence`（置信度）：

| rule | 含义 | 可信度 |
|---|---|---|
| `FIELD` / `SUPER_FIELD` | 接收者是本类/父类字段 | 高 |
| `CONSTRUCTOR_PARAM` / `METHOD_PARAM` | 接收者是构造器/方法形参（覆盖 setter 注入） | 高 |
| `LOCAL_VAR` / `LOOP_VARIABLE` | 接收者是局部变量 / 循环变量 | 高 |
| `STATIC_CLASS` / `STATIC_IMPORT` | 静态调用 | 高 |
| `IMPLICIT_THIS` | 无接收者的本类调用 | 高 |
| `NAME_CONVENTION` | 由变量名猜类型（`orderService` → `OrderService`） | **低，默认关闭** |

**Java 与 Kotlin 都扫**。Java 走 AST；Kotlin 没有 AST，用同一套接收者规则在 token 流上重建：
局部 `val`（含 `val x = Foo()` 这种构造器推断）→ 本类属性 → 方法形参 → 类名（`Foo.bar()`），
命中哪条规则就报哪条，和 Java 侧一一对应。Kotlin 里构造函数参数写成 `private val x: T` 就是属性，
所以 `FIELD` 覆盖了 Kotlin 最常用的构造器注入。

链式调用 `getX().y()`、方法引用、lambda 形参在两种语言上都不解析，进入 `unresolved` 并给出原因。
Kotlin 额外两条边界：`apply { ... }` / `run { ... }` 里的隐式接收者是 lambda 接收者而不是 `this`，
这类调用会被当成 `IMPLICIT_THIS`；泛型实参无法推断（没有类型环境）。两者都不猜，只影响 `unresolved` 的完整性。

默认只返回**直接调用点**。传 `depth=2` 会再向上展开一层——对第一层出现的每个调用方类型，查「谁调用了它」：

```json
{"target":"...LeafService","depth":2,
 "direct":   {"callSites":[{"callerType":"...MiddleService"}]},
 "upstream": [{"callerType":"...TopController"}],
 "expandedTypes":1,"callerTypes":1}
```

`depth=2` **是近似**：第二层是「调用了第一层类型的方法」，无法限定到具体方法，可能包含与该目标无关的调用。
它给的是影响面**边界**，不是精确清单，所以两个层级在结构上分开，摘要与 warnings 都会写明。
展开类型数受 `maxUpstreamTypes`（默认 20）限制，超出部分在 warnings 里说明。

### `find-implementations`

给定接口或类，返回工程内所有实现/继承它的类型，标注是否为 Spring Bean、是直接实现还是经由父类。

```json
{"targetFqn":"com.acme.pay.PaymentGateway","targetKind":"INTERFACE",
 "implementations":[
   {"typeFqn":"com.acme.pay.CardGateway","direct":false,"springStereotypes":["Service"]},
   {"typeFqn":"com.acme.pay.CashGateway","direct":true, "springStereotypes":["Component"]}],
 "count":2,
 "warnings":["另有 1 个抽象类实现该类型，设 includeAbstract=true 可列出"]}
```

**默认不含抽象类与子接口**：抽象基类不能注册成 Bean，混进「实现类」会让答案看起来比实际多。
需要时传 `includeAbstract` / `includeInterfaces`。

为什么需要它：`grep "implements PaymentGateway"` 只能找到直接实现、且类名往往与接口无关；
`CardGateway extends AbstractGateway` 这种经由抽象基类的实现，文本搜索完全找不到。

### `list-mappers`

合并三类持久层对象：

- `ANNOTATION`：接口形式的 mapper，三种声明方式都认 ——
  `@Mapper` 注解、`extends BaseMapper<T>`（MyBatis-Plus，实体类型从泛型取）、以及
  `@MapperScan` 覆盖的包（`basePackages` 或 `value`，裸 `@MapperScan` 按注解所在包算）。
  包值里的通配符按 MyBatis 的 Ant 规则解析：`**` 跨任意层、`*` 跨一层，
  所以 `com.ruoyi.**.mapper` 能覆盖 `com.ruoyi.system.mapper`。
  只有第一种时，`mall` 的 76 个 mapper 会一个都认不出来；不支持通配符时，`ruoyi` 的 21 个会全部变成孤儿
- `XML`：`resources` 下的 MyBatis XML，按 `namespace` 关联到接口，抽取 statement、`<sql>` 片段、`<include>` 引用；找不到接口的进 `orphans`。
  发现深度上限 24 层（不是 8）：`<module>/src/main/resources/<package>/X.xml` 在第 9 层，早期版本把它整片漏掉
- `JPA`：继承 `JpaRepository` / `MongoRepository` 等 Spring Data 接口的，附带泛型推断出的实体类型与主键类型

同一个 mapper 的接口侧与 XML 侧会合并成一条记录。`entityType` 只报能确定的：`BaseMapper<T>` 的 `T`
是类型变量时返回 `null`，而不是把 `"T"` 当成实体名。

**实体类型会沿继承链传导**。`interface CoinDao extends BaseDao<Coin>` 配
`interface BaseDao<T> extends JpaRepository<T, Long>` 是团队共享 CRUD 的常见写法，实体写在泛型实参里。
工具按声明位置的类型参数逐层代入（`BaseDao<T, ID>` 这种两个参数、跨包 import、`AuditedDao<T> extends
BaseDao<T>` 这种两级链都能走），每一步都用声明文件自己的 import 上下文解析父类型名。链上有一环
不在索引里、或者名字有歧义，就停在「不知道」——不猜。

### `list-gateway-routes`

微服务工程里，「代码里有什么端点」和「客户端能调到什么」是两套 URL：网关通常加前缀再 `StripPrefix`，
所以服务内部的 `/brand/list` 对外是 `/mall-admin/brand/list`。这张路由表要么在配置里，要么在代码里。

返回每条路由的 `id`、`uri`、`serviceId`、`predicates`、`filters`，另外两个字段是为这层映射单独算的：

| 字段 | 含义 |
|---|---|
| `pathPrefixes` | 从 `Path=` 断言里取出的路径前缀，如 `/mall-admin/**` |
| `stripPrefix` | `StripPrefix=N` 的 N；客户端路径去掉前 N 段就是服务内部路径 |
| `targetModule` | `lb://name` 解析到本工程的哪个模块；绝对地址或外部服务为 `null` |
| `file` | 路由声明在哪个配置文件里（工程相对路径） |

三种属性路径都读，因为它在版本间搬过家：`spring.cloud.gateway.routes`（3.x）、
`spring.cloud.gateway.server.webflux.routes`（4.x，mall-swarm 用的就是它）、`…server.webmvc.routes`。

**Maven 过滤符不会让整份配置作废**：`@project.version@` 在打包时被 Maven 替换，本身不是合法 YAML
（plain scalar 不能以 `@` 开头）。读文件时先把它改写成 `${project.version}`——语义不变，但解析器接受。

**代码里的 `RouteLocator` bean 也读**，两种写法都认：

```java
return builder.routes()
    .route("code-orders", r -> r.path("/code/orders/**")
        .filters(f -> f.stripPrefix(2))
        .uri("lb://order-service"));
```

```kotlin
fun routes(builder: RouteLocatorBuilder): RouteLocator = builder.routes {
    route("kt-notify") {
        path("/kt/notify/**")
        filters { stripPrefix(1) }
        uri("lb://notify-service")
    }
}
```

代码路由与配置路由进同一张表，`file` 指向声明处，`targetModule` 同样按 `lb://` 解析。
`uri` 是变量时，同一文件里的**字面量**会被代入（`String url = "..."; .uri(url)`）；
被重新赋过值的变量不代入，`@Value` 注入的字段也不代入——这两种情况下值在运行时才确定，
按变量名原样返回并计入 warning 比猜一个 URL 好。

三条边界，都会明确说出来而不是返回空结果：

- **`uri` 不是字面量**：变量（不可代入）或字符串拼接，原样返回并计数提示
- **配置里有 `spring.cloud.gateway` 但读不到 routes、也没有代码路由**：直接说没找到，而不是返回空表
- **`discovery.locator.enabled=true`**：还有 `/{serviceId}/**` 形式的隐式路由，只做提示、不展开

`scope` 按 `targetModule` 过滤，但**指向工程外的路由会被保留**——作用域说的是本仓库的模块，
一条指向外部服务的路由不属于「超出范围」。

### `list-feign-clients`

前一个工具说的是「外部能调进来什么」，这个说的是**「本模块调了谁」**——出站的那半条边。

`@FeignClient("mall-admin")` 加接口方法上的映射注解，就是一份完整的出站契约：

| 字段 | 含义 |
|---|---|
| `name` | 目标服务名：注解的 `name`/`value`，只有 `url` 时用 URL |
| `pathPrefix` | `@FeignClient(path="/api/notify")`，接口方法路径的前缀 |
| `targetModule` | 服务名对应到本工程的哪个模块；指向外部服务时为 `null` |
| `endpoints[].httpMethods` | 方法映射注解推出的动词；`@RequestMapping` 不带 method 时是 `ANY` |
| `endpoints[].pathResolved` | 路径是否为字面量（常量/拼接时为 `false`，原文返回） |

mall-swarm 上的实测：5 个客户端 → `mall-admin`×2、`mall-portal`×2、`mall-search`×1，
加网关的 5 条入站路由，同一份仓库的两侧边就都有了。

两类情况会明说，而不是给个空结果：方法上没有映射注解的客户端（「无法判断它调用哪些路径」），
以及 `RestTemplate`/`RestClient` 这类没有声明式契约的调用——**后者不解析**，见「明确不支持」。

### `search-by-annotation`

搜索被指定注解标注的类/方法/构造器/字段/参数。

默认按**简单名**匹配（查 `Transactional` 能命中带 import 的写法），可能命中同名自定义注解，结果里会带 warning；需要精确匹配时传全限定名并设 `strictFqn=true`。

**不展开元注解**：查 `@RequestMapping` 不会返回 `@GetMapping` 标注的方法。

### `get-file-dependencies`

给一个 `.java` 文件，返回双向依赖：

- `outgoing`：import、import static、通配 import、同包引用、类型使用，区分工程内/外部
- `incoming`：**既看 import，也看同包内未 import 的直接引用**——Spring 工程同包耦合极重，只统计 import 会得出"没人依赖它"的错误结论

`java.lang.*` 被过滤掉（隐式导入，不是真实耦合）。

---

## 结果与错误格式

成功：

```json
{
  "ok": true,
  "data": { "...": "各工具的领域结果" },
  "warnings": ["1 个文件存在解析问题，结果可能不完整"]
}
```

失败（`isError: true`，同时给机器可读的 code 与人可读的文本）：

```json
{
  "ok": false,
  "error": {
    "code": "CLASS_NOT_FOUND",
    "message": "找不到类: Ghost",
    "hint": "确认类名拼写，或提供全限定名"
  }
}
```

错误码：`INVALID_ARGUMENT`、`PATH_NOT_FOUND`、`PATH_NOT_DIRECTORY`、`PATH_NOT_READABLE`、`NOT_A_SOURCE_FILE`、`CLASS_NOT_FOUND`、`AMBIGUOUS_CLASS`、`INDEX_BUILD_FAILED`、`INDEX_TOO_LARGE`、`PARSE_ERROR`、`XML_PARSE_ERROR`、`TIMEOUT`、`PLUGIN_ERROR`、`INTERNAL_ERROR`。

**任何异常都不会穿透到协议层**：插件抛 `RuntimeException`、解析器抛 `Error` 的意外分支，都会被转成一条格式正确的错误结果，服务继续存活。

---

## 离线命令行

不经过 MCP 协议，直接跑同一次分析，用来分清是分析错了还是客户端错了。

怀疑结果不对时，先用同样的查询走离线通道，一眼看出是分析层还是协议层的问题：

```bash
J="java -jar target/spring-mcp-all.jar"

$J --dev index-info  --root fixtures/demo-web
$J --dev controllers --root fixtures/demo-web --http-method GET
$J --dev callers     --root fixtures/layered-callers --service OrderService --method create
$J --dev mappers     --root fixtures/mybatis-xml --kinds ANNOTATION,XML
$J --dev annotations --root fixtures/demo-web --annotation RestController
$J --dev deps        --root fixtures/layered-callers --file src/main/java/com/acme/order/OrderService.java
$J --dev symbols     --root fixtures/demo-web --name Order
$J --dev implementations --root fixtures/type-hierarchy --type PaymentGateway
$J --dev gateway     --root fixtures/gateway
$J --dev modules     --root fixtures/multi-module

# 任何子命令都可以加 --scope 试作用域，逗号分隔
$J --dev controllers --root fixtures/multi-module --scope billing

# 怀疑磁盘缓存导致结果不一致时，关掉它再跑一遍
$J --index-cache=off --dev controllers --root /path/to/project
```

注意：`--index-cache` / `--project` / `--plugins` 这类**服务级参数必须写在 `--dev` 之前**。
`--dev` 之后的一切都原样转给离线命令，因此 `--dev controllers --root x --index-cache=off` 里的
`--index-cache` 会被当成子命令参数而静默忽略。

输出是 UTF-8 的 JSON，可以直接管道给 `jq`。参数支持 `--key value` 和 `--key=value` 两种写法。

### 两条通道，别用错

`--dev` 绕过工具层直接调索引，所以它能回答「是不是分析算错了」；但它**不能**证明「客户端调用会被正确处理」。

| 通道 | 走哪一层 | 用来回答 |
|---|---|---|
| `--dev <cmd> --root ...` | 直接调索引 | 分析结果对不对 |
| `bash tools/corpus/mcp-tool.sh <tool> k=v ...` | 真实的工具层（registry / plugin / `ToolInvocation`，参数名与 `tools/list` 一致） | 客户端看到的那一层对不对 |

```bash
bash tools/corpus/mcp-tool.sh list-controllers projectRoot=fixtures/demo-web
bash tools/corpus/mcp-tool.sh find-service-callers projectRoot=fixtures/layered-callers service=OrderService
```

`docs/E2E.md` 和 `docs/GENERALIZATION.md` 里那些「冷启动会话只给工具不给源码」的实测，走的就是第二条——
测量必须在被测的那一层上做。脚本用源码模式启动（`java McpTool.java`），单次查询方便，批量跑请用
`tools/corpus/check.sh`。

### 回归基线

在九个真实仓库上冻结分析结果，用来证明「改一处没把别处改坏」：

| 仓库 | 用途 |
|---|---|
| `spring-petclinic` | Maven 单模块，端点/调用点全量覆盖 |
| `spring-boot` | Maven 40+ 模块，同时充当性能基座 |
| `mybatis-spring-boot` | MyBatis 技术栈 |
| `spring-security` | Gradle 多模块，压模块推导 |
| `mybatis-plus-samples` | MyBatis-Plus：`@MapperScan` + `BaseMapper<T>`，抓 mapper 漏检 |
| `mall` | 524 文件的真实电商项目：76 个 mapper 全无 `@Mapper`、106 个 XML、Lombok |
| `ruoyi` | 285 文件的国产后台框架：`@MapperScan("com.ruoyi.**.mapper")` 通配符包、Shiro、Thymeleaf |
| `mall-swarm` | Spring Cloud Alibaba：Nacos + Gateway + Feign，唯一带真实网关路由表的样本 |

`mybatis-plus-samples` 是后加的：它第一次跑就暴露出「只认 `@Mapper` 注解」只认出 12 个 mapper
（实际 38 个，其中 32 个能从 `BaseMapper<T>` 推断出实体类型），以及 `@RequestMapping("/")` 被拼成 `//test`。
加进基线后这两类缺陷再出现会立刻变红。

`mall` 是第二个后加的，代价最高也最值：它 76 个 mapper 接口**一个 `@Mapper` 都没有**，全靠
`@MapperScan({"com.macro.mall.mapper","com.macro.mall.dao"})`；106 个 mapper XML 里有 76 个落在第 9 层目录，
而当时的发现深度上限是 8 —— 结果 76 个 XML 连同约 1100 条 SQL 语句一起消失，且不产生任何 orphan 或警告。
基线把它固定下来：**修复前 28 个 XML / 50 条语句，修复后 104 个 / 1142 条**。

`ruoyi` 是第三个后加的：它的 `@MapperScan("com.ruoyi.**.mapper")` 带通配符，而包匹配当时只做前缀比较，
于是 21 个 mapper 接口一个都没认出来、21 个 XML 全部进了 `orphans`——一个看起来像"这个项目的 XML 没有对应接口"
的错误结论。修完是 **21 个接口全部识别、21 个 XML 全部挂上、0 orphan**。

仓库按 `fixtures/baseline/manifest.json` 里**固定的 commit** 浅克隆到 `.baseline/`（已加 `.gitignore`；放在 `target/` 里会被 `mvn clean` 删掉，每次重下 149 MB），首次运行会下载几百 MB。

```bash
# 生成/更新基线（改动是刻意的才执行）
java -jar target/spring-mcp-all.jar --dev baseline --repo=spring-petclinic --regenerate

# 只比对
SPRING_MCP_BASELINE=true ./mvnw test -Dtest=BaselineTest
```

golden 只存 `digest` + 数组字段名 + 条数 + 首尾各 20 条样本，完整输出在 spring-boot 上会有几十 MB，不入库。比较前会规范化：绝对路径替换成 `<root>`、剔除耗时与 generation、数组排序 —— **但保留行号与列号**，因为 commit 固定后它们漂移就是真实的行为变化。

### 真实仓库语料

基线固定 8 个仓库、比对完整 JSON；语料固定 11 个仓库、只比对五个数字——
它回答的是"改完之后，真实项目上数出来的东西有没有变"，5 分钟内能跑完。

仓库与 **commit** 钉在 `tools/corpus/repos.tsv`（跟 HEAD 走的话，上游随便一次提交都会让它变红，
而它要回答的不是那个问题），期望值在 `tools/corpus/expected.json`。

```bash
bash tools/corpus/check.sh --jar target/spring-mcp-all.jar          # 按 commit 拉到 .corpus/ 再比对
bash tools/corpus/check.sh --jar target/spring-mcp-all.jar --update  # 期望值变了，且确认是故意的
```

覆盖的形态：注解端点、函数式路由、MyBatis 接口与 XML、Feign、网关、多模块，
外加两个边界——`mybatis-3`（一个 HTTP 端点都没有）和 `solon`（非 Spring 框架，67 个控制器/0 个端点）。
CI 里的 `corpus` 作业跑的就是它。

基线测试默认跳过（要环境的 `SPRING_MCP_BASELINE=true`），普通 `./mvnw verify` 不受影响。

---

## CI 与发版

`.github/workflows/ci.yml` 三个作业，失败的含义互不重叠：

| 作业 | 跑什么 | 为什么单列 |
|---|---|---|
| `build` | `clean verify` + 零编译警告门禁 + fat jar 协议冒烟 | 代码本身。冒烟用管道喂一个 `initialize`，覆盖 shade 之后 `META-INF/services` 是否还完整，以及进程会不会退出 |
| `windows` | 同样的 `verify` + 启动脚本冒烟 | 本工具的主要使用平台：路径分隔符、CRLF 批处理、长路径只有在这里才被验证 |
| `baseline` | 8 个真实仓库的 golden 比对 | 慢且要联网，所以与主构建并行、单独判定 |

零警告门禁就是一行 grep：日志里出现 `[WARNING] ... .java` 即失败。这类警告（deprecation / unchecked）正是 SDK
变更的早期信号，混在几千行日志里没人看得见，所以让它变成红灯比写在规范里有用。

`baseline` 作业把 `.baseline/` 缓存起来，key 里含 `fixtures/baseline/manifest.json` 的哈希 —— 换 commit 就换 key，
自动重新克隆。冷跑约 3–4 分钟（下载 150 MB），命中缓存后一分多钟。

### 发版

```bash
# pom 的 <version> 是版本的唯一来源；v 前缀之外必须与它一致（-SNAPSHOT 会被去掉）
git tag v0.1.0 && git push origin v0.1.0
```

`release.yml` 先校验 tag 与 `pom.xml` 的版本（`v0.1.0` ↔ `0.1.0-SNAPSHOT`），不一致就失败并说明该改哪一边；
然后把构建版本改成 `0.1.0` 跑全量测试，再问 jar 自己报的版本是不是 `0.1.0`，最后把 `spring-mcp-all.jar`
与一个含 jar + 两个启动脚本的 `spring-mcp.zip` 挂到 Release。

三点需要知道：

- 报给客户端的版本号来自 `pom.xml` 的 `<version>`，经 shade 写进 jar manifest 的 `Implementation-Version`，
  由 `SpringMcpMain.VERSION` 在运行时读出来——**代码里没有第二个版本号可以漂移**
- 两者不一致时发版会红，而不是挑一个悄悄用
- 基线不在发版流程里跑（联网 + 分钟级），它是 CI 的职责

### 换行符

`.gitattributes` 固定了两条会真的坏事的规则，不依赖每个人的 `core.autocrlf`：

| 文件 | 必须 | 否则 |
|---|---|---|
| `*.cmd` | CRLF | `cmd.exe` 把脚本拆成 `'he'`、`'xist'` 之类碎片 |
| `mvnw` / `*.sh` | LF | Linux 上报 `bad interpreter: /bin/sh^M` |

---

## 读不出来的东西

这些是刻意的边界，不是遗漏。碰到时不猜，而是返回原文并标记未解析——**一个看起来完整、实际不对的答案，
比一个明说不知道的答案更糟**。分两类：根本不读的，和读了但只到某一层的。

### 根本不读

- **动态 Bean**：`@Bean` 方法的返回类型、`FactoryBean`、`@Profile` 分支。条件装配本身不求解（没有活动环境），
  但落在 controller / handler 上的 `@Profile` 与 `@Conditional*` 会逐条写进端点的 `conditions` 字段
- **非 HTTP 映射**：RSocket 的 `@MessageMapping`、GraphQL 的 `@SchemaMapping` 不产生端点。它们所在的
  `@Controller` 会被点名列出（「N 个 controller 没有任何 HTTP 映射」），不会被当成空 controller。
  数量很大时（≥10 且占比过半）会再加一句：也可能这批类用的是别的 Web 框架——Solon 同样用 `@Controller`
  标注、映射却用它的 `@Mapping`，本工具不认，于是看起来像「有控制器、没端点」
- **无声明式契约的远程调用**：`RestTemplate` / `RestClient` / `WebClient` 的 URL 在调用点拼装，静态还原不了，
  因此不进 `list-feign-clients`；只有 `@FeignClient` 这类接口式契约能被读出
- **AOP 代理**：`@Transactional` / `@Cacheable` 织入后的实际调用目标
- **SpEL 与 `@Value` 表达式**
- **后缀映射**：`web.xml` 里的 `*.do` / `*.action`——只认注解与 `RouterFunctions`
- **starter 注册的端点**：actuator、captcha-plus 这类库自动注册的路径不在源码里
- **元注解推导**：`@GetMapping` 不会被当成 `@RequestMapping`，`@MyRestController` 这类组合注解也不会被认出
- **跨工程解析**：一次分析一个目录。仓库之间的引用永远算「外部」，哪怕它们在同一个 Maven reactor 里
- **每个模块独立的 classpath**：不做依赖冲突仲裁，同名类在多个模块里只有一个解析结果
- **两层以上的调用链**：`depth` 上限是 2；更深的传递闭包需要类型级调用图
- **泛型推断**：没有 symbol solver，接收者类型只从语法声明推断

### 读，但只到某一层

- **常量与占位符**：`${api.prefix}`、`@Value` 注入的值不求值，包括 `${x:默认值}` 这种写法（默认值只是
  「属性没被覆盖时」的值）。编译期常量会求值，但常量本身若是 `System.getenv(...)` 之类的调用，仍然不知道
- **`RouterFunctions` 的非字面量谓词**：`route(this::acceptsXml, handler)` 没有路径可报，会计数提示「未展开」
- **`nest(...)` 的跨类前缀**：同文件内的字面量前缀会拼上；前缀由另一个类在注册时套上（halo 的
  `CustomEndpointsBuilder` 就是），只能告警说这层前缀存在且未应用，不替你把路径拼出一个大概
- **全局路径前缀**：`setPathPrefixes` / `configurePathMatch` 加的前缀来自配置，工具报的是注解里的路径，
  并在 warnings 里点名这类配置出现在哪
- **Kotlin**：`.kt` **会解析**（类/接口/对象、函数、属性、注解、父类型，含嵌套与 `companion object`），
  但只到声明层。`val repo = findRepo()` 这种由表达式决定类型的地方留空；`find-service-callers` 也读 Kotlin，
  接收者按「局部 `val` → 字段 → 方法参数 → 类名」定型，定不了型的进 `unresolved`
- **MyBatis 动态 SQL**：`<if>` / `<foreach>` 不展开，只记录 statement 元信息
- **mapper XML 的发现深度**：上限 24 层。`mall` 那种 9 层是常态，设上限是为了防病态目录树；
  超过深度的 XML 不会被发现，也不会有警告
- **`--include-tests` 为 false 时**，`*/src/test/resources` 下的 mapper XML 也不扫（否则会出现
  namespace 指向测试类、接口又没被索引的 XML-only 记录）
- **代码定义的网关路由**：`RouteLocator` 的 Java DSL 与 Kotlin DSL 会解析，识别不了的 `uri` 按原文返回并计数；
  配置文件里读不到路由时会明说，而不是给出「没有网关」的结论
- **网关的鉴权与限流规则**：`secure.ignore.urls` 这类业务白名单不解析——它属于配置语义，不是路由表
- **模块级索引裁剪**：`scope` 只过滤结果，索引始终是全仓库。想少解析就把 `projectRoot` 指向子目录，
  代价是丢跨模块解析
- **`.kts` 不算源码**：构建脚本不是应用代码，收进来只会夸大工程规模

碰到 `unresolved` 或 `pathResolved=false` 时，正确做法是让调用方自己去看那个文件——这比给出一个自信的错误答案好。

---

## 环境备忘

本机开发环境（仅供参考）：

- JDK：`D:\tools\re\jdk-21`（Temurin 21.0.12.1）
- Maven：`D:\tools\apache-maven-3.9.16`，于 `%USERPROFILE%\.m2\settings.xml` 配置了阿里云镜像
- 依赖版本：`mcp-bom 2.1.0`、`javaparser-core 3.28.2`、`jackson-dataformat-yaml 2.21.1`、JUnit 5.11.4、AssertJ 3.26.3
- 索引缓存目录：`%LOCALAPPDATA%\spring-mcp\index`（一个工程一个 36 MB 左右的 `index.json`；
  整个目录超过 1 GiB 时，下一次成功写缓存后按最久未构建的顺序自动回收，也可以直接删目录）

---

## 许可

Apache License 2.0，全文见 [`LICENSE`](LICENSE)。

选它是因为这份代码的用法：被公司引进、被第三方写插件、被打包再分发，都需要一条显式的专利授权；
MIT 没有这一条。想换成 MIT 的话，替换 `LICENSE` 和 `pom.xml` 里的 `<licenses>` 即可，代码里没有
任何许可相关内容。

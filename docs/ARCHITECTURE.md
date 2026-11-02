# 架构笔记

> 讲内部构造，给要改这份代码的人。用法与边界见 [README](../README.md)。

## 工程结构

**一个 Maven 模块、一个 jar**。包按职责分层，不拆子模块：

```
spring-mcp/
├── pom.xml
├── .github/workflows/                 ci.yml（构建/Windows/基线）、release.yml（打 tag 发布）
├── .gitattributes                     固定 .cmd 用 CRLF、脚本用 LF
├── fixtures/                          16 个 fixture 工程 + baseline/，供测试复用
├── src/main/java/io/github/springmcp/
│   ├── spi/                           插件 API + 领域 DTO
│   │   ├── api/                       CoreAnalyzer / IndexHandle / IndexOptions
│   │   └── model/                     Controllers / ServiceCalls / Mappers / ...
│   ├── core/                          索引、JavaParser + Kotlin 解析、分析器
│   │   ├── index/                     SourceWalker / CuSummaryBuilder / SourceIndex / IndexRegistry
│   │   │                              ModuleLayout / ScopedIndexView（模块归属与作用域视图）
│   │   ├── kotlin/                    KotlinLexer / KotlinSummaryBuilder（产出与 Java 同构的 CuSummary）
│   │   │                              KotlinRouteScanner（Kotlin DSL 网关路由）
│   │   ├── parse/                     JavaParserFactory / AstCache
│   │   ├── resolve/                   TypeNameResolver / SuperTypeGraph
│   │   ├── cache/                     IndexCacheStore / CacheEnvelope / IndexCacheFormat
│   │   ├── analyze/                   分析器 + ReceiverResolver / GatewayRouteAnalyzer / RouteLocatorAnalyzer
│   │   └── util/                      PathJoiner / AnnotationNames / AstNodes
│   └── server/                        MCP 装配
│       ├── ProjectAlias / ProjectCatalog   工程别名与查找
│       ├── ProjectRoots               空目录守卫（工具层与 --dev 共用同一份）
│       ├── builtin/                   九个分析工具 + list-projects / list-modules（实现 SPI，与第三方插件同一条路径）
│       ├── mcp/                       ToolRegistrar / ToolResults
│       ├── plugin/                    PluginLoader / DefaultPluginContext
│       ├── transport/                 StdioTransportFactory / StdoutGuard / EofAwareInputStream
│       ├── declarative/               ConfigLoader / DeclarativeTools（配置文件定义工具，不用写 Java）
│       ├── external/                  ExternalToolProvider（把任意命令行程序包成工具）
│       └── dev/                       DevCli / BaselineSpec / BaselineNormaliser / BaselineRunner
├── src/test/java/io/github/springmcp/
│   ├── example/ExamplePlugin.java     插件模板（编译进测试、不打包，保证示例不腐烂）
│   ├── baseline/BaselineTest.java     九个真实仓库的回归基线（默认跳过）
│   └── server/StdioProtocolTest.java  起真进程走 stdio：工具清单、错误信封、关闭即退出
└── src/main/resources/simplelogger.properties
```

依赖方向仍然是单向的 `server → core → spi`，只是现在由**包边界 + 代码评审**保证，不再是编译器强制。

### 关于 `spi` 包的约束

`io.github.springmcp.spi` 下的类型仍然是插件作者唯一的编译依赖，规矩没变：

- **只允许 JDK 类型**。不给 MCP SDK、Jackson、JavaParser 类型出现在签名里
- 泄漏 MCP SDK → 插件被锁死在一个 SDK 版本
- 泄漏 **JavaParser** 更糟 → `CompilationUnit` 一旦进接口，SPI 就和 AST 库的内部结构绑定，跨 classloader 还会 `ClassCastException`

需要原始 AST 的插件自己依赖 JavaParser，通过 `PluginContext.analyzer().readSource(path)` 拿源码重新解析。

拆模块时这条约束由 `maven-enforcer-plugin` 在构建期强制；合成单模块后只能靠约定——**新增 `spi` 包下的类时请自行检查 import**。这是换取「一个 pom 一个 jar」的代价。

### 索引设计

两层缓存，是整套东西能在真实工程上跑得动的前提：

```
SourceIndex（不可变快照）
├── byFile     : Map<Path, CuSummary>   ← 常驻，每文件几 KB（AST 提炼完立即丢弃）
├── byFqn / bySimpleName / byAnnotation ← 派生索引，全不可变
├── SuperTypeGraph                      ← 超类型闭包，带环检测
└── AstCache                            ← 有界 LRU(512)，可丢可重建
```

九个分析工具里只有 `find-service-callers` 需要方法体（Java 取 AST，Kotlin 取 token），其余八个用摘要就能回答。
而它先用 `calledMethodNames` 预过滤，把候选文件从几千缩到几十分之一，再取方法体：spring-boot 上查 `Environment`
（命中面很大）全流程 0.9 s。

并发：读路径零锁（快照不可变 + `AtomicReference`），只有首次构建或检测到变更时才在每根目录的 `ReentrantLock` 内重建，并通过 generation 比较避免重复重建。

变更检测：默认 `stat` 模式重新遍历比对 `(size, mtime)`，2 秒节流；有变化就全量重建（几千文件 1–3 秒，做增量 diff 不划算）。

模块归属在解析阶段顺带算好，存在 `CuSummary` 上（每文件一份，不是每类型一份）：`ModuleLayout` 只读构建文件，
把 849 个模块的 spring-boot 也压到几十毫秒。作用域是**查询期**概念——`IndexOptions` 里没有模块字段，
所以切换 scope 不会触发重建；`SourceIndex.scoped(...)` 返回一个只过滤结果的视图，`byFqn` / 类型解析 / 超类型图全部保持全量。

### 读源码：编码与排除目录

文件按「严格 UTF-8 → 严格 GB18030 → 带替换的 UTF-8」三级解码，BOM 会被剥掉。用哪一级不是秘密：
索引的 `warnings` 会写「57 个文件不是 UTF-8（GB18030 57），已按探测到的编码解码；例如 XxxDao.java」。
不这么做的话，一个 GBK 文件会在解析阶段失败、整个文件从索引里消失——110 个真实仓库里有一家的 60%
源码是 GBK，工具对它的回答曾经是「没有端点」。

排除目录除了 `target` / `build` / `node_modules` 这些构建产物，还包括 `testdata`、`test-data`、
`testFixtures`。这些目录放的是**故意写坏**的样本（解析器、IDE 插件的测试语料），收进来会凭空造出
应用里不存在的 controller 和 Feign 客户端：那家有 339 个测试夹具的仓库，曾因此多报 7 个
`@FeignClient`、12 条「解析失败」。

### 索引落盘：冷启动从 8 秒降到 1 秒

进程内缓存只活到进程退出。MCP 客户端往往**每轮对话重启一次 server**，于是每次都要重新解析整仓库。
`core/cache` 把 `CuSummary` 列表 + 元数据序列化到用户缓存目录，下次启动直接读回：

| 步骤 | spring-boot（8 413 文件 / 14 155 类型） |
|---|---|
| 解析构建 | 8.3 s |
| 写缓存（36 MB JSON） | 0.19 s |
| 读缓存 | 0.25 s |
| 重新指纹校验（必须做，否则不知道缓存是否过期） | 0.75 s |

实测冷启动（含 JVM 启动与进程退出）：**10.1 s → 2.0 s**，查询本身 2.1 s。
小仓库收益小（petclinic 1.20 s → 0.74 s，JVM 启动占大头），大仓库收益接近 5 倍。

**编辑循环另走「按文件复用」**：指纹不匹配时不做全量重解析，而是按 `(path, size, mtime)` 复用未变文件的摘要，
只解析改动的那几个，派生索引仍从全量摘要重建（所以跨文件解析不受影响）。摘要是单文件内容的纯函数，
复用与重解析结果必然相同。

| 场景（spring-boot，8 413 文件） | 全量 | 复用后 |
|---|---|---|
| 改 1 个文件后，同进程再次提问 | 10.7 s | **1.3 s** |
| 改 1 个文件后，新进程首个提问 | 10.7 s | **3.1 s** |
| 没有改动，新进程首个提问 | 10.7 s | **2.0 s** |

不复用「上一个快照都没有、磁盘缓存也没有」的首次构建——那次必须全量解析，没有可省的东西。

**失效用五道关卡，全部命中才读，任何一道不符就重建**：

| 关卡 | 防止什么 |
|---|---|
| 格式版本 | 存储布局变化（改了 `CuSummary`/`TypeSummary` 字段就必须 bump，守卫测试会拦） |
| 构建戳（jar/classes 的修改时间） | 升级 spring-mcp 后沿用旧分析器结果——这一条最关键，源码指纹发现不了代码变了 |
| 工程根路径 | 同一 hash 目录被复用到别的仓库 |
| 构建选项 | `--include-tests` 等改变了索引内容 |
| **全量文件指纹（size + mtime）** | 源码变化。不抽样：抽样漏检的后果是给出过期答案 |

另外几条硬约束：

- **缓存在用户目录**（`%LOCALAPPDATA%\spring-mcp\index`），绝不写进被分析的仓库——这工具只读别人的代码，写进去既污染工作区，也会被 `mvn clean` 删掉
- **任何异常都当未命中**：文件缺失、JSON 损坏、目录只读、版本不符，一律重建，绝不让缓存问题变成一个工具错误
- 写入用 `tmp` + 原子 move，进程被杀不会留下半个文件；残留的 `.tmp` 会在下次成功写入后顺手清掉
- **目录有上限**：整份缓存超过 1 GiB 时按最久未构建的顺序删掉旧工程——不做上限的话，231 个仓库的语料就是 8 GB 的静默堆积
- `CuSummary` / `TypeSummary` / `FieldSummary` 的字段一变，`IndexCacheFormatTest` 就会失败并提示 bump `VERSION`——否则旧文件里缺字段会静默反序列化成半空摘要（`FieldSummary` 是嵌套记录，只比类型名是抓不到它加字段的）
- 基线测试与 `--dev baseline` 一律不走缓存：基线的意义就是验证解析路径，缓存的答案证明不了任何事

### 实测数据（真实工程）

在 16 核笔记本上跑两个真实仓库：

| 工程 | 文件数 | 索引构建（冷） | 说明 |
|---|---|---|---|
| spring-petclinic | 30 | 286 ms | 首次调用 |
| spring-boot | 8 413 | 6.6 s | 首次调用 |

索引建好之后，同进程内的查询延迟：

| 工具 | 耗时 |
|---|---|
| `list-controllers` | 15–25 ms |
| `list-mappers` | 8–15 ms（首次 0.8s，XML 发现结果按快照缓存） |
| `search-by-annotation` | ~130 ms |
| `find-service-callers` | 6–20 ms（取决于命中文件数） |

也就是说 **同一进程内冷启动一次，之后都是毫秒级**。跨进程的冷启动由上一节的磁盘缓存处理。
解析是唯一与工程规模线性相关的成本，已按核数并行化（串行 17.3s → 8 核 8.9s → 16 核 6.6s）。

作用域在同一份索引上的实测（spring-boot，`--dev controllers`，**只算主源码**）：

| 查询 | 端点数 |
|---|---|
| 不限模块 | 85 |
| `scope=module/spring-boot-webmvc` | 3（与全量中该模块的条数一致） |
| `scope=module` | 5（其下所有子模块） |
| `scope=core/spring-boot-autoconfigure` | 0（该模块本来就没有 Controller） |

`find-service-callers` 交叉验证了跨模块解析没有被破坏：`SpringApplication` 定义在 `core/spring-boot`，
`scope=smoke-test` 仍能解析出 10 处调用点（全量 159 处），且没有一条落在作用域外。
`get-file-dependencies` 对 `SpringApplication.java`：outgoing 恒为 173，incoming 由 187 降到 101。

上面这些数字都是**只算主源码**的结果。在这之前，多模块仓库的 `module/src/test` 不会被排除——
判断测试根用的是 `<root>/src/test`，而真实工程把测试放在 `<module>/src/test`。后果是 spring-boot 的端点里
混进了 73 个测试端点，`mybatis-spring-boot` 索引了 114 个文件而主源码只有 46 个。
现在 `--dev controllers` 与 `--include-tests` 的差别才真正对应「应用 vs 测试」。

### 九个仓库的实测结果

端点、mapper、路由都只在主源码上统计（`--include-tests` 的差异见上一段）；文件数是仓库里的 `.java` 总数，
用来标出量级，不是索引文件数：

| 仓库 | 规模 | 工具输出 | 备注 |
|---|---|---|---|
| `spring-petclinic` | 30 文件 | 17 端点 / 3 JPA Repository / 11 调用点 | 与源码逐条核对一致 |
| `spring-boot` | 8 709 文件 | 111 端点（11 个在 `.kt` 里，16 个来自 `RouterFunctions`） | 426 个 `.kt` 解析 0 失败；`@Component` 命中 183 处（Kotlin 贡献 61）；少掉的 1 个是 `src/testFixtures` 里的测试端点 |
| `mybatis-spring-boot` | 46 文件 | 10 mapper | 2 个 XML 的 namespace 指向测试类，默认不扫 |
| `mybatis-plus-samples` | 176 文件 | 39 mapper（36 带实体）/ 1 端点 | 全部无 `@Mapper`，靠 `BaseMapper` 与 `@MapperScan`；3 个没实体的是泛型基接口 |
| `mall` | 519 文件 | 246 端点 / 107 mapper（104 带 XML）/ 1 142 条 SQL 语句 / 21 处 `@Data` | 76 个 mapper 接口一个 `@Mapper` 都没有 |
| `ruoyi` | 285 文件 | 306 端点 / 33 controller / 21 mapper（全部带 XML） | `@MapperScan("com.ruoyi.**.mapper")` 通配符包 |
| `mall-swarm` | 526 文件 | 245 端点 / 5 条网关路由 / 5 个 Feign 客户端 | 路由在 `gateway.server.webflux.routes`（4.x 路径）；Feign 全部解析到本工程模块 |
| `kotlin-realworld`（纯 Kotlin） | 104 个 `.kt` | 19 端点 / 6 controller / 6 JPA Repository | 0 解析失败；25 处映射注解 = 19 个 handler + 6 个类级前缀，逐条核对一致 |
| `spring-cloud-gateway` | 691 文件 | 135 条网关路由，其中 134 条来自代码里的 `RouteLocator` | 90 条 `uri` 不是字面量（`@Value` 注入），按原文返回并计数 |

`mall` 上的三个查询都做了抽样核对：`PmsBrandService` 的实现类是 `PmsBrandServiceImpl`（`@Service`、直接实现），
调用点 9 处全部由 `FIELD` 规则解析、0 未解析；`@Data` 命中 21 处与源码一致。

**输出是字节稳定的**：同一份代码在四个独立 JVM 里跑 `list-mappers`，输出完全一致（含 `counts` 的键顺序）。
这条不是自然而然的结果——`Map.copyOf` 的迭代顺序在每个 JVM 里随机，曾经让两次运行产生不同的 JSON，
是基线测试跨 JVM 才把它抓出来的。

---

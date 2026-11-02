> 本文是过程记录。结论汇总与四层证据见 [`VALIDATION.md`](../VALIDATION.md)。

# 110 个陌生仓库实测与修复（2026-10-31）

目的：把「只在作者挑的 9 个仓库上验证过」这个假设，换成一张清单——**哪些错了、哪些没有答案、哪些只是没崩**。
第一轮只跑不改，交出一份清单；第二轮按清单修，再跑一遍同样的 110 个仓库对账。

## 怎么选的

用 GitHub Search API 跑了 4 组查询（`spring boot` / `spring cloud` / `mybatis` / `spring boot kotlin`），
按 star 排序各取前 100，过滤 fork / archived / 空仓库 / 大于 80 MB，再去掉已经进回归基线的 10 个仓库，
最后按 star 补齐到 110 个，其中给 Kotlin 和 MyBatis 各留了配额。

| | |
|---|---|
| 仓库 | 110（95 Java / 15 Kotlin） |
| 源码规模 | 45,468 个 `.java` + 3,345 个 `.kt`（浅克隆合计 2.3 GB） |
| 最大的一个 | `YunaiV/yudao-cloud`：7,022 文件 / 7,210 类型 / 3,431 端点 |
| 运行方式 | 单个 JVM 顺序跑完，默认选项（不含测试） |

## 修复前后对账

同一批仓库、同一台机器、同一份扫描程序：

| 指标 | 修复前 | 修复后 |
|---|---|---|
| 端点 | 13,307 | 13,246 |
| controller | 3,536 | 3,508 |
| mapper | 2,051 | 2,047 |
| Feign 客户端 | 221 | **214** |
| 未解析路径 · 常量 | 258 | **4** |
| 未解析路径 · 拼接 | 173 | **3** |
| 未解析路径 · 占位符 | 58 | 59 |
| 解析失败文件 | 69 | **0** |
| 索引文件数 | 41,431 | 41,084 |
| 全程耗时 | 50.4 s | **34.3 s** |

端点、controller、mapper 的减少全部来自 `testdata` / `testFixtures` 被排除（-61 端点 / -28 controller /
-347 文件），不是漏抓。其余每一项都是「原来没有答案，现在有了」。

---

## ① 常量路径：258 → 4

工具原先对 `@RequestMapping(ApiConst.BASE + "/x")` 的处理是「原样返回 + `pathResolved=false`」，这对拼接合理，
对**编译期常量不合理**——值就写在同一个工程里。

| 仓库 | 修复前 | 修复后 |
|---|---|---|
| `1024-lab/smart-admin` | 178 / 384 端点（46%） | **0** |
| `201206030/novel` | 44 / 44（100%） | **0** |
| `F43nd1r/Acrarium`（Kotlin） | 9 / 9 | **0** |
| `chillzhuang/SpringBlade` | 6 / 188 | 3 |

修法不是「按 `A.B` 查表」那么简单，实测逼出了四种写法：

1. **嵌套类**：`SwaggerTagConst.Support.URL_PREFIX` —— 限定名第一段按该文件的 import 解析，其余当作嵌套
2. **常量定义在常量里**：`API_FRONT = API + "/front"` —— 取值时要带着声明处的作用域继续折叠，最多 8 跳，环形引用会停
3. **隐式 `static final`**：接口里的 `String PREFIX = "/x"` 没有写 `final`，JavaParser 的 `isFinal()` 是 `false`（SpringBlade 的 Feign 接口全这样）
4. **继承来的常量**：实现在 `@GetMapping(API_PREFIX + "/top")` 里用，声明在它实现的接口上；Kotlin 还有 `companion object` 里
   的 `const val API_PATH`，写法是 `RestApiInterface.API_PATH`

剩下 4 条全部是**值不在这个仓库里**：`Conf.SSO_LOGIN` 来自依赖 jar，Kotlin 的 `"/${API_AUTHORS}"` 是引用顶层常量的
字符串模板（顶层属性还没有对应的持有者类型）。这两类继续按占位符返回，不猜。

## ② GBK 源码：69 条解析失败 → 0

`zhanglei-workspace/shopping-management-system` 的 57 个 `.java` 是 GBK，严格 UTF-8 读取失败后整片退出索引——
该仓库 60% 的源码、整个 DAO 层都不在答案里。现在按「严格 UTF-8 → 严格 GB18030 → 带替换的 UTF-8」解码，
并在索引 warnings 里写明用了几次回退：

```
57 个文件不是 UTF-8（GB18030 57），已按探测到的编码解码；例如 GoodsDao.java, ...
```

剩下的 12 条解析失败来自 `testdata` 里故意写坏的夹具，随 ④ 一起消失。

## ③ JPA 实体经中间接口：82 → 5

`CoinDao extends BaseDao<Coin>` 配 `BaseDao<T> extends JpaRepository<T, Long>`，实体在泛型实参里。
分析器现在沿继承链逐层绑定类型参数（`BaseDao<T, ID>` 两个参数、跨包 import、两级链都支持），
每一步都在声明文件自己的 import 上下文里解析父类型名。

| 仓库 | 修复前 | 修复后 |
|---|---|---|
| `jammy928/CoinExchange_CryptoExchange_Java` | 54 / 67 无实体 | **1**（`BaseDao` 自己） |
| `Exrick/xboot` | 26 / 34 | 4（`StudentMapper`×2、`XbootBaseDao`×2） |

顺带修掉一个同族错误：`JpaRepository<T, Long>` 里的 `T` 原来会被当成实体名返回（`entityType: "T"`），
现在和 `BaseMapper<T>` 一样返回 `null`。

## ④ `testdata/`：339 个文件不再进索引

`explyt/spring-plugin` 有 339 个源文件在 `modules/*/testdata/` 下，是**故意写坏的测试夹具**。
排除之后：12 条假解析失败消失、**7 个不存在的 `@FeignClient` 消失**（该仓库主源码里一个都没有）。
`testFixtures`（Gradle 测试夹具源集）同理——spring-boot 自己有 14 个这样的目录，其中
`AbstractServletWebMvcIntegrationTests` 的 `@RequestMapping("/hello")` 曾被当成一个真实端点。

## 准确性抽查：修完之后再数一遍

拿「源码里的方法级映射注解」和工具输出的端点对账（只算 `src/main`）：

| 仓库 | 源码注解 | 工具端点 | 差异解释 |
|---|---|---|---|
| `1024-lab/smart-admin` | 384 | 384 | 完全一致（修复前也是 384，但现在路径是真路径） |
| `elunez/eladmin` | 132 | 133 | +1：方法级 `@RequestMapping` |
| `lilishop/lilishop` | 1,183 | 1,192 | +9：方法级 `@RequestMapping` |
| `chillzhuang/SpringBlade` | 201 | 188（+13 Feign） | **精确闭合**：少的 13 条都在 `@FeignClient` 接口上 |
| `Exrick/xboot` | 0 个 verb 注解 | 167 | 全用 `@RequestMapping` 的旧写法，工具照样认 |

## 没出问题的地方（也是结论的一半）

- **110 个仓库，0 崩溃、0 工具失败、0 超时**，包括 7,022 文件的 `yudao-cloud`（索引 2.8 s）
- **3,345 个 `.kt` 里 0 条 Kotlin 解析失败**；修复前 69 条解析问题全部是 `.java`
- 端点数为 0 的 14 个仓库，抽查的两个（`spring-reading`、`dgs-framework`）确实没有 HTTP 端点

## 还剩下的（写在这里，不写进广告）

- **值不在仓库里的常量**：依赖 jar 里的常量、Kotlin 顶层 `const val` 的字符串模板（`"/${API_AUTHORS}"`）
- **`RestTemplate` / `RestClient` / `WebClient`** 的 URL 在调用点拼装，静态还原不了（110 个仓库里 96% 的首参不是字面量）
- **AOP 代理后的实际调用目标**、`@Conditional` 分支、跨仓库引用

## 数据

原始结果在 `.realworld/results.jsonl`（110 行，一行一个仓库），修复前的快照在 `results-before.jsonl`。
仓库副本在 `.realworld/repos/`（2.3 GB，可随时删）。复现脚本：`.realworld/Scan.java` + `.realworld/analyze.py`。

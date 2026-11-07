# 发布帖草稿

0 star 的原因不是项目差，是没人知道。这份是发帖用的，不是宣传稿 —— 每一句都指向一个能被
验证的数字或一次真实的踩坑。

发之前先确认：`README.en.md` 已经推到 main，Release v0.1.0 的两个资产还在。

---

## 中文版（V2EX / 掘金 / 少数派）

**标题**

```
写了个 MCP 服务，把 Spring 仓库读成端点清单：不编译、不启动、读不出来的就说不知道
```

**正文**

```
起因很小：给一个继承自 BaseCrudController<T> 的控制器加接口，想先看看它暴露了什么，
grep 了一圈 @GetMapping，一个都没有 —— 四个端点全写在父类里。

后来就写了这个：spring-mcp，一个 MCP 服务，静态读 Spring 源码。Java 走 JavaParser 的 AST，
Kotlin 没有 AST，用词法器在 token 流上重建声明。不启动 Spring 上下文，不把目标工程加载进
classpath，不编译它。

给一个目录，它回答：这个 URL 谁处理（文件+行号）、这个 Service 谁在调、这张表由哪个 mapper
操作、Feign 客户端声明了哪些路径、网关把 /api/orders/** 转到了哪个服务。11 个工具。

几个刻意的取舍：

- 注解写下来的，它读得到；由配置或运行时决定的，它说不知道。`${api.prefix}` 不会被补成
  /api/xxx —— 那看起来像个答案，其实是编的。
- @Profile / @ConditionalOn* 原样抄进 conditions 字段，不求值。它不知道你的线上环境。
- 指错目录会直接报错，而不是返回「0 个端点」。这两种情况从外面看是同一句话，而你会相信后者。

准不准：在 98 个没见过的仓库上跑过，13.6 万源文件，98/98 成功，最慢 14 秒；两万条端点
逐条能指回源码里的真实映射声明，100%。过程文档里也写了这一路抓到的七个 bug，以及两条被
推翻的结论 —— 包括一次五个会话结果完全一致、但一致地少算了 359 个端点。

一条命令：

    java -jar spring-mcp-all.jar --dev controllers --root /path/to/repo

或者接进 Claude Code / Cursor：

    { "mcpServers": { "spring-mcp": { "command": "…/spring-mcp.sh",
      "args": ["--project=/path/to/repo"] } } }

它不做什么，README 里有一整节，比功能列表长。如果你试了发现哪一类端点被漏掉，告诉我仓库
地址就行，那种反馈比 star 有用。
```

---

## English (Show HN / r/java / r/springboot)

**Title**

```
Show HN: spring-mcp – read a Spring repo as an endpoint inventory, without building it
```

**Body**

```
Started with a small annoyance: a controller extending AbstractCrudController<T> exposed four
endpoints, all declared in the parent. grep for @GetMapping found nothing.

spring-mcp is an MCP server that reads Spring source statically — JavaParser AST for Java, a lexer
for Kotlin. No Spring context, no classloading, no build. Point it at a directory and it answers:
which method serves this URL (file:line), who calls this service method, which mapper owns this
table, what the Feign clients declare, where the gateway routes.

Eleven tools. The design rule is that a plausible wrong answer is worse than an honest "I don't
know":

- ${api.prefix} is never completed into a URL. It comes back with pathResolved=false and a reason.
- @Profile and @ConditionalOn* are copied verbatim, never evaluated.
- A wrong projectRoot errors out instead of returning "0 endpoints" — those two look identical
  from the outside, and you would believe the second one.

On 98 repositories I had never seen (136k source files): 98/98 analysed, slowest 14 s. All 20,834
endpoints trace back to a real mapping declaration in source. The validation doc also records the
seven bugs this found, and two conclusions I had to overturn — including five sessions that agreed
with each other and were all wrong by 359 endpoints.

    java -jar spring-mcp-all.jar --dev controllers --root /path/to/repo

Apache-2.0. The "what it cannot see" section of the README is longer than the feature list, on
purpose. If it misses a class of endpoints in your repo, an issue with the repo URL is more useful
to me than a star.
```

---

## 发的时候注意

- **别同一天群发**。V2EX 和掘金隔两天，HN 挑美国工作日上午（北京时间晚 9 点后）。
- **HN 标题别带感叹号、别写 "AI-powered"**，会被当广告。Show HN 只接受你自己做的东西，符合。
- 有人问「和 xxx 有什么区别」，答具体的：他们要不要启动应用、认不认继承的映射、未解析的路径怎么处理。
- 有人报 bug，先让他跑 `--dev controllers`，把「分析错了」和「客户端调错了」分开 —— 这个
  项目自己的文档就是这么排查的，照做就行。
- **别在帖子里放 Agensi 链接**。先让人用起来，付费版是后话。

# 扩展方式

> 两种都不需要改这个仓库的代码：写插件（Java，能改行为）、改配置（声明式，只加视图）。
> 用法与边界见 [README](../README.md)。

## 写一个插件

三步：实现接口、写 services 文件、丢进 `plugins/`。不需要改主程序、不需要重新打包。

```java
public final class MyPlugin implements SpringMcpPlugin {

    private PluginContext context;

    @Override public String id() { return "com.acme.my-plugin"; }
    @Override public String version() { return "1.0.0"; }

    @Override
    public void initialize(PluginContext context) throws ToolFailure {
        this.context = context;
        context.logger().info("plugin ready");
    }

    @Override
    public List<ToolProvider> tools() {
        return List.of(new MyTool(context));
    }
}
```

```java
final class MyTool implements ToolProvider {

    private final PluginContext context;

    MyTool(PluginContext context) { this.context = context; }

    @Override
    public ToolDescriptor descriptor() {
        return ToolDescriptor.builder("my-tool")              // 必须 kebab-case
            .description("做什么，以及什么时候不该用它")
            .inputSchema(JsonSchemas.object()
                .string("projectRoot", "工程根目录", false, null))
            .build();
    }

    @Override
    public ToolOutcome invoke(ToolInvocation invocation) {
        try {
            var index = context.analyzer().open(Path.of("/abs/path"), IndexOptions.defaults());
            var report = index.findByAnnotation(new Annotations.Query("Scheduled", Set.of(), false, 100));
            return ToolOutcome.ok(report, "找到 " + report.hits().size() + " 处");
        } catch (ToolFailure f) {
            return ToolOutcome.fail(f);                        // 不要抛异常
        }
    }
}
```

`META-INF/services/io.github.springmcp.spi.SpringMcpPlugin`：

```
com.acme.MyPlugin
```

插件工程只有一个依赖：`spring-mcp-all.jar` 的 `provided` 作用域（或直接用 `system`/本地 `lib` 引入）。

```xml
<dependency>
  <groupId>io.github.springmcp</groupId>
  <artifactId>spring-mcp</artifactId>
  <version>0.1.0-SNAPSHOT</version>
  <scope>provided</scope>
</dependency>
```

用 `provided` 是必须的：SPI 类型必须由服务端的父 classloader 提供，若插件自带一份，`SpringMcpPlugin` 在两个 classloader 里就是两个不同的类，`ServiceLoader` 会报 `Provider ... not a subtype`。

打包后把 jar 放进插件目录，重启服务，`tools/list` 里就会多出 `my-tool`。

完整可运行的例子见 `src/test/java/io/github/springmcp/example/ExamplePlugin.java`。它放在测试源码里是有意的：由 `ExamplePluginTest` 编译并验证，示例不会失效，同时不会被打进服务 jar（插件从 `plugins/` 加载，内置在 jar 里的插件是死代码）。

### 插件能拿到什么

| 能力 | 说明 |
|---|---|
| `context.analyzer()` | 只读分析门面：`open(Path, IndexOptions)` → `IndexHandle`，九个分析都在上面 |
| `context.logger()` | 写 stderr 的日志（stdout 是协议通道，绝不能用） |
| `context.pluginsDirectory()` | 插件 jar 所在目录，方便带辅助数据文件 |
| `context.defaultProjectRoot()` | 命令行 `--project` 的值，可能为 null |

拿不到 MCP 类型、JavaParser 类型或索引实现类。这不是限制，是隔离。

### 隔离保证

- 每个 jar 一个 `URLClassLoader`（父优先），插件之间互相不可见
- `ServiceLoader` 是惰性的，`ServiceConfigurationError` / `NoClassDefFoundError` 在 `hasNext()`/`next()` 才抛 —— try/catch 包在迭代内部，一个坏 jar 只损失它自己
- `initialize()` 抛异常、工具名重复、schema 非法、SPI 主版本不匹配 → 丢弃该插件/工具并记一条 report
- **最坏情况是 0 个插件，服务照常带着 11 个内置工具启动**

---

## 声明式扩展

改配置文件就能加工具，不用写 Java、不用建插件工程。

上一节那种插件要写 Java、要 Maven 工程。但真实需求里绝大多数扩展**不是要新算法，是要新视图**：固定几个参数、只留需要的字段、按类分组。这类扩展改配置文件就够了。

文件位置：`--config=<file>` → `SPRING_MCP_CONFIG` → 插件目录下的 `spring-mcp.yaml` / `.yml` / `.json`。
按扩展名自动识别格式，两种都支持。

### 声明式工具：复用内建查询

```yaml
version: 1
tools:
  - name: list-owner-endpoints          # 必须 kebab-case
    title: 宠物诊所 /owners 下的端点
    description: |
      只列出 /owners 开头的端点，按 Controller 分组，每组最多 3 条。
      适用场景：…… 不适用：看全部端点用 list-controllers。
    query: list-controllers             # 五种内建查询之一
    fixed:                              # 固定参数，调用方不可更改
      basePathFilter: /owners
    expose:                             # 暴露给调用方的参数
      - name: httpMethod
        description: 只看某个 HTTP 动词
    output:
      source: endpoints                 # structuredContent 里的数组字段
      select: [path, httpMethods, handlerMethod, line]
      groupBy: controllerFqn
      sort: [{ field: path }]
      limitPerGroup: 3
      limit: 10
    summary: 宠物诊所 /owners 下的端点
```

它是**代理**内建工具，不是重写：参数解析、错误码、warning 合并全部走原来那条路径，所以输出和 `list-controllers` 加同样过滤后完全一致。
`schema` 也是从被代理工具裁出来的，所以内建工具后来加了 `scope`，声明式工具自动就有——`fixed: {scope: [services/order]}`
就能做一个「只看订单模块端点」的专用工具，不需要改代码。

三条规则值得记住：

- **配置永远赢**。`fixed` 里的值调用方改不了——想改就该直接用内建工具。
- **`fixed` 的参数不出现在 schema 里**，而是写进工具描述的「固定参数」一行。留在 schema 里会让调用方以为可传，传了又被忽略，是最糟的组合。
- **base 的必填参数既没 expose 也没 fixed → 拒绝这个工具**。这条专挡「做了个查调用点的工具却忘了固定 `serviceClass`」——那种工具能注册成功、`tools/list` 里看着正常、每次调用都失败。

`output` 只做 `select` / `groupBy` / `sort` / `limit` / `limitPerGroup`，**不做表达式求值、不做 JSONPath、不做模板**。要计算就写外部进程工具或真插件。

### 外部进程工具：任何语言

```yaml
external:
  - name: file-stats
    description: 统计工程里 .java 文件的数量与总字节数。
    command: ["python", "stats_tool.py"]   # 必须是数组，不经 shell
    cwd: .                                  # 相对「配置文件所在目录」，不许逃逸
    timeoutMs: 8000
    maxOutputBytes: 65536
    expose:
      - name: includeTests
```

协议：一次调用起一个进程，**stdin 一个 JSON、stdout 一个 JSON、退出码 0**。

```jsonc
// 子进程收到
{"protocol":"spring-mcp.external/1","tool":"file-stats","arguments":{...},
 "projectRoot":"/abs/project","context":{"pluginsDirectory":"..."}}

// 子进程应当输出（成功）
{"ok":true,"data":{...},"summary":"30 个文件","warnings":[]}
// 或（失败）
{"ok":false,"code":"PLUGIN_ERROR","message":"...","hint":"..."}
```

`cwd` 相对**配置文件所在目录**而不是被分析的工程——扩展应该自带脚本，不该要求每个被分析的仓库里都有你的脚本。工程路径仍然通过 `projectRoot` 传给子进程。

### 安全：默认关闭

外部进程工具需要显式 `--allow-external-commands` 才注册。不加这个开关时：**启动不失败**，这些工具被跳过并记一条汇总日志。

另外有三道加载期检查：`command` 必须是数组（字符串一律拒，避免 shell 注入）；`command[0]` 不能是 `sh`/`bash`/`cmd`/`powershell`；`cwd` 不能逃出扩展目录。

> ⚠️ 这个开关只管「能不能起进程」，**不是沙箱**。子进程继承环境变量和完整文件系统权限。只在你信任这份配置文件时才打开。

### 配置出错只丢一个工具

语法错 → 整份文件被忽略并记一条；单条工具字段拼错 → 只损失那一条，其余照常注册。和 jar 插件的隔离哲学一致：可选扩展出问题，服务必须继续跑。

完整可运行的例子见 `fixtures/declarative-example/`

```bash
java -jar target/spring-mcp-all.jar \
  --config=fixtures/declarative-example/spring-mcp.yaml \
  --allow-external-commands --project=/path/to/petclinic
```

---

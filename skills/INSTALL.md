# Install

This skill drives the **spring-mcp** MCP server. The server does the analysis; the skill decides
what to ask it and how to read the answer. Without the server the skill has nothing to call — it
ships instructions, not a binary.

Takes about three minutes.

## 1. JDK 21 or newer

```
java -version
```

`21.x` or newer and you are done. Nothing printed means no JDK on PATH: install one (Temurin, Zulu,
Microsoft OpenJDK) and set `JAVA_HOME` to its directory. A path that is not on `PATH` is fine —
step 3 prints the absolute path for you.

## 2. The server

Download `spring-mcp.zip` from

<https://github.com/zhaogegejiafuhao/spring-mcp/releases>

and unzip it somewhere stable, for example `~/tools/spring-mcp`. The zip contains
`spring-mcp-all.jar` and two launchers (`spring-mcp.sh`, `spring-mcp.cmd`) that find java
themselves.

## 3. Client config

Run the preflight script. It is read-only: it looks for java, looks for the jar, and prints the
exact JSON for your machine.

```
sh scripts/preflight.sh /path/to/spring-mcp-all.jar
scripts\preflight.cmd C:\path\to\spring-mcp-all.jar
```

Paste the printed block into your MCP client config and restart the client. It looks like this:

```json
{
  "mcpServers": {
    "spring-mcp": {
      "command": "C:/tools/spring-mcp/spring-mcp.cmd",
      "args": ["--project=/abs/path/to/your-spring-app"]
    }
  }
}
```

`--project` is optional. Without it, pass `projectRoot` on each call — which is what the skill
does when it has not been told otherwise.

## 4. Verify

Ask the agent to call `list-projects`. It must return JSON, not an error.

Then try a real repository:

```
list-controllers projectRoot=/abs/path/to/your-spring-app
```

If that returns endpoints, you are done. If it returns `INVALID_ARGUMENT`, the path is not the
source root — run `list-modules` and point `projectRoot` at a module.

## Troubleshooting

| Symptom | Cause |
|---|---|
| `No Java runtime found` | The launcher cannot see a JDK. Use the absolute `java.exe` path that `preflight` prints |
| `INVALID_ARGUMENT: 目录下没有 Java/Kotlin 源码` | `projectRoot` is a parent, a frontend, or a Maven aggregator module |
| `INVALID_ARGUMENT: scope 不可用` | Single-module project — drop `scope` |
| Tools do not appear in the client | The MCP server is not trusted/enabled in the client, or the client was not restarted |

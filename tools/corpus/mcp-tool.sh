#!/usr/bin/env bash
# 一次调用一个工具，走的是真实的工具层——registry / plugin / ToolInvocation 与 MCP 服务完全相同，
# 参数名与 tools/list 一致。端到端验收用它，因为要验的就是「客户端看到的那一层」。
#
# 想绕过协议层看分析本身，用 --dev（DevCli）：它直接调索引，出错时能确定是分析的问题。
#
#   tools/corpus/mcp-tool.sh list-controllers projectRoot=/path/to/repo
#   tools/corpus/mcp-tool.sh find-service-callers projectRoot=/path/to/repo service=OrderService
#   JAR=target/spring-mcp-all.jar tools/corpus/mcp-tool.sh list-mappers projectRoot=/path/to/repo
#
# 退出码：0 成功，1 工具返回失败，2 用法错误。
set -u

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
JAR="${JAR:-$ROOT/target/spring-mcp-all.jar}"

if [ ! -f "$JAR" ]; then
    echo "jar 不存在: $JAR（先跑 ./mvnw package）" >&2
    exit 2
fi

# Windows 上 java 不认 MSYS 的 /d/... 路径，源码模式的参数也要转。
JAR_ARG="$(cygpath -w "$JAR" 2>/dev/null || echo "$JAR")"
SRC_ARG="$(cygpath -w "$HERE/McpTool.java" 2>/dev/null || echo "$HERE/McpTool.java")"

if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
    JAVA="$JAVA_HOME/bin/java"
elif command -v java >/dev/null 2>&1; then
    JAVA=java
else
    echo "找不到 java：设 JAVA_HOME，或把 java 放进 PATH" >&2
    exit 2
fi

# stdout/stderr 单独指定 UTF-8：JDK 18+ 的 -Dfile.encoding 不管控制台流（JEP 400），
# Windows 上重定向到管道时中文会按 GBK 写出来，读它的脚本和模型看到的是乱码。
exec "$JAVA" -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 \
    -cp "$JAR_ARG" "$SRC_ARG" "$@"

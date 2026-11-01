#!/usr/bin/env sh
# ---------------------------------------------------------------------------
# spring-mcp launcher (Linux / macOS)
#
# Lets an MCP client start the server without knowing where java is:
#   { "command": "/path/to/spring-mcp.sh", "args": ["--project=/home/me/app"] }
#
# Looks for the fat jar next to this script first, then under target/ -- so it
# works both from a fresh checkout and from an unpacked release.
# ---------------------------------------------------------------------------
set -eu

DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
JAR="$DIR/spring-mcp-all.jar"
[ -f "$JAR" ] || JAR="$DIR/target/spring-mcp-all.jar"

if [ ! -f "$JAR" ]; then
    echo "[spring-mcp] spring-mcp-all.jar not found." >&2
    echo "[spring-mcp] Build it first: ./mvnw package" >&2
    exit 1
fi

if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
    JAVA="$JAVA_HOME/bin/java"
elif command -v java >/dev/null 2>&1; then
    JAVA=java
else
    echo "[spring-mcp] No Java runtime found." >&2
    echo "[spring-mcp] spring-mcp needs JDK 21+. Install one, or set JAVA_HOME." >&2
    exit 1
fi

exec "$JAVA" -Dfile.encoding=UTF-8 -jar "$JAR" "$@"

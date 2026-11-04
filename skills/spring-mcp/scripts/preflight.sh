#!/usr/bin/env sh
# ---------------------------------------------------------------------------
# spring-mcp preflight (read-only)
#
# Answers one question: can this machine start the spring-mcp MCP server?
# It looks for a JDK, looks for the jar, and prints the config snippet for
# this machine. It installs nothing, downloads nothing, writes nothing.
#
#   ./preflight.sh                        # look in the current directory
#   ./preflight.sh /path/to/spring-mcp-all.jar
#   SPRING_MCP_JAR=/path/to.jar ./preflight.sh
# ---------------------------------------------------------------------------
set -eu

say() { printf '%s\n' "$*"; }
rule() { printf '%-12s%-9s%s\n' "$1" "$2" "$3"; }
# JSON string escaping: backslashes become \\ so a Windows path stays valid JSON.
jesc() { printf '%s' "$1" | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g'; }

ok=0

# --- java ------------------------------------------------------------------
JAVA=""
if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
    JAVA="$JAVA_HOME/bin/java"
elif command -v java >/dev/null 2>&1; then
    JAVA=$(command -v java)
fi

if [ -z "$JAVA" ]; then
    rule "java" "MISSING" "spring-mcp needs JDK 21 or newer"
    ok=1
else
    raw=$("$JAVA" -version 2>&1 | head -n 1)
    num=$(printf '%s' "$raw" | sed -n 's/.*version "\([0-9][0-9.]*\)".*/\1/p')
    major=${num%%.*}
    if [ "$major" = "1" ]; then
        major=$(printf '%s' "$num" | cut -d. -f2)
    fi
    if [ -n "$major" ] && [ "$major" -ge 21 ] 2>/dev/null; then
        rule "java" "OK" "$raw"
    else
        rule "java" "TOO OLD" "$raw (need 21+)"
        ok=1
    fi
fi

# --- jar -------------------------------------------------------------------
JAR=""
if [ "${1:-}" != "" ] && [ -f "$1" ]; then
    JAR=$1
elif [ -n "${SPRING_MCP_JAR:-}" ] && [ -f "$SPRING_MCP_JAR" ]; then
    JAR=$SPRING_MCP_JAR
else
    for c in ./spring-mcp-all.jar ./target/spring-mcp-all.jar ../spring-mcp-all.jar; do
        if [ -f "$c" ]; then
            JAR=$c
            break
        fi
    done
fi

if [ -z "$JAR" ]; then
    rule "jar" "MISSING" "spring-mcp-all.jar not found here"
    ok=1
else
    JARDIR_LOCAL=$(cd -- "$(dirname -- "$JAR")" && pwd)
    JAR="$JARDIR_LOCAL/$(basename -- "$JAR")"
    JARDIR="$JARDIR_LOCAL"
    # Git Bash / MSYS reports /d/... which no MCP client can use; -w gives D:\...
    if command -v cygpath >/dev/null 2>&1; then
        JAR=$(cygpath -w "$JAR")
        JARDIR=$(cygpath -w "$JARDIR_LOCAL")
    fi
    size=$(wc -c < "$JAR" | tr -d ' ')
    rule "jar" "OK" "$JAR ($size bytes)"
fi

say ""
if [ "$ok" -ne 0 ]; then
    say "Not ready. Get the missing pieces:"
    say "  jar : https://github.com/zhaogegejiafuhao/spring-mcp/releases"
    say "  java: any JDK 21+ (Temurin, Zulu, Microsoft OpenJDK)"
    exit 1
fi

# --- config ----------------------------------------------------------------
# Normalize the java path for a JSON config: MSYS reports /c/..., and Windows
# resolves java.exe but a literal path is safer in a config file.
if command -v cygpath >/dev/null 2>&1; then
    case "$JAVA" in
        /*) JAVA=$(cygpath -w "$JAVA") ;;
    esac
fi
case "$JAVA" in
    *.exe) : ;;
    *)
        if [ -x "$JAVA.exe" ]; then
            JAVA="$JAVA.exe"
        fi
        ;;
esac

# Prefer the launcher next to the jar: it finds java itself, so the client
# config does not have to hardcode a JDK path.
CMD="$JAVA"
ARGS="\"-jar\", \"$(jesc "$JAR")\""
for launcher in spring-mcp.sh spring-mcp.cmd; do
    if [ -f "$JARDIR_LOCAL/$launcher" ]; then
        CMD="$JARDIR/$launcher"
        ARGS=""
        break
    fi
done

rule "project" "--" "pass --project=<dir>, or projectRoot on each call"
say ""
say "Add this to your MCP client config, then restart the client:"
say ""
say "{"
say "  \"mcpServers\": {"
say "    \"spring-mcp\": {"
if [ -z "$ARGS" ]; then
    say "      \"command\": \"$(jesc "$CMD")\","
    say "      \"args\": [\"--project=/abs/path/to/your-spring-app\"]"
else
    say "      \"command\": \"$(jesc "$CMD")\","
    say "      \"args\": [$ARGS, \"--project=/abs/path/to/your-spring-app\"]"
fi
say "    }"
say "  }"
say "}"
say ""
say "Verify: ask the agent to call list-projects. It must answer, not error."

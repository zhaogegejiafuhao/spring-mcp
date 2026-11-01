#!/usr/bin/env bash
# 真实仓库语料：把「这套工具在真实项目上数出来的东西」变成可重跑的东西。
#
# 这些数字以前只活在一个人某天的终端里——换台机器就没了，PR 上也看不到它们变了。
# 这里固定 11 个仓库和它们的期望值：跑出来一样就是绿，不一样就红。
#
# 覆盖的形态：注解式端点、函数式路由、MyBatis 接口与 XML、Feign、网关、多模块、
# 以及两个边界（一个没有任何 HTTP 端点的库、一个非 Spring 框架的项目）。
#
#   check.sh --jar target/spring-mcp-all.jar                    # 按 repos.tsv 的 commit 拉取后比对
#   check.sh --jar target/spring-mcp-all.jar --repos ../repos2  # 用本地已有 checkout（仍校验 commit）
#   check.sh --jar target/spring-mcp-all.jar --update           # 重写期望值（只在确认变化合理时用）
set -u

JAR=""
REPOS_DIR=".corpus"
UPDATE=0
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
EXPECTED="$HERE/expected.json"
LIST="$HERE/repos.tsv"
TAB="$(printf '\t')"

while [ $# -gt 0 ]; do
  case "$1" in
    --jar) JAR="${2:-}"; shift 2 ;;
    --repos) REPOS_DIR="${2:-}"; shift 2 ;;
    --update) UPDATE=1; shift ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

[ -n "$JAR" ] || { echo "usage: check.sh --jar <fat-jar> [--repos <dir>] [--update]" >&2; exit 2; }
[ -f "$JAR" ] || { echo "jar not found: $JAR" >&2; exit 2; }

# 深路径在 Windows 上会被 260 字符卡住，clone 会中途停下却留下可用的 .git
git config --global core.longpaths true 2>/dev/null || true

mkdir -p "$REPOS_DIR"
fetched=0
reused=0
while IFS="$TAB" read -r name url sha; do
  [ -z "${name:-}" ] && continue
  dir="$REPOS_DIR/${name//\//__}"
  # 钉死 commit：跟 HEAD 走的话，上游随便一次提交都会让这里变红，而这里要回答的是
  # "我们的改动有没有改变结果"，不是"上游有没有改代码"。
  if [ -d "$dir/.git" ] && [ "$(git -C "$dir" rev-parse HEAD 2>/dev/null)" = "$sha" ]; then
    reused=$((reused + 1))
    continue
  fi
  rm -rf "$dir"
  echo "fetch $name@$(echo "$sha" | cut -c1-8)"
  mkdir -p "$dir"
  # 管道接到 tail 上时，`if` 看到的是 tail 的退出码，git 失败会被悄悄吞掉——
  # 症状是后面报"没有结果"，而不是"取不到"，完全指错方向。
  if ! { git -C "$dir" init -q &&
         git -C "$dir" remote add origin "$url" &&
         git -C "$dir" fetch -q --depth 1 origin "$sha" &&
         git -C "$dir" checkout -q FETCH_HEAD; } > "$dir.fetch.log" 2>&1; then
    tail -3 "$dir.fetch.log" >&2
    echo "  取不到 $name@$sha" >&2
    exit 1
  fi
  rm -f "$dir.fetch.log"
  fetched=$((fetched + 1))
done < "$LIST"
echo "拉取 $fetched 个，复用 $reused 个，语料目录 $REPOS_DIR"

OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT

# ScanTools 在同一个进程里逐个建索引，每个仓库用新的 registry；路径参数给白名单，
# 否则 --repos 指到别的目录时会顺手把里面所有 checkout 都扫一遍。
JAR_ARG="$(cygpath -w "$JAR" 2>/dev/null || echo "$JAR")"
SCAN_ARG="$(cygpath -w "$HERE/ScanTools.java" 2>/dev/null || echo "$HERE/ScanTools.java")"
java -Dfile.encoding=UTF-8 -cp "$JAR_ARG" "$SCAN_ARG" \
  "$REPOS_DIR" "$OUT/results.jsonl" "$OUT/endpoints.jsonl" "$LIST" 2>&1 | tail -1

python - "$OUT/results.jsonl" "$EXPECTED" "$UPDATE" <<'PY'
import json
import sys

results_path, expected_path, update = sys.argv[1], sys.argv[2], sys.argv[3] == "1"

actual = {}
for line in open(results_path, encoding="utf-8"):
    if line.strip():
        row = json.loads(line)
        actual[row["name"]] = row

expected = json.load(open(expected_path, encoding="utf-8"))
fields = ("endpoints", "controllers", "mappers", "feignClients", "gatewayRoutes")

if update:
    merged = dict(expected)
    for name, row in actual.items():
        merged[name] = {f: row.get(f, 0) for f in fields} | {"status": row.get("status")}
    json.dump(dict(sorted(merged.items())), open(expected_path, "w", encoding="utf-8"),
              ensure_ascii=False, indent=2)
    print(f"已写入 {len(merged)} 个仓库的期望值")
    raise SystemExit(0)

failures = []
for name, want in sorted(expected.items()):
    got = actual.get(name)
    if got is None:
        failures.append(f"  {name}: 没有结果（仓库没取到？）")
        continue
    if got.get("status") != "OK":
        failures.append(f"  {name}: status={got.get('status')} {got.get('error', '')}")
        continue
    diff = [f"{f} {want[f]}→{got.get(f)}" for f in fields if want[f] != got.get(f)]
    if diff:
        failures.append(f"  {name}: " + ", ".join(diff))

if failures:
    print(f"\n{len(failures)} / {len(expected)} 个仓库的数字变了：")
    print("\n".join(failures))
    print("\n如果这是有意的改动，跑 --update 并把 expected.json 一起提交；否则就是回归。")
    raise SystemExit(1)

total = sum(w[fields[0]] for w in expected.values())
print(f"\n{len(expected)} 个仓库全部一致，端点合计 {total}")
# 打成 annotation：作业日志要权限才能看，页面上直接能见到这一行才算真的绿了。
print(f"::notice title=语料::{len(expected)} 个仓库全部一致，端点合计 {total}")
PY

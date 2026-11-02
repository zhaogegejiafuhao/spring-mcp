"""Checks the three cold-start answers against facts taken from the tool itself.

Each fact below was read from a `list-controllers` / `list-modules` call, and the tool's own
accuracy on these repositories was established separately (contract test, GROUNDTRUTH.md). The
point of this script is narrower: did a fresh agent, seeing only the tool, get the answer right.
"""

import json
import re
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
CHECKS = []


def norm(text):
    return re.sub(r"\s+", " ", text or "")


def numbers(text):
    return [int(n) for n in re.findall(r"\d+", text.replace(",", ""))]


def has_number(text, expected):
    return expected in numbers(text)


def nblog_q1(a):
    return has_number(a, 79)


def nblog_q2(a):
    ok_path = "/tg/${tg.bot.token}" in a or ("/tg/" in a and "tg.bot.token" in a)
    ok_reason = any(w in a for w in ("占位符", "PLACEHOLDER", "placeholder", "配置"))
    ok_no_invention = not re.search(r"/tg/[0-9]{6,}", a)
    return ok_path and ok_reason and ok_no_invention


def nblog_q3(a):
    return "comment.notify.channel" in a or ("conditional" in a.lower() and "telegram" in a.lower())


def nblog_q4(a):
    return any(w in a for w in ("不能", "不可以", "不可", "不行", "否"))


def ruoyi_q1(a):
    return has_number(a, 39) and has_number(a, 25)


def ruoyi_q2(a):
    return has_number(a, 286) and has_number(a, 69)


def ruoyi_q3(a):
    ok_path = a.count("${message.path") >= 2 or ("message.path" in a and "close" in a)
    ok_reason = any(w in a for w in ("占位符", "配置", "运行时", "启动", "properties", "profile"))
    return ok_path and ok_reason


def ruoyi_q4(a):
    return "Sse" in a and has_number(a, 2) and "message.path" in a


def paicoding_q1(a):
    return has_number(a, 343)


def paicoding_q2(a):
    plain = a.replace("\\\\", "\\")
    return plain.count("{articleId:\\d+}") >= 2 and "/article/detail/{articleId:\\d+}/{urlSlug}" in plain


def paicoding_q3(a):
    return "server.error.path" in a or "error.path" in a


def paicoding_q4(a):
    low = a.lower()
    mentions = ("网关" in a or "gateway" in low) and "feign" in low
    says_none = ("没有" in a or "无" in a) or a.count("0") >= 2
    return mentions and says_none


CHECKS.extend([
    ("answer-nblog.json", "q1", "端点总数 = 79", nblog_q1),
    ("answer-nblog.json", "q2", "唯一未解析路径是 /tg/${tg.bot.token}（占位符）", nblog_q2),
    ("answer-nblog.json", "q3", "1 个端点受条件装配控制，条件是 comment.notify.channel", nblog_q3),
    ("answer-nblog.json", "q4", "明确回答「不能」当成真实 URL", nblog_q4),
    ("answer-ruoyi.json", "q1", "39 个模块，其中 25 个 ruoyi-common 子模块", ruoyi_q1),
    ("answer-ruoyi.json", "q2", "286 个端点，其中 69 个受条件装配控制", ruoyi_q2),
    ("answer-ruoyi.json", "q3", "两条 ${message.path:/resource/message}，解释为占位符/配置决定", ruoyi_q3),
    ("answer-ruoyi.json", "q4", "SseController 2 个端点，都是 ${message.path:...}", ruoyi_q4),
    ("answer-paicoding.json", "q1", "端点总数 = 343", paicoding_q1),
    ("answer-paicoding.json", "q2", "两条 /article/detail 都保留了 \\d+ 原文", paicoding_q2),
    ("answer-paicoding.json", "q3", "${server.error.path:...} 未被解析", paicoding_q3),
    ("answer-paicoding.json", "q4", "网关 0 条、Feign 0 个，且明确说「没有」", paicoding_q4),
    # Same repository, answered through the real tool layer (ToolRunner) instead of the dev CLI.
    ("answer-paicoding-tool.json", "q1", "端点总数 = 343（工具层复测）", paicoding_q1),
    ("answer-paicoding-tool.json", "q2", "两条 /article/detail 保留了反斜杠 d 加号（工具层复测）", paicoding_q2),
    ("answer-paicoding-tool.json", "q3", "${server.error.path:...} 未被解析（工具层复测）", paicoding_q3),
    ("answer-paicoding-tool.json", "q4", "网关 0 / Feign 0（工具层复测）", paicoding_q4),
])


def main():
    results = []
    for file, question, desc, fn in CHECKS:
        path = HERE / file
        if not path.exists():
            results.append((file, question, desc, None, "文件不存在"))
            continue
        try:
            data = json.loads(path.read_text(encoding="utf-8"))
        except Exception as exc:  # noqa: BLE001 - a malformed answer is a failed answer
            results.append((file, question, desc, None, "JSON 解析失败: %s" % exc))
            continue
        answer = norm(str(data.get("answers", {}).get(question, "")))
        try:
            passed = fn(answer)
        except Exception as exc:  # noqa: BLE001
            passed = False
            answer = "%s <%s>" % (answer, exc)
        results.append((file, question, desc, passed, answer))

    ok = 0
    for file, question, desc, passed, answer in results:
        mark = "PASS" if passed else "FAIL"
        if passed:
            ok += 1
        label = file.replace("answer-", "").replace(".json", "")
        print("%-12s %-3s %-4s %s" % (label, question, mark, desc))
        if not passed:
            print("     回答: %s" % answer[:280])
    print()
    print("%d/%d 题答对" % (ok, len(results)))
    return 0 if ok == len(results) else 1


if __name__ == "__main__":
    sys.exit(main())

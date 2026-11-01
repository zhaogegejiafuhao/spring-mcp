"""External tool example for spring-mcp.

Protocol: exactly one JSON object in on stdin, exactly one JSON object out on stdout,
exit code 0. Anything written to stderr is shown to the user when the tool fails.
"""
import json
import os
import sys


def main() -> int:
    request = json.load(sys.stdin)
    project_root = request["projectRoot"]
    arguments = request.get("arguments", {})
    include_tests = str(arguments.get("includeTests", "false")).lower() == "true"

    files = 0
    total_bytes = 0
    for dirpath, dirnames, filenames in os.walk(project_root):
        normalised = dirpath.replace("\\", "/")
        if "/target/" in normalised or "/.git/" in normalised or "/build/" in normalised:
            dirnames[:] = []
            continue
        if not include_tests and "/src/test" in normalised:
            dirnames[:] = []
            continue
        for name in filenames:
            if name.endswith(".java"):
                files += 1
                total_bytes += os.path.getsize(os.path.join(dirpath, name))

    json.dump(
        {
            "ok": True,
            "data": {"files": files, "bytes": total_bytes, "includeTests": include_tests},
            "summary": f"{files} 个 .java 文件，共 {total_bytes // 1024} KiB",
            "warnings": [] if files else ["没有找到 .java 文件，检查 projectRoot 是否正确"],
        },
        sys.stdout,
        ensure_ascii=False,
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())

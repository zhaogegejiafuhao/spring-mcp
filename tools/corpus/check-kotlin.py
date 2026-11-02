"""Kotlin 侧的独立对账：用正则从 .kt 源码里数出端点，和工具的输出逐条比。

为什么需要这个：Java 侧的准绳是别人写下的东西（前端 API 文件、集成测试里的 URI），写的时候
没考虑过这个工具。Kotlin 侧一直没有这种外部准绳——`KotlinContractTest` 的期望值是我自己从
fixture 里读出来再写进断言的，等于同一个人出了题又判了卷。

这里的准绳是**第二份实现**：正则不碰词法器、不建 AST、不共用任何代码。两边数出来一样才算过。
它能证明的范围有限——只覆盖正则能表达的那部分语法（单行字面量、`value = ["/x"]` 这种）——
但比自证强：任何一边改了却不一致，这张表立刻红。

用法：
    python tools/corpus/check-kotlin.py --jar target/spring-mcp-all.jar
    python tools/corpus/check-kotlin.py --jar ... --repos .corpus/kotlin
"""

import argparse
import collections
import json
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path

MAP = re.compile(r'@(GetMapping|PostMapping|PutMapping|DeleteMapping|PatchMapping|RequestMapping)\b')
VERB_OF = {
    'GetMapping': ['GET'],
    'PostMapping': ['POST'],
    'PutMapping': ['PUT'],
    'DeleteMapping': ['DELETE'],
    'PatchMapping': ['PATCH'],
}
DECL = re.compile(r'\b(class|interface|object)\s+\w+')
STR = re.compile(r'"([^"\\]*(?:\\.[^"\\]*)*)"')


def balanced(text, i):
    """@X( 之后的括号内容。"""
    while i < len(text) and text[i] in ' \t\r\n':
        i += 1
    if i >= len(text) or text[i] != '(':
        return '', i
    depth, start = 0, i + 1
    while i < len(text):
        c = text[i]
        if c == '"':
            i += 1
            while i < len(text):
                if text[i] == '\\':
                    i += 2
                    continue
                if text[i] == '"':
                    break
                i += 1
        elif c == '(':
            depth += 1
        elif c == ')':
            depth -= 1
            if depth == 0:
                return text[start:i], i + 1
        i += 1
    return text[start:], i


def path_args(args):
    """把注解参数里的路径取出来；取不出来就返回 None（交给"正则跳过"计数）。"""
    body = args.strip()
    if not body:
        return ['']
    m = re.search(r'\b(?:value|path)\s*=\s*', body)
    expr = body[m.end():] if m else body
    # 切到下一个顶层参数
    depth, cut = 0, len(expr)
    for i, c in enumerate(expr):
        if c in '[{(': depth += 1
        elif c in ']})': depth -= 1
        elif c == ',' and depth == 0:
            cut = i
            break
    expr = expr[:cut]
    if re.search(r'\$\{|\$[A-Za-z_]', expr):
        return None
    rest = STR.sub('', expr)
    rest = re.sub(r'[\s\[\],]', '', rest)
    if rest:                      # 还有标识符或 + 号：正则不猜
        return None
    return STR.findall(expr) or ['']


def verbs_of(name, args):
    if name in VERB_OF:
        return VERB_OF[name]
    m = re.search(r'\bmethod\s*=\s*\[?([^\]]*)\]?', args)
    if not m:
        return ['ANY']
    found = re.findall(r'RequestMethod\.(\w+)', m.group(1))
    return [v.upper() for v in found] or ['ANY']


def join(prefix, path):
    if prefix and path not in ('', '/'):
        if prefix.endswith('/') and path.startswith('/'):
            return prefix + path[1:]
        if not prefix.endswith('/') and not path.startswith('/'):
            return prefix + '/' + path
        return prefix + path
    return prefix or path or '/'


def normalise(path):
    if not path:
        return '/'
    return path if path.startswith('/') else '/' + path


def blank_comments(text):
    """把注释换成等长空格。

    不是为了好看：`DECL` 和 `MAP` 都在原文上跑，注释里出现 "object of type 'Vets'" 或一段被注释掉的
    `@GetMapping` 都会被当成真代码。替换成空格而不是删除，是为了让所有偏移量保持不变。
    """
    newline = chr(10)
    backslash = chr(92)
    out = list(text)
    i, n = 0, len(text)
    while i < n:
        c = text[i]
        if c == '"':
            i += 1
            while i < n:
                if text[i] == backslash:
                    i += 2
                    continue
                if text[i] == '"':
                    break
                i += 1
            i += 1
        elif c == '/' and i + 1 < n and text[i + 1] == '/':
            while i < n and text[i] != newline:
                out[i] = ' '
                i += 1
        elif c == '/' and i + 1 < n and text[i + 1] == '*':
            while i < n and not (text[i] == '*' and i + 1 < n and text[i + 1] == '/'):
                if text[i] != newline:
                    out[i] = ' '
                i += 1
            for _ in range(2):
                if i < n:
                    out[i] = ' '
                    i += 1
        else:
            i += 1
    return ''.join(out)


def expected_from_sources(root, skipped):
    """正则这一侧的答案：{(verb, path)} 及其重复次数。

    类前缀按「文件里那个紧贴声明的 `@RequestMapping`」取，并作用于该文件所有方法注解。
    这样假设了一个文件一个控制器——Spring 里绝大多数如此；真有多个控制器各自带前缀时，
    两边会对不上，表里会直接显示出来，不会静默通过。
    """
    out = collections.Counter()
    for path in Path(root).rglob('*.kt'):
        parts = {p.lower() for p in path.parts}
        if 'test' in parts or 'build' in parts:
            continue
        text = blank_comments(path.read_text(encoding='utf-8', errors='replace'))
        decls = [m.start() for m in DECL.finditer(text)]
        prefix = ''

        for m in MAP.finditer(text):
            args, ann_end = balanced(text, m.end())
            paths = path_args(args)
            if paths is None:
                skipped['complex'] += 1
                continue
            # 类级注解必须紧贴类声明：注解和下一个 class/interface/object 之间不能隔着
            # 花括号或 fun——接口里声明的方法注解也满足「在下一个类声明之前」，只看位置会误判。
            class_level = False
            for d in decls:
                if d > m.start():
                    class_level = not re.search(r'[{}]|\bfun\b', text[ann_end:d])
                    break
            if class_level:
                if m.group(1) == 'RequestMapping':
                    prefix = normalise(paths[0])
                continue
            for verb in verbs_of(m.group(1), args):
                for one in paths:
                    out[(verb, join(prefix, normalise(one) if one else ''))] += 1
    return out


def java_command():
    """java 的可执行路径：PATH 上没有时靠 JAVA_HOME。

    和 check.sh 里同一个坑：这台机器（以及多数 Windows 环境）java 不在 PATH 上，只有 JAVA_HOME，
    而 subprocess 不会像 shell 那样帮忙展开。
    """
    home = os.environ.get('JAVA_HOME')
    if home:
        candidate = Path(home) / 'bin' / ('java.exe' if os.name == 'nt' else 'java')
        if candidate.exists():
            return str(candidate)
    return 'java'


def actual_from_tool(jar, root):
    proc = subprocess.run(
        [java_command(), '-Dfile.encoding=UTF-8', '-jar', jar, '--dev', 'controllers', '--root', str(root)],
        capture_output=True, text=True, encoding='utf-8', errors='replace')
    if proc.returncode != 0:
        raise SystemExit('工具执行失败: %s' % (proc.stderr or proc.stdout)[:400])
    report = json.loads(proc.stdout)
    out = collections.Counter()
    for e in report['endpoints']:
        if not e['file'].endswith('.kt'):
            continue
        for verb in e['httpMethods']:
            out[(verb, normalise(e['path']))] += 1
    return out


def ensure_repos(repos_dir, list_file):
    """按 kotlin-repos.tsv 备好检出：commit 对得上就复用，否则重新拉。

    不做这一步的话，一个 clone 失败的仓库会以「工具 0 / 正则 0 / 一致」的形式通过——
    空对空永远相等，那是最坏的假绿。
    """
    wanted = []
    for line in Path(list_file).read_text(encoding='utf-8-sig').splitlines():
        line = line.strip()
        if not line or line.startswith('#'):
            continue
        name, url, sha = line.split('\t')
        target = Path(repos_dir) / name
        head = ''
        if (target / '.git').is_dir():
            head = subprocess.run(['git', '-C', str(target), 'rev-parse', 'HEAD'],
                                  capture_output=True, text=True).stdout.strip()
        if head != sha:
            if target.exists():
                # 浅克隆到已存在的目录会直接失败，先清掉
                shutil.rmtree(target, ignore_errors=True)
            subprocess.run(['git', 'clone', '--depth', '1', '-q', url, str(target)], check=False)
            after = subprocess.run(['git', '-C', str(target), 'rev-parse', 'HEAD'],
                                   capture_output=True, text=True).stdout.strip()
            if after != sha:
                raise SystemExit('取不到 %s@%s（当前 %s）' % (name, sha[:8], after[:8] or '空'))
        wanted.append(target)
    return wanted


def main():
    here = Path(__file__).resolve().parent
    ap = argparse.ArgumentParser()
    ap.add_argument('--jar', required=True)
    ap.add_argument('--repos', default=str(here.parent.parent / '.corpus' / 'kotlin'))
    ap.add_argument('--list', default=str(here / 'kotlin-repos.tsv'))
    ap.add_argument('--no-fetch', action='store_true', help='只用已有检出，不克隆')
    args = ap.parse_args()

    if args.no_fetch:
        repos = sorted(p for p in Path(args.repos).iterdir() if p.is_dir()) if Path(args.repos).is_dir() else []
    else:
        Path(args.repos).mkdir(parents=True, exist_ok=True)
        repos = ensure_repos(args.repos, args.list)
    if not repos:
        raise SystemExit('没有找到 Kotlin 仓库，先克隆到 %s' % args.repos)

    failures = 0
    totals = collections.Counter()
    for repo in repos:
        skipped = collections.Counter()
        want = expected_from_sources(repo, skipped)
        got = actual_from_tool(args.jar, repo)
        only_tool = got - want
        only_regex = want - got
        same = sum((got & want).values())
        print('%-46s 工具 %3d  正则 %3d  相同 %3d  只在工具 %d  只在正则 %d  正则跳过 %d'
              % (repo.name[:46], sum(got.values()), sum(want.values()), same,
                 sum(only_tool.values()), sum(only_regex.values()), skipped['complex']))
        for (verb, path), n in sorted(only_tool.items())[:5]:
            print('    只在工具: %s %s ×%d' % (verb, path, n))
        for (verb, path), n in sorted(only_regex.items())[:5]:
            print('    只在正则: %s %s ×%d' % (verb, path, n))
        totals['only_tool'] += sum(only_tool.values())
        totals['only_regex'] += sum(only_regex.values())
        totals['skipped'] += skipped['complex']
        if only_tool or only_regex:
            failures += 1

    print()
    if failures:
        print('%d / %d 个仓库不一致（只在工具 %d，只在正则 %d，正则跳过 %d）'
              % (failures, len(repos), totals['only_tool'], totals['only_regex'], totals['skipped']))
        return 1
    print('%d 个仓库全部一致（正则跳过 %d 处复杂表达式，未计入）'
          % (len(repos), totals['skipped']))
    return 0


if __name__ == '__main__':
    sys.exit(main())

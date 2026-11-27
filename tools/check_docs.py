#!/usr/bin/env python3
"""文档引用体检：docs/ 里写的「文件:行号」必须真的存在。

一次性的文档最容易出的毛病是：代码后来被改过，文档里的行号还停在老位置，
读者按图索骥找到的是另一段代码。这个脚本把 `path/to/File.kt:123`
和紧随其后的 `:456-789` 都当引用核对：

- 文件不存在 → 报错；
- 文件存在但行号超出总行数 → 报错；
- 反引号里没有行号、只有路径 → 只核对文件是否存在。

    python tools/check_docs.py            # 体检 docs/ 下所有 .md
    python tools/check_docs.py --strict   # 同时把「只有路径没行号」也当必须可解析（默认已检查）

退出码 1 表示有坏引用，CI 里会直接红。
"""
import argparse
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
# 文档里的路径写法不统一：有仓库根相对路径，也有 `data/LogFilter.kt` 这种相对模块的简写。
# 依次在这些根目录下找，找到第一个存在的就是它。
CANDIDATE_ROOTS = (
    "",
    "app/src/main/java/dev/loglens",
    "app/src/main",
    "app/src/main/res",
    "app/src/main/java",
    "app/src",
)


def resolve(rel: str):
    for root in CANDIDATE_ROOTS:
        p = ROOT / root / rel if root else ROOT / rel
        if p.exists():
            return p
    # 只写文件名的简写（如 `CaptureService.kt`、`file_paths.xml`）：在源码目录里按文件名找，
    # 只有唯一命中才算数，同名多个就报「歧义」而不是瞎猜一个。
    if "/" not in rel:
        hits = sorted((ROOT / "app" / "src").rglob(rel))
        if len(hits) == 1:
            return hits[0]
    return None

# `app/src/main/java/dev/loglens/X.kt:26-40` 或 `X.md`
REF = re.compile(
    r"`([A-Za-z0-9_./\-\u4e00-\u9fff]+\.(?:kt|kts|xml|md|py|yml|yaml|json|toml|gradle|properties))"
    r"(?::(\d+)(?:-(\d+))?)?`"
)
# 紧跟在后面的「续行号」写法：`:101-144`
TAIL = re.compile(r"`:(\d+)(?:-(\d+))?`")


def is_binary(rel: str) -> bool:
    return rel.endswith((".png", ".jpg", ".jar", ".apk"))


# ---------------------------------------------------------------- 数字校验
# 行号能被机器抓，数字以前只能靠人眼：README 写「70 条单测」时实际已经 76，
# audit-rules 写「556 个构件」时实际 559。这类漂移不影响功能，但会让人对
# 「这份文档是核对过的」这件事失去信任。
COUNT_RULES = (
    ("README.md", re.compile(r"(\d+)\s*条单测"), "tests", "条单测"),
    ("security/audit-rules.md", re.compile(r"(\d+)\s*个构件"), "components", "个构件"),
)


def count_tests() -> int:
    files = list((ROOT / "app" / "src" / "test").rglob("*.kt"))
    return sum(
        1
        for f in files
        for line in f.read_text(encoding="utf-8", errors="replace").splitlines()
        if line.strip().startswith("@Test")
    )


def count_components() -> int:
    xml = (ROOT / "gradle" / "verification-metadata.xml").read_text(encoding="utf-8", errors="replace")
    return len(re.findall(r"<component\s", xml))


COUNT_FUNCS = {"tests": count_tests, "components": count_components}


def check_counts():
    """文档里写死的计数必须和仓库实际一致；对不上就报错，由 CI 直接红。"""
    bad, checked = [], 0
    actual = {}
    for rel, pattern, key, unit in COUNT_RULES:
        p = ROOT / rel
        if not p.exists():
            continue
        if key not in actual:
            actual[key] = COUNT_FUNCS[key]()
        text = p.read_text(encoding="utf-8", errors="replace")
        for lineno, line in enumerate(text.splitlines(), 1):
            m = pattern.search(line)
            if not m:
                continue
            checked += 1
            if int(m.group(1)) != actual[key]:
                bad.append(
                    f"{rel}:{lineno} 写的 {m.group(1)} {unit} 与实际不符（实际 {actual[key]}）"
                )
    return bad, checked


def check_file(md: pathlib.Path, text: str):
    bad, checked = [], 0
    for lineno, line in enumerate(text.splitlines(), 1):
        last_path = None
        for m in REF.finditer(line):
            rel, start, end = m.group(1), m.group(2), m.group(3)
            target = resolve(rel)
            checked += 1
            if target is None:
                bad.append(f"{md.relative_to(ROOT)}:{lineno} 引用的文件不存在：{rel}")
                last_path = None
                continue
            last_path = target
            if start:
                total = len(target.read_text(encoding="utf-8", errors="replace").splitlines())
                hi = int(end or start)
                if int(start) < 1 or hi > total:
                    bad.append(
                        f"{md.relative_to(ROOT)}:{lineno} 行号越界：{rel}:{start}"
                        + (f"-{end}" if end else "")
                        + f"（该文件共 {total} 行）"
                    )
        for m in TAIL.finditer(line):
            if last_path is None:
                continue
            checked += 1
            total = len(last_path.read_text(encoding="utf-8", errors="replace").splitlines())
            if int(m.group(1)) > total:
                bad.append(f"{md.relative_to(ROOT)}:{lineno} 行号越界：{last_path.name}:{m.group(1)}（共 {total} 行）")
    return bad, checked


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--dir", default="docs")
    a = p.parse_args()

    base = ROOT / a.dir
    if not base.exists():
        print(f"没有 {a.dir} 目录，跳过")
        return 0

    bad, checked = [], 0
    for md in sorted(base.rglob("*.md")):
        text = md.read_text(encoding="utf-8", errors="replace")
        b, c = check_file(md, text)
        bad += b
        checked += c

    # 数字校验独立于 docs 目录：README 与 audit-rules 都在文档目录外
    count_bad, count_checked = check_counts()
    bad += count_bad

    if bad:
        print(f"发现 {len(bad)} 处坏引用/数字漂移（共核对 {checked} 处引用、{count_checked} 处计数）：")
        for x in bad[:60]:
            print("  " + x)
        if len(bad) > 60:
            print(f"  ……另有 {len(bad) - 60} 处")
        return 1
    print(f"文档引用全部有效（核对 {checked} 处引用、{count_checked} 处计数）")
    return 0


if __name__ == "__main__":
    sys.exit(main())



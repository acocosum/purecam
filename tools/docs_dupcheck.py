"""横向扫描所有文档，找出跨文件的重复内容。

思路：把每个文档按行归一化（去掉空白、标点、markdown 装饰符），
只保留长度 >= MIN_LEN 的行，统计同一行出现在哪些文件里。
"""
import os
import re
import sys
from collections import defaultdict

MIN_LEN = 24

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

FILES = [
    "README.md",
    "docs/setup-guide.md",
    "docs/calibration.md",
    "docs/limitations.md",
    "docs/verification-log.md",
    "docs/development-notes.md",
    "docs/blog-use-phone-as-switch2-webcam.md",
    "docs/blog-xiaohongshu.txt",
    "docs/release-notes-v1.0.0.md",
    "tools/README.md",
    "keystore/README.md",
]

# A 类：对外独立分发的稿件（博客平台、GitHub Releases 页面）。
# 读者不会去翻 docs/，所以它们与任何文档重复都是**允许的**，不参与判定。
EXTERNAL = {
    "docs/blog-use-phone-as-switch2-webcam.md",
    "docs/blog-xiaohongshu.txt",
    "docs/release-notes-v1.0.0.md",
}


def normalize(line: str) -> str:
    s = line.strip()
    if not s:
        return ""
    if s.startswith("```") or s.startswith("| ") or s.startswith("|---"):
        return ""                      # 代码块围栏 / 表格分隔行
    s = re.sub(r"^[#>\-*\d\.\s]+", "", s)   # 去掉标题号、列表号、引用号
    s = re.sub(r"[*`\[\]()\s]", "", s)      # 去掉 markdown 装饰与空白
    s = re.sub(r"[，,。．\.：:；;！!？?、—\-（）「」“”\"']", "", s)
    return s


def main():
    docs = {}
    for rel in FILES:
        path = os.path.join(ROOT, rel)
        if not os.path.exists(path):
            print(f"!! 缺失: {rel}")
            continue
        with open(path, encoding="utf-8") as f:
            docs[rel] = [normalize(l) for l in f]

    index = defaultdict(set)
    for rel, lines in docs.items():
        for n in lines:
            if len(n) >= MIN_LEN:
                index[n].add(rel)

    dupes = {n: files for n, files in index.items() if len(files) > 1}

    # 只看「仓库内部技术文档」之间的重复；涉外部稿件的重复一律算允许
    internal = {
        n: files for n, files in dupes.items()
        if not (files & EXTERNAL)
    }
    allowed = {
        n: files for n, files in dupes.items()
        if files & EXTERNAL
    }

    print(f"=== 内部技术文档之间的重复：{len(internal)} 条（应为 0）===\n")
    for n, files in sorted(internal.items(), key=lambda kv: -len(kv[1])):
        print(f"[{len(files)} 个文档] {n[:74]}")
        for f in sorted(files):
            print(f"      - {f}")
    if not internal:
        print("（无）")

    print()
    print(f"=== 涉及对外稿件的重复：{len(allowed)} 条（允许，仅供人工确认）===\n")
    for n, files in sorted(allowed.items(), key=lambda kv: -len(kv[1]))[:12]:
        print(f"[{len(files)}] {n[:66]}")
        print(f"      {', '.join(sorted(files))}")
    if len(allowed) > 12:
        print(f"      ... 另有 {len(allowed) - 12} 条")

    print("=== 各文档之间的重复条数矩阵 ===")
    rel_list = [r for r in FILES if r in docs]
    pairs = defaultdict(int)
    for n, files in dupes.items():
        fs = sorted(files)
        for i in range(len(fs)):
            for j in range(i + 1, len(fs)):
                pairs[(fs[i], fs[j])] += 1
    for (a, b), c in sorted(pairs.items(), key=lambda kv: -kv[1]):
        if c >= 3:
            print(f"  {c:3d} 条   {a}  <->  {b}")


if __name__ == "__main__":
    main()

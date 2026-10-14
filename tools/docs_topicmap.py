"""按「主题」而不是「字面」统计重复 —— 同一件事换个说法写在多个文档里，行级匹配抓不到。

对每个主题统计它在各文档中的提及次数，输出覆盖矩阵。
次数高 = 同一件事被反复讲。
"""
import os
import re

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

TOPICS = {
    "采集卡需1080p/UVC": r"1080p|2K|1440p|UVC",
    "画面拉伸补偿": r"拉伸|补偿值|校准图|非等比|anamorphic",
    "90度旋转问题": r"90°|转了\s*90|Surface\s*自带|surfaceRotation|旋转量",
    "息屏保护/背光": r"息屏|背光|brightness|BRIGHTNESS",
    "后台输出不可行": r"后台|屏幕镜像|Presentation|扩展模式",
    "CameraX 的问题": r"CameraX",
    "物理子摄路由": r"物理子摄|physicalCameraId|超广角",
    "AF-L 对焦锁定": r"AF-L|对焦锁定|MANUAL_SENSOR",
    "延迟/时域降噪": r"时域|降噪|FAST 档|延迟",
    "变换矩阵细节": r"setRectToRect|surfW|变换矩阵|宽高|各向异性",
    "签名/keystore": r"keystore|签名|\.jks|assembleRelease",
    "验证方法论": r"像素统计|硬边|相关系数|差异矩阵|不靠肉眼|非可视化",
    "三层UI状态机": r"三层|悬浮图标|3 秒|纯净画面",
}


def count(text: str, pat: str) -> int:
    return len(re.findall(pat, text, re.I))


def main():
    docs = {}
    for rel in FILES:
        p = os.path.join(ROOT, rel)
        if os.path.exists(p):
            docs[rel] = open(p, encoding="utf-8").read()

    short = {r: r.replace("docs/", "").replace(".md", "").replace(".txt", "") for r in docs}
    width = max(len(s) for s in short.values())

    print("=== 主题 × 文档 提及次数矩阵 ===\n")
    header = " " * (width + 2) + "".join(f"{short[r][:9]:>11}" for r in docs)
    print(header)
    print("-" * len(header))

    hot = []
    for topic, pat in TOPICS.items():
        counts = {r: count(t, pat) for r, t in docs.items()}
        spread = sum(1 for c in counts.values() if c > 0)
        row = f"{topic:<{width}}  " + "".join(
            f"{counts[r]:>11}" if counts[r] else f"{'·':>11}" for r in docs
        )
        print(row)
        if spread >= 4:
            hot.append((spread, topic, counts))

    print()
    print("=== 散布在 4 个及以上文档的主题（重点排查对象）===")
    for spread, topic, counts in sorted(hot, reverse=True):
        hits = ", ".join(f"{short[r]}×{c}" for r, c in counts.items() if c)
        print(f"\n[{spread} 个文档] {topic}")
        print(f"    {hits}")


if __name__ == "__main__":
    main()

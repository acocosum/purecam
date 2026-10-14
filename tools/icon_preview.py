"""按 ic_launcher_foreground.xml 的几何用 PIL 复刻，并**裁切到自适应图标的可见区**，
用来肉眼确认图标在启动器里的真实样子。

关键：108x108 画布中，系统只显示中心约 72x72（其余是出血区），
所以必须裁到中心 72 再看，否则会误判"内容太小"。
"""
from PIL import Image, ImageDraw

SS = 4                      # 超采样倍数
BG = (16, 46, 61)           # #102E3D
WHITE = (255, 255, 255)
ACCENT = (64, 196, 255)     # #40C4FF

CORNERS = [
    (32, 46, 32, 32, 46, 32),
    (62, 32, 76, 32, 76, 46),
    (76, 62, 76, 76, 62, 76),
    (46, 76, 32, 76, 32, 62),
]
CORNER_W = 6

# 可变参数：用于对比两套比例 / 有无高光
VARIANTS = {
    "A 大镜片无高光": dict(ring_r=13.0, ring_w=6.0, lens_r=7.5, hilite=None),
    "B 大镜片小高光": dict(ring_r=13.0, ring_w=6.0, lens_r=7.5, hilite=(51.8, 51.8, 1.7)),
    "C 原方案": dict(ring_r=12.25, ring_w=5.5, lens_r=6.5, hilite=(51.5, 51.5, 2.4)),
}


def render_icon(v: dict) -> Image.Image:
    s = 108 * SS
    img = Image.new("RGB", (s, s), BG)
    d = ImageDraw.Draw(img)
    k = s / 108.0

    def circle(cx, cy, r, **kw):
        d.ellipse([(cx - r) * k, (cy - r) * k, (cx + r) * k, (cy + r) * k], **kw)

    w = int(CORNER_W * k)
    for x1, y1, x2, y2, x3, y3 in CORNERS:
        pts = [(x1 * k, y1 * k), (x2 * k, y2 * k), (x3 * k, y3 * k)]
        d.line(pts, fill=WHITE, width=w, joint="curve")
        for px, py in (pts[0], pts[2]):
            circle(px / k, py / k, CORNER_W / 2, fill=WHITE)

    circle(54, 54, v["ring_r"], outline=WHITE, width=int(v["ring_w"] * k))
    circle(54, 54, v["lens_r"], fill=ACCENT)
    if v["hilite"]:
        circle(v["hilite"][0], v["hilite"][1], v["hilite"][2], fill=WHITE)

    # 裁到中心 72x72 的可见区
    m = 18 * SS
    return img.crop((m, m, s - m, s - m))


def masked(icon: Image.Image, px: int, shape: str) -> Image.Image:
    """缩放并用遮罩裁切后贴到浅灰底上，模拟启动器效果。"""
    icon = icon.resize((px * SS, px * SS), Image.LANCZOS)
    mask = Image.new("L", (px * SS, px * SS), 0)
    md = ImageDraw.Draw(mask)
    r = {"circle": px * SS // 2, "square": int(px * SS * 0.24)}[shape]
    if shape == "circle":
        md.ellipse([0, 0, px * SS - 1, px * SS - 1], fill=255)
    else:
        md.rounded_rectangle([0, 0, px * SS - 1, px * SS - 1], radius=r, fill=255)
    out = Image.new("RGB", (px * SS, px * SS), (245, 245, 245))
    out.paste(icon, (0, 0), mask)
    return out.resize((px, px), Image.LANCZOS)


if __name__ == "__main__":
    import os

    os.chdir(os.path.dirname(os.path.abspath(__file__)))

    BIG = 140
    PAD = 24
    GAP = 16
    cols = len(VARIANTS) * 2 + 2
    W = PAD * 2 + cols * BIG + (cols - 1) * GAP
    H = PAD * 2 + BIG + 22
    canvas = Image.new("RGB", (W, H), (245, 245, 245))

    x = PAD
    for name, v in VARIANTS.items():
        icon = render_icon(v)
        for shape in ("circle", "square"):
            canvas.paste(masked(icon, BIG, shape), (x, PAD))
            x += BIG + GAP
    for sz in (48, 72):
        canvas.paste(masked(render_icon(VARIANTS["A 大镜片无高光"]), sz, "circle"), (x, PAD))
        x += max(sz, 40) + GAP

    canvas.save("icon_preview.png")
    print("已生成 icon_preview.png", canvas.size, "| 变体顺序:", list(VARIANTS))

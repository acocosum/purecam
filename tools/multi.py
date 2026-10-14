"""多张截图的平均差矩阵：判断切换镜头后画面是否真的变了。"""
import sys, itertools
from PIL import Image, ImageChops

names = sys.argv[1:]
box = (500, 120, 1900, 960)
imgs = {n: Image.open(n).convert('RGB').crop(box) for n in names}


def mad(a, b):
    d = ImageChops.difference(a, b)
    px = list(d.getdata())
    return sum(sum(p) for p in px) / len(px) / 3


print(f"比对区域 {box}，数值 = 平均像素差（0=完全相同，>15=肉眼可辨）")
print("            " + "".join(f"{n[:9]:>11s}" for n in names))
for a in names:
    row = f"{a[:11]:12s}"
    for b in names:
        row += f"{mad(imgs[a], imgs[b]):11.2f}" if a != b else f"{'—':>11s}"
    print(row)

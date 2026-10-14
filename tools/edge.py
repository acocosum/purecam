"""找出预览区域的硬边界：逐列 / 逐行统计「最大亮度」与「非零像素占比」。

用法: edge.py 图.png [阈值]
黑边是纯黑（max=0），画面里哪怕很暗的区域也至少有非零像素，
用 max 而不是均值可以避免「场景本身就暗」造成的误判。
"""
import sys
from PIL import Image

im = Image.open(sys.argv[1]).convert('L')
w, h = im.size
px = im.load()
th = int(sys.argv[2]) if len(sys.argv) > 2 else 1

def colmax(x, y0=0, y1=None):
    y1 = y1 or h
    return max(px[x, y] for y in range(y0, y1, 2))

def rowmax(y, x0=0, x1=None):
    x1 = x1 or w
    return max(px[x, y] for x in range(x0, x1, 2))

cols = [x for x in range(w) if colmax(x) >= th]
rows = [y for y in range(h) if rowmax(y) >= th]
print(f"{sys.argv[1]} ({w}x{h}) 阈值>={th}")
if not cols or not rows:
    print("  全黑，未取到画面")
    sys.exit()
print(f"  内容 x={cols[0]}..{cols[-1]} (宽{cols[-1]-cols[0]+1})  y={rows[0]}..{rows[-1]} (高{rows[-1]-rows[0]+1})"
      f"  中心=({(cols[0]+cols[-1])//2},{(rows[0]+rows[-1])//2})")

# 边界附近的逐列亮度，确认是不是「一刀切」的硬边
def edge_profile(idx, func, total, name):
    print(f"  {name}（边界附近每 20px 取一列/行的最大亮度）:")
    lo = f"{name}-左/上: "
    for i in range(max(0, idx[0] - 60), min(total, idx[0] + 80), 20):
        lo += f"{i}:{func(i):3d} "
    print("   " + lo)
    hi = f"{name}-右/下: "
    for i in range(max(0, idx[-1] - 80), min(total, idx[-1] + 60), 20):
        hi += f"{i}:{func(i):3d} "
    print("   " + hi)

edge_profile(cols, colmax, w, "水平")
edge_profile(rows, rowmax, h, "垂直")

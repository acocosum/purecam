"""用「画面结构沿哪个轴变化」判断朝向 —— 不依赖场景明暗，只比「变化沿哪个轴」。

模拟器虚拟场景是**竖条色带**：沿水平方向（列→列）亮度变化大，沿垂直方向变化小。
先降采样到 48x27（色带宽度约 250~600px，降采样后仍有 5~11 列，足以捕捉），
再比较「相邻列之差」与「相邻行之差」的均值。

  竖直变化 / 水平变化 明显 > 1  =>  竖条  =>  与 CameraX 基准同向（正立）
  明显 < 1                       =>  横条  =>  相对基准转了 90°
"""
import sys
from PIL import Image

W, H = 48, 27


def axis_grad(name):
    g = Image.open(name).convert('L').resize((W, H), Image.BOX)
    px = g.load()
    dx = sum(abs(px[x, y] - px[x + 1, y])
             for y in range(H) for x in range(W - 1)) / (H * (W - 1))
    dy = sum(abs(px[x, y] - px[x, y + 1])
             for y in range(H - 1) for x in range(W)) / ((H - 1) * W)
    return dx, dy


for name in sys.argv[1:]:
    dx, dy = axis_grad(name)
    r = dx / dy if dy else float('inf')
    verdict = ("竖条 —— 与基准同向（正立）" if r > 1.6 else
               "横条 —— 相对基准转了 90°" if r < 0.62 else "无明显方向性")
    print(f"{name:16s} 列间变化={dx:5.2f}  行间变化={dy:5.2f}  dx/dy={r:5.2f}  -> {verdict}")

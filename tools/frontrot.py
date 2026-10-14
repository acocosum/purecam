"""前置摄像头横屏旋转量的决定性判定。

原理：摄像头刚性固定在设备上，**原始 buffer 在横屏/竖屏下完全相同**。
竖屏时两种公式（sensor±display）都给出 270°，所以竖屏画面 = 无歧义基准：

  竖屏画面 = 顺时针旋转 270°(原始buffer) 等比缩放
  横屏画面 = 顺时针旋转 rot°(原始buffer) 等比缩放，rot 只可能是 0 或 180

把竖屏画面再旋转回去就能还原原始 buffer，与横屏画面比对即可定案：
  横屏 rot=0   正确  ->  竖屏图 PIL rotate(270) 应匹配横屏图
  横屏 rot=180 正确  ->  竖屏图 PIL rotate(90)  应匹配横屏图
（PIL 的 rotate 是逆时针；Android postRotate 是顺时针。）
"""
import sys
from PIL import Image, ImageChops

land = Image.open(sys.argv[1]).convert('L')   # 横屏截图 2400x1080
port = Image.open(sys.argv[2]).convert('L')   # 竖屏截图 1080x2400

# 完整模式下：横屏内容 1440x1080 @ x=480..1920；竖屏内容 1080x1440 @ y=480..1920
Lc = land.crop((480, 0, 1920, 1080))
Pc = port.crop((0, 480, 1080, 1920))
print(f"横屏内容 {Lc.size}   竖屏内容 {Pc.size}")


def corr(a, b):
    a, b = list(a.getdata()), list(b.getdata())
    n = len(a)
    ma, mb = sum(a) / n, sum(b) / n
    va = sum((x - ma) ** 2 for x in a) ** .5
    vb = sum((x - mb) ** 2 for x in b) ** .5
    return sum((x - ma) * (y - mb) for x, y in zip(a, b)) / (va * vb)


for deg in (90, 270):
    r = Pc.rotate(deg, resample=Image.BICUBIC, expand=True)
    tag = {270: "=> 横屏 rot=0（sensor+display，当前实现）",
           90: "=> 横屏 rot=180（sensor-display，JPEG 标准式）"}[deg]
    if r.size != Lc.size:
        print(f"  竖屏图 rotate({deg}) 尺寸 {r.size} 与横屏不符，跳过")
        continue
    print(f"  竖屏图 rotate({deg:3d}°) vs 横屏图  相关系数 r={corr(Lc, r):+.4f}   {tag}")

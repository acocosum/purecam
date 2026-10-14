# 已知限制

## 适配范围

- **只在小米 17 上验证过。** 这是给自己用的工具，适配面很窄。
  其他机型大概率能用（Camera2 是标准 API），但没人替你测过。
- **采集卡必须支持 1080p 输出**，2K / 1440p 模式 Switch 2 不识别（见 [setup-guide.md](setup-guide.md)）。

## 功能上的限制

- **不提供「手动对焦距离滑杆」**：绝大多数手机模组不支持 `MANUAL_SENSOR`。
  取而代之提供更实用的 **AF-L 对焦锁定**（锁住当前焦点距离，固定机位输出时画面不会反复拉风箱）。
- **曝光补偿**依赖 `CONTROL_AE_COMPENSATION_RANGE`，个别机型返回空范围时会提示「当前设备不支持曝光补偿」。
- **固定 30fps 是目标值**，最终由相机 HAL 决定；部分机型在弱光下会自动降帧。
- **不提供左右 / 上下镜像**（v4 起移除）。视频通话场景下前置不镜像看起来更自然；
  确实需要镜像的话，在采集卡或播放端处理。
- **摄像头列表里可能出现两个选项画面相同**（一般等于主摄）：这是把 logical camera 本身
  和它的物理子摄都列出来的结果，用来保证「物理摄像头打不开时仍有可用入口」。
- **物理子摄路由需要设备 HAL 支持**（API 28+）。模拟器的虚拟前置摄像头带物理子摄（id 3/4）可验证；
  后置多焦段切换需真机确认。
- **`surfaceRotation` 硬编码为 90°**（绝大多数 Camera2 设备的实测值）。
  若你的设备恰好是 0/180/270，画面会多转 90°，点一次「画面旋转」即可摆正并自动记忆。
  不做自动探测的原因见 [development-notes.md](development-notes.md) 第 2.3 节
  （`SurfaceTexture#getTransformMatrix()` 含反射成分，无法可靠分解出旋转角）。

---

# 为什么做不到「后台输出相机画面」

**结论：在当前这条链路上做不到，而且不是实现问题，是架构决定的。**

## 原因

```
手机屏幕 ──(SurfaceFlinger 合成的 framebuffer)──> USB-C DP Alt Mode ──> HDMI ──> 采集卡 ──> Switch 2
                    ▲
        送出去的永远是「屏幕此刻显示的内容」
```

HDMI 走 DP Alt Mode 是**屏幕镜像**，送出去的是 SurfaceFlinger 合成出来的 framebuffer。
所以：**App 一退到后台，屏幕就被别的 app 占据，送出去的也就变成了别的 app。**
「后台输出相机画面 + 前台自由使用手机」这个需求，在这条链路上逻辑上就不成立。

几条容易走错的路：

| 想法 | 为什么不行 |
| --- | --- |
| 加前台服务让相机在后台继续跑 | 前台服务（`FOREGROUND_SERVICE_TYPE_CAMERA`）确实能让**相机**在后台继续工作，但送出去的仍是屏幕内容，相机画面根本到不了 HDMI |
| 用画中画（PiP） | 唯一能「接近」的方案：前台可以跑其他 app，相机画面在小窗持续显示。但 HDMI 输出里会**混入其他 app 的内容**，且 PiP 窗口尺寸受系统限制，做不到全屏 |
| 用「USB 用作 → 网络摄像头」 | Android 14+ 的系统级 UVC gadget 功能（仅部分机型提供），它送出去的同样是**屏幕内容**，不是相机数据流 |

## 唯一真正可行的分支：扩展模式（独立副屏）

如果设备的 USB-C 输出支持**扩展模式**（把外接显示器识别为一个**独立** display，而不是镜像），
就可以用：

```kotlin
val displays = displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
val presentation = Presentation(context, displays.first())
presentation.setContentView(previewLayout)   // 相机预览只画到副屏
presentation.show()
```

这样主屏可以照常使用其他 app，副屏持续输出相机画面。

**但前提是设备支持扩展模式** —— 手机厂商普遍把 USB-C 输出限制为镜像
（三星 DeX、华为 PC 模式这类是有意做的扩展模式，小米是否有需要实测）。

**怎么判断**：插上扩展坞后看手机是否弹出「桌面模式 / 扩展模式」提示；
或连 adb 执行 `adb shell dumpsys display | grep -c DisplayDevice`，结果 **> 1** 说明有独立副屏。

如果确认支持，这套改动是本项目唯一能实现「后台输出」的路径。

---

## 好在有个替代方案

「自动息屏保护」已经解决了「手机屏一直亮着」这个实际痛点：
60 秒无操作后关掉屏幕背光，**只关背光、不改 framebuffer**，所以手机屏黑了而投屏画面完全不变
（原理见 [development-notes.md](development-notes.md) 第 4.2 节）。

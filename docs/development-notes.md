# 开发笔记

这份笔记记录 PureCam 开发过程中踩过的坑和验证方法。

它不是 API 教程，而是**"书上不会写、但改代码前必须知道"**的东西。很多结论是靠像素统计
实测出来的，和直觉相反 —— 如果不知道，很容易在同一个地方反复摔跤。

文中的实测数字来自 Android 14 模拟器（Pixel 7 / x86_64）与小米 17。

---

## 一、相机层：为什么从 CameraX 重写成 Camera2

最初用的是 CameraX，撞了两堵墙，最后整体重写为 Camera2。

### 1.1 CameraX 选不中物理子摄

`Camera2CameraFactory#getAvailableCameraIds()` 只返回 `CameraManager#getCameraIdList()`，
也就是**只有逻辑摄像头**。而超广角 / 长焦通常藏在逻辑摄像头里面，是**物理子摄**
（`CameraCharacteristics#getPhysicalCameraIds()`）。

用 `CameraSelector.addCameraFilter` 按物理 id 过滤，匹配集是空的，CameraX 会**静默降级**回主摄。
表现就是：**「后置切了好几个焦段，画面完全不变」**。

### 1.2 CameraX 掌控不了变换矩阵

`PreviewView#getViewTransform()` 只在 `TextureViewImplementation`（`COMPATIBLE` 模式）下
`postConcat` 应用层矩阵；`SurfaceView`（`PERFORMANCE`）下直接打一行
`Logger.w("PreviewView needs to be in COMPATIBLE mode for the transform to work correctly.")`
然后**忽略所有应用层变换**。

而 `COMPATIBLE` 模式的矩阵又要叠加框架自己算的 Surface 变换，很容易和我们的旋转 / 补偿打架。

### 1.3 Camera2 的解法

路由到物理镜头（API 28+，`minSdk 29` 满足）：

```kotlin
val output = OutputConfiguration(surface).apply {
    if (isPhysical) setPhysicalCameraId(physicalId)
}
device.createCaptureSession(
    SessionConfiguration(SESSION_REGULAR, listOf(output), bgExecutor, sessionCallback)
)
```

注意 `openCamera()` 用的必须是**逻辑摄像头 id**，物理镜头只是把输出流路由过去。

配套的容错：如果某颗物理镜头被设备拒绝（`onConfigureFailed`），记进 `droppedIds`、
提示用户、自动回退到其逻辑摄像头，不会黑屏。

---

## 二、预览变换矩阵的四个坑

这是整个项目最容易出错的地方。四个坑层层叠加，任何一个错了画面就不对。

### 2.1 `setTransform()` 变换的是"目标四边形"，坐标系是 view 空间

默认（单位矩阵）时，**整块 buffer 已经被拉伸铺满整个 view**。

所以矩阵的第一步必须是把这个映射还原掉：

```kotlin
setRectToRect(RectF(0, 0, viewW, viewH), RectF(0, 0, surfW, surfH), Matrix.ScaleToFit.FILL)
// ScaleToFit.FILL = x/y 独立缩放，正好把 src 完整映射到 dst
```

少了这一步，后面的旋转中心、平移量、缩放支点会**全部错位**。实测 4:3 模式下画面会跑到
`x=480..2342`、底部缺 250px（正确应为 `x=480..1920`、满高）。官方 `Camera2Basic` 的
`configureTransform()` 也是这个顺序。

### 2.2 Surface 自带 90° 视觉旋转（最隐蔽的一个）

**现象**：画面看起来被顺时针旋转了 90°。

**成因**：Camera2 在创建 output stream 时，相机会通过 `native_window_set_buffers_transform`
把"传感器方向"写进 Surface。这个变换由 `TextureView` 在贴纹理时**自动叠加**，
应用层既拿不到、也改不掉。

**解法**：

```
我们实际要下发的旋转 = net − surfaceRotation + 用户微调
```

其中 `surfaceRotation = 90`（绝大多数 Camera2 设备的实测值）。

实测：模拟器上 `sensor=90, display=90` → `net=0`，但必须下发 **`rot=270`**（逆时针 90°）才能摆正
—— 差值正好是被 Surface 抢走的那 90°。

### 2.3 为什么不用 `getTransformMatrix()` 自动探测

看起来很合理，实际上走不通。实测该矩阵是：

```
[ 0 -1 ]
[ -1  0 ]     行列式 = −1
```

**行列式是 −1，说明它含反射成分**（GLConsumer 把 GL 的 y 翻转烘了进来）。
含反射的矩阵无法唯一分解为"旋转 + 翻转"，用 `atan2` 提角度必然得出错误结论 ——
实测它会给出 270°，而真实值是 90°，**正好差 180°**。它在 session 切换期间还会变脏。

**结论：自动探测这条路走不通**，改用"硬编码 90° + 用户可循环微调"这一稳健方案。
如果用户设备恰好不是 90°，点一次「画面旋转」就能摆正，并自动记忆。

### 2.4 三组宽高绝不能混用

`applyViewTransform()` 里同时存在三组宽高，用途完全不同：

| 变量 | 含义 | 用在哪 |
| --- | --- | --- |
| `bufW / bufH` | 相机输出的原始 buffer 尺寸（如 `1280×720`） | 只用于日志 |
| `surfW / surfH` | **Surface 变换之后、我们旋转之前**的有效宽高 | step 0 还原、旋转支点、平移量 |
| `effW / effH` | **最终呈现在屏幕上的**画面宽高 | 计算填充缩放 `scale` |

```kotlin
val surfSwapped = surfaceRotation == 90 || surfaceRotation == 270
val surfW = if (surfSwapped) bufH else bufW
val surfH = if (surfSwapped) bufW else bufH

val effSwapped = rotation == 90 || rotation == 270
val effW = if (effSwapped) surfH else surfW
val effH = if (effSwapped) surfW else surfH
```

**踩过的坑**：曾经用"我们下发的 `rotation`"去判断 `swapped`，于是 `scale` 算成了 **3.3333**
（把 1280×720 当成 720×1280 来算）。画面角度是对的，但只剩中间一小块被放大到糊 ——
用户反馈的"画面被拉伸变形"就是这个原因。

修正后的实测值：

| 模式 | buf | surf | rotation | eff | scale | 屏幕结果 |
| --- | --- | --- | --- | --- | --- | --- |
| 填充（16:9） | 1280×720 | 720×1280 | 270 | 1280×720 | **1.875** | 铺满 2400×1080 |
| 完整（4:3） | 1856×1392 | 1392×1856 | 270 | 1856×1392 | **0.776** | 1440×1080 居中，左右各 480 黑边 |

### 2.5 旋转量的公式

```
后置 = (sensorOrientation − displayRotation + 360) % 360
前置 = (sensorOrientation + displayRotation) % 360
```

前置为什么是「+」：与 CameraX `CameraOrientationUtil#getRelativeImageRotation` 一致
（源码注释："If flipping horizontally, rotations should be negative"）。

⚠️ **不要用 `JPEG_ORIENTATION` 的算法**。那条公式假设 Surface 上还带着"镜像 + 传感器旋转"的变换，
在我们的链路上会给前置摄像头**多转 180°**。

已用「同摄像头横屏 vs 竖屏」做过决定性验证：正解相关系数 **+0.97**，错误公式 **−0.42**。

---

## 三、非可视化验证方法论

调相机预览有个根本困难：**你没法一边改代码一边盯着屏幕看**。而且"画面转了 90° 还是没转"
这类问题，肉眼在模拟器小窗口里看很不准。

所以整个项目采用了**纯像素统计**的验证方式：不靠肉眼，全部用硬边检测、亮度剖面、
差异矩阵、相关系数来判定。脚本都在 [`../tools/`](../tools/)。

### 3.1 几个真正有用的手段

| 要验证什么 | 怎么做 |
| --- | --- |
| 黑边 / 居中 / 宽高 | 逐列逐行取**最大亮度**找硬边（用 max 不用均值，避免"场景本身暗"被误判成黑边） |
| 朝向对不对 | 降采样后比较"相邻列之差"与"相邻行之差"，比值 >1 是竖条、<1 是横条 |
| 换镜头画面真的变了吗 | 多图两两求平均绝对像素差；同一硬件的基线约 1.2，跨硬件明显更大（实测 4.6+） |
| 0° 还是 180° | 用一个**无歧义的姿态**（竖屏）做基准，转回去求相关系数，看正负号 |
| UI 状态机 | `adb shell input tap` + `uiautomator dump`，数层级节点和 bounds |

### 3.2 一条重要教训：粗判手段不能当结论

`banddir.py` 那种条纹方向判定看着很灵敏，但**模拟器虚拟场景会随时间缓慢漂移** ——
同一张基线图重复测，`dx/dy` 曾从 1.32 飘到"无明显方向性"。

所以它只能做**粗判**，最终结论一定要落回 `edge.py` 的几何数字（硬边坐标不会骗人）。

### 3.3 另一个教训：先确认基准本身是对的

排查"画面旋转 90°"时，我一度通过对比"当前输出 vs CameraX 基线"来判断朝向。
但那个基线本身也是在**同样的假设**下产生的 —— 如果假设错了，基线也错，
对比就失去了意义。

真正定案靠的是**两条独立证据交叉验证**：
1. 实验法：把 `rotateFix` 手动设成 270°，画面立刻恢复正常；
2. 数学法：`net=0` 时 `scale` 应该是 1.875，实测在错误配置下是 3.3333 —— 数字对不上，说明假设错了。

---

## 四、工程细节

### 4.1 系统 Toast 会排队，别用它做高频反馈

系统 `Toast` 由 `NotificationManagerService` 统一排队派发，时长固定 2 秒改不了。
连点按钮时会一个个排着队弹出来（MIUI / HyperOS 上尤其明显）。

**解法**：用自绘的覆盖层 `TextView`，新提示**直接覆盖**旧文本并重置计时。
配合 `dispatchTouchEvent` 统一处理交互，不用给每个 View 挂监听。

### 4.2 「屏幕熄灭但画面不变」是可以做到的

关键在 `WindowManager.LayoutParams#screenBrightness` 的语义 —— **它调的是背光，
不改变 SurfaceFlinger 合成出来的 framebuffer**。所以：

```kotlin
attrs.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_OFF   // 0f：背光关到最低
attrs.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE  // -1f：交还系统
```

手机屏黑了（OLED 上像素不发光，约等于息屏），但 **HDMI 输出的 framebuffer 完全不变**。

实测：静置后 `mScreenBrightnessOverrideFromWindowManager` 由 `NaN` → `0.0`，
息屏前后截图平均亮度 `196.98` → `197.67`（差 3.57，纯属相机场景漂移）——
证明画面内容确实没被动过。

### 4.3 延迟：钱花在哪

**延迟大户是时域降噪（TNR）** —— 它要拿前后多帧比对，天然引入 1~3 帧延迟。
对实时视频输出场景，这些处理收益很小（画面一直在动，多帧降噪反而容易拖影），代价却是实打实的。

压到 `FAST` 档（**不是 `OFF`**，`OFF` 会让噪点/紫边明显变差）：

```kotlin
set(NOISE_REDUCTION_MODE, NOISE_REDUCTION_MODE_FAST)
set(EDGE_MODE, EDGE_MODE_FAST)
set(COLOR_CORRECTION_ABERRATION_MODE, COLOR_CORRECTION_ABERRATION_MODE_FAST)  // API 33+
set(CONTROL_VIDEO_STABILIZATION_MODE, CONTROL_VIDEO_STABILIZATION_MODE_OFF)
// AF 改用 CONTINUOUS_VIDEO（为持续取流优化，比 CONTINUOUS_PICTURE 更平滑）
```

约省 33~66ms。**教训：凡是带"时域"字样的后处理，都是延迟的来源。**

### 4.4 自适应图标的两个坑

1. **内容要控制在中心直径 72dp 的安全圆内**（108dp 画布）。超出部分会被厂商遮罩裁掉。
2. **镜片上不要加圆形高光**。实测加了高光之后整个图形会被读成一只"**眼睛**"而不是相机镜头
   —— 哪怕高光缩到 r=1.7 也一样。纯色镜片反而最干净。

补充：`monochrome` 层（Android 13+ 主题图标）**要单独做**。系统只取该层的 alpha 当遮罩，
如果直接复用彩色前景，镜片与外环之间那 2.5dp 的间隙单色化后会糊成一团。

### 4.5 构建：Gradle Kotlin DSL 里的一个坑

在 `build.gradle.kts` 顶层写 `java.util.Properties()` 会报 `Unresolved reference: util`
—— 因为 `java` 被 Gradle 的 `JavaPluginExtension` 遮蔽了。必须显式 `import java.util.Properties`。

### 4.6 签名密钥不要进版本库

原则一句话：**仓库不含任何 keystore**；有 `keystore/release.jks` 就用它，
没有则自动回退 debug 签名。这样 clone 下来不用任何配置就能构建，也不会泄露密钥。

具体的 keytool 命令和 `local.properties` 写法见 [keystore/README.md](../keystore/README.md)，
这里不重复。

### 4.7 其它工程细节

- **只建一条 repeating request**：预览用 `TEMPLATE_PREVIEW`，不挂 `ImageAnalysis` / `ImageCapture`，
  少一条数据通路就少一份调度开销。
- **分辨率挑选**：走 `SCALER_STREAM_CONFIGURATION_MAP.getOutputSizes(SurfaceTexture::class)`，
  取目标比例（16:9 / 4:3）内、不超过 1920×1080（4:3 为 1920×1440）的最大档。
  用 `SurfaceTexture` 的尺寸列表而不是 `SurfaceView` 的，避免选到设备不支持的档位。
- **固定横屏**：`requestedOrientation = SCREEN_ORIENTATION_LANDSCAPE`。
  Manifest 里仍然声明 `configChanges="orientation|screenSize|..."`，
  `onConfigurationChanged` **只重算矩阵、不重建相机会话**，所以系统栏短暂出现这类配置变化不会黑帧。
- **沉浸式**：`WindowInsetsControllerCompat` 隐藏系统栏 + 旧版 `SYSTEM_UI_FLAG_IMMERSIVE_STICKY` 双保险；
  `layoutInDisplayCutoutMode = SHORT_EDGES` 让画面延伸到打孔区域。
- **生命周期**：`onResume` 起相机、`onPause` 解绑、`onDestroy` shutdown。
  注意 `onPause` 里必须**同时恢复屏幕亮度**（`cancelAutoDim()`），
  否则切到后台后屏幕还是黑的，看起来像卡死。

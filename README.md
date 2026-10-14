# PureCam

把 Android 手机变成一个「纯净的全屏相机画面输出源」——用于通过 USB-C（DisplayPort Alt Mode）扩展坞输出 HDMI，再经 HDMI 采集卡接入 Nintendo Switch 2，作为其 USB 摄像头使用（GameChat / 支持摄像头功能的游戏）。

**相关文档**

- [技术博客：我用一台小米 17 给 Switch 2 当了摄像头](docs/blog-use-phone-as-switch2-webcam.md) —— 为什么做、踩了哪些坑
- [开发笔记](docs/development-notes.md) —— Camera2 / 变换矩阵的坑、非可视化验证方法论
- [验证工具箱](tools/) —— 用像素统计（而非肉眼）验证画面是否正确的脚本

- 包名：`com.purecam.app`
- 语言：Kotlin
- minSdk 29（Android 10）/ targetSdk 34（Android 14）
- 相机：**Camera2**（自建 `SurfaceTexture` + `TextureView`，变换矩阵 100% 自控）
- UI：纯代码动态构建（FrameLayout + TextureView + 悬浮图标 + 底部面板），无 XML 布局、无 Material 依赖
- 许可：[MIT](LICENSE)

> **v3 变更**：相机层已从 CameraX 重写为 Camera2。原因是 CameraX 既无法把画面路由到物理子摄（超广角/长焦），也无法让我们完全掌控变换矩阵——详见「七、关键实现说明」。
>
> **v4 变更**：① 只保留**横屏**，删除竖屏模式；② 删除左右/上下镜像（用不到）；③ **修复后置切换后画面被顺时针旋转 90°、且 4:3 全画幅被拉伸变形**的问题——根因是 Surface 自带 90° 视觉旋转没有被扣掉，且「还原步」用错了宽高基准。详见「七、关键实现说明」第 3、4 条。
>
> **v4.1 变更**：① 提示条改为自绘（连点按钮不再排队弹出）；② 新增**自动息屏保护**（手机屏熄灭但投屏画面不变）；③ 相机后处理压到低延迟档。详见「一、功能」与「七、关键实现说明」第 6、7 条。
>
> **v4.2 变更**：重做启动图标（取景框四角 + 镜头圆环，深青蓝底），并补充「十、为什么做不到后台输出」的技术结论。

---

## 一、功能

| 功能 | 说明 |
| --- | --- |
| 全屏预览 | 自建 `TextureView`，一个矩阵搞定「还原 + 旋转 + 填充/适配 + 拉伸补偿」 |
| 分辨率 | 自动挑选相机硬件**支持**的、不超过 1920×1080（4:3 为 1920×1440）、最接近目标比例的档位 |
| 帧率 | 固定 30fps（`CONTROL_AE_TARGET_FPS_RANGE = Range(30,30)`，HAL 不支持时不下发） |
| **多摄像头切换** | 枚举**逻辑摄像头 + 其物理子摄**（超广角 / 广角 / 长焦 + 前置），按 35mm 等效焦距自动命名并循环切换，记住上次选择 |
| **固定横屏** | 始终 `SCREEN_ORIENTATION_LANDSCAPE`，不再提供竖屏（本应用唯一用途就是给 Switch 2 输出横屏画面） |
| **画面旋转微调** | 一键叠加 0 / 90 / 180 / 270°（自动记忆，非 0 时按钮点亮）。兜底两类情况：个别设备 Surface 自带旋转量不是 90°；扩展坞/采集卡装反导致画面上下颠倒 |
| 屏幕常亮 | `FLAG_KEEP_SCREEN_ON` |
| **自动息屏保护** | 60 秒无操作后把**屏幕背光关到最低**——只动背光、不动 framebuffer，于是「手机屏熄灭」而 **HDMI 投屏画面完全不变**；任意触摸立即恢复。可一键关闭（默认开） |
| **低延迟管线** | 相机后处理压到 `FAST` 档（去掉时域降噪的多帧延迟）+ 关电子防抖 + 对焦改用 `CONTINUOUS_VIDEO`，省下约 **1~2 帧（33~66ms）**；详见「七、第 7 条」 |
| 操作提示 | 自绘提示条（1.2s 淡出）。新提示**直接覆盖**旧文本，**不会像系统 Toast 那样排队逐个弹出** |
| 三层 UI 状态机 | 纯净画面 → 悬浮图标 → 完整 UI 面板（详见下文） |
| 对焦 / 曝光 | 默认连续自动对焦 + 自动曝光；提供曝光补偿 ±、中心对焦、对焦锁定 AF-L（固定机位必备） |
| **画面模式（含源画幅）** | **填充**：16:9 源，裁上下填满屏幕；**完整**：**4:3 全画幅**，拿满传感器视野（左右留黑边）；**16:9框**：限制在屏幕中央 16:9 区域 |
| **垂直拉伸补偿** | 可调 0.50~1.50 的垂直缩放，抵消采集卡 / Switch 2 链路的非等比变形，带预设 1.00 / 0.75 / 0.80 / 1.33，自动记忆 |
| **校准图案** | 叠加标准正方形网格 + 同心圆，一眼看出链路是压扁还是拉高 |

---

## 二、UI 三层状态机

```
[纯净画面] --点击画面--> [悬浮图标] --点击图标--> [完整 UI 面板]
     ^                       |                        |
     |                       | 3 秒无操作自动淡出      | 点击「隐藏」或面板外区域
     +-----------------------+------------------------+
```

- **纯净画面**：只有相机预览，屏幕上没有任何按钮/图标。点击任意位置 → 显示右上角悬浮图标。
- **悬浮图标**：右上角 48dp 半透明相机图标。**3 秒**无操作自动淡出回纯净画面；点击画面其它区域不产生 UI 变化（仅刷新计时）。点击图标 → 展开完整 UI 面板。
- **完整 UI 面板**：底部控制面板，一级菜单只有 5 项：
  1. **切换摄像头**：在**所有可用摄像头（含物理子摄）**之间循环，提示条显示当前名称与 cameraId，状态行常驻显示当前镜头
  2. **画面旋转**：循环 `0° → 90° → 180° → 270°`，非 0 时图标变青蓝色
  3. **息屏保护**（默认开启）：60 秒无操作后关掉屏幕背光，**投屏画面完全不变**，任意触摸唤醒；关闭后屏幕常亮
  4. **`AF/AE`**：展开/收起高级面板，内含：
     - 曝光补偿 `−` / `EV 值` / `+`、`中心对焦`、`AF-C 连续` ⇄ `AF-L 锁定`
     - `拉伸 −/+`（数值即当前垂直补偿比例）、`预设`（1.00 / 0.75 / 0.80 / 1.33 循环）
     - `填充 / 完整 / 16:9框`：画面模式（同时决定相机源画面比例）
     - `校准图`：叠加正方形网格 + 同心圆
     - 状态行：当前分辨率 · 帧率 · 镜头
  5. **隐藏控制面板**（回到悬浮图标状态，并重新开始 3 秒计时）
- **点击面板内的灰色空白区域不会有任何反应**（防误触）；只有点击**面板外的画面区域**才会收起面板。返回键在面板展开时先收起面板。
- 面板外层包了 `ScrollView`，二级菜单再高也不会把一级菜单顶出屏幕。

---

## 三、安装

### 方式 A：下载已编译的 APK

到本仓库的 **Releases** 页面下载 `PureCam-release.apk`，手机上点击安装即可。

```bash
adb install -r PureCam-release.apk
```

> 签名密钥**不在仓库里**（不该把 jks 提交到版本库）。Release 页里的包是用本地自签名证书构建的；
> 如果你自己重新编译，签名会不同 —— 切换不同来源的包之前需要先
> `adb uninstall com.purecam.app`，否则会报 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`。

### 方式 B：自行编译（推荐）

环境要求：JDK 17 + Android SDK（platform 34 / build-tools 34）+ Gradle 8.9。

```bash
# 1. 配置 SDK 路径（编辑 local.properties）
sdk.dir=<你的 Android SDK 路径>

# 2. 构建
./gradlew assembleDebug      # 调试包
./gradlew assembleRelease    # 发布包

# 产物
app/build/outputs/apk/debug/app-debug.apk
app/build/outputs/apk/release/app-release.apk
```

**不需要任何额外配置**：仓库里没有 keystore 时，`assembleRelease` 会自动回退到 debug 签名，
clone 下来就能直接构建出可安装的包。

想用自己的正式签名，就把 jks 放到 `keystore/release.jks`，并在（已被 gitignore 的）
`local.properties` 里补上三行：

```properties
purecam.storePassword=你的口令
purecam.keyAlias=你的别名
purecam.keyPassword=你的口令
```

用 Android Studio 直接打开本目录即可（Gradle Kotlin DSL 项目）。

首次启动需授予**相机权限**。

---

## 四、连接 Nintendo Switch 2 测试

### 链路一：手机 HDMI 输出 + HDMI 采集卡（推荐，通用性最好）

```
小米 17 (USB-C, DP Alt Mode)
   └─ USB-C 扩展坞（带 HDMI 输出）
        └─ HDMI 线
             └─ HDMI 采集卡（UVC，免驱）
                  └─ USB-C / USB-A
                       └─ Nintendo Switch 2
```

要点：
1. **小米 17 支持 USB-C DisplayPort 输出**（USB 3.2 Gen2 + DP Alt Mode），所以有线 HDMI 方案可行。若插上扩展坞后电视无画面，请在系统「USB 用途」里确认没有误选为「仅充电 / 传输文件」。
2. 采集卡必须是**标准 UVC（USB Video Class）、免驱**的型号。Switch 2 对摄像头的兼容性偏保守：不少新款高清摄像头因为上报的模式太多反而无法识别，社区经验是**支持 UVC、且提供 480p/720p/1080p 简单 MJPEG 或 YUY2 模式**的采集卡更容易被识别。
3. 采集卡输出建议设为 **1080p30 或 720p30**（与应用内固定 30fps 一致）。
4. 手机屏幕比例是 **19.5:9**，横屏时 HDMI 输出的也是这个比例，采集卡送进 Switch 2 后可能被拉伸或裁切。此时请打开应用内的 **`AF/AE → 16:9框`**：应用会把预览限制为屏幕中央的 16:9 区域、四周留黑，采集卡输出的就是标准 16:9 画面。
5. 延迟构成：手机编码/输出（很小）+ 采集卡（约 30~100ms）+ Switch 2 合成。用于 GameChat 视频通话完全可接受。

### 链路二：手机直接作为 UVC 摄像头（无需采集卡）

Android 原生支持「USB 用作 → 网络摄像头（Webcam）」模式（Pixel 机型在 Android 16 上已确认可被 Switch 2 直接识别）。部分 HyperOS / 定制 ROM 机型可能没有该选项，需要：

- 下拉通知栏查看「USB 用途 / USB 偏好」中是否有「网络摄像头」；或
- 开发者选项中查找相关开关。

若有该选项，手机可直接用 USB-C 数据线连 Switch 2，无需采集卡。此时 PureCam 负责的仍然是「输出干净、全屏的画面」——系统会把屏幕内容作为 UVC 视频流送出。

### 排查清单

| 现象 | 检查 |
| --- | --- |
| Switch 2 完全不识别 | 换一个已知可用的 UVC 摄像头/采集卡交叉验证；优先选支持 480p 模式的产品；插底座而非机身试试 |
| 有画面但比例怪异/被裁 | 打开应用内 `16:9框` 输出模式 |
| **画面被转了 90°** | 点一次「画面旋转」（转 90°）即可摆正，会自动记忆。若转了 180°，点两次 |
| 画面上下颠倒 | 扩展坞/采集卡安装方向导致时，点两次「画面旋转」（180°）。v4 起已移除垂直镜像，用旋转替代 |
| 画面被拉高 / 压扁 | 打开「校准图」，用「拉伸 ±」调到圆变正圆；常见值 0.75（详见第五章） |
| 画面卡顿 | 采集卡是否支持 30fps；手机是否过热降频（相机长时间预览会发热，建议拆壳/通风） |
| 屏幕自己变黑了 | 息屏保护生效（默认 60s 无操作）。**投屏画面不受影响**，摸一下手机屏就亮。想彻底关掉就点「息屏保护」关掉开关 |
| 提示条一闪而过看不清 | 自定义提示条默认停留 1.2s；错误类提示会停留 3~4s |
| 画面模糊 / 只剩中间一小块 | v4 之前若把「还原步」的宽高基准写错，会按 3.33 倍过度放大。当前版本正常情况下日志里 `scale` 应为 **1.875**（填充）/ **0.776**（完整），明显偏离请 `adb logcat \| grep PureCamDbg` 反馈 |
| 切了后置焦段画面不变 | v3 之前是 CameraX 无法路由物理子摄（**已知根因，已修复**）。现改为 Camera2 `OutputConfiguration.setPhysicalCameraId`；若某颗镜头被设备拒绝，会 Toast 提示并自动从列表剔除 |
| 一会就黑屏 | 应用已开启屏幕常亮；若仍黑屏，检查系统省电策略是否把应用冻结，把 PureCam 加入后台豁免/电池无限制 |

---

## 五、画面被拉伸 / 有黑边：校准指南

你的链路里有三处可能改变画面比例：

```
手机屏幕(19.5:9 或 16:9) --DP 输出--> 采集卡(UVC 帧, 可能是 4:3) --> Switch 2 显示窗口
```

任何一环做「非等比（anamorphic）缩放」，圆就会变成椭圆。

### 1. 最快的诊断：把采集卡插电脑

用 OBS / 系统相机应用打开采集卡，直接看两件事：

1. **采集卡实际输出分辨率**：`720×480`、`640×480`、`1024×768` 是 4:3；`1280×720`、`1920×1080` 是 16:9。
2. **画面有没有被拉伸**：用一个正方形物体（或手机上开本应用的校准图）对着看。

如果采集卡输出的是 4:3，而你的源是 16:9，那采集卡只有两种做法：
- **FIT（等比 + 上下黑边）**：不变形，只有黑边；
- **Stretch（非等比填满）**：横向被压缩 `(4/3)/(16/9) = 0.75`，画面看起来「被拉高」——这正是你看到的现象。

### 2. 应用内实测校准（不需要电脑）

1. 面板 → `AF/AE` → `校准图`：屏幕叠加**标准正方形网格 + 同心圆**（不受补偿影响，是判断基准）。
2. 看 Switch 2 上的图案：
   - 圆变成**竖椭圆**（高 > 宽，即「拉高」）→ 需要把补偿值调到 **< 1.00**
   - 圆变成**横椭圆**（宽 > 高，即「压扁」）→ 需要把补偿值调到 **> 1.00**
3. 用 `拉伸 −` / `拉伸 +` 调到圆变正圆为止（步进 0.01），数值自动记忆，下次打开保持。

### 3. 想直接算：公式与常见值

设链路对画面做了横向压缩 `a`、纵向压缩 `b`，Switch 2 再各向同性显示。一个正方格在 Switch 2 上量得宽 `W`、高 `H`，则：

```
补偿值 k = W / H
```

| 现象 | W/H | 补偿值 | 典型链路 |
| --- | --- | --- | --- |
| 画面被拉高（源 16:9 → 4:3 帧 stretch） | 0.75 | **0.75** | 最常见，直接按 `预设` 切到 0.75 试试 |
| 画面被压扁（源 4:3 → 16:9 显示） | 1.33 | **1.33** | 4:3 采集卡 + 16:9 显示窗口拉伸 |
| 轻微变形 | 0.80~0.95 | 微调 | 部分采集卡带 overscan 裁切 |

`预设` 按钮会在 `1.00 → 0.75 → 0.80 → 1.33` 之间循环，多数情况下先把 0.75 试一遍就能看出来对不对。

### 4. 关于黑边

- 上下黑边通常是 FIT 缩放的产物（属正常，且是不可避免的「安全区」）。
- 如果**黑边 + 变形同时出现**，说明链路里某一环先 stretch、再 FIT。此时先按上面的方法把补偿调准，再配合 `16:9框` 输出模式，让有效画面正好落在目标区域里。
- 补偿值 < 1.00 时画面上下会多出黑边，这本来就是「抵消拉伸」的代价；如果最终出现在 Switch 2 上的黑边变多了，说明方向调反了。

---

## 六、项目结构

```
PureCam/
├── app/
│   ├── build.gradle.kts
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/purecam/app/
│       │   ├── MainActivity.kt        # 全屏/常亮/固定横屏 + 三层状态机 + 变换矩阵 + 息屏保护
│       │   ├── CameraController.kt    # Camera2 封装：会话、物理子摄路由、分辨率挑选、对焦/曝光、低延迟
│       │   ├── CalibrationOverlay.kt  # 校准图案（正方形网格 + 同心圆）
│       │   └── UiFactory.kt           # 动态创建图标/文字按钮、圆角背景、着色
│       └── res/
│           ├── drawable/              # 图标（vector，无 PNG 依赖）
│           ├── mipmap-anydpi-v26/     # 自适应启动图标
│           └── values/                # strings / themes / colors
├── docs/
│   ├── blog-use-phone-as-switch2-webcam.md   # 技术博客
│   └── development-notes.md                  # 开发笔记（踩坑 + 验证方法论）
├── tools/                             # 非可视化验证脚本（像素统计，含 README）
├── keystore/                          # 放你自己的签名（*.jks 已被 gitignore，仓库内为空）
├── build.gradle.kts / settings.gradle.kts
├── LICENSE
└── README.md
```

---

## 七、关键实现说明（七个要点，改动前务必读完）

### 1. 为什么用 Camera2 而不是 CameraX

- **CameraX 选不中物理子摄**。源码 `Camera2CameraFactory#getAvailableCameraIds()` 只返回
  `CameraManager#getCameraIdList()`，即只含**逻辑摄像头**；超广角 / 长焦往往是藏在逻辑摄像头里的
  **物理子摄**（`CameraCharacteristics#getPhysicalCameraIds()`）。用 `CameraSelector.addCameraFilter`
  按物理 id 过滤会匹配为空并静默降级回主摄 —— 表现就是「后置切了好几个焦段，画面完全不变」。
- **CameraX 掌控不了变换矩阵**。`PreviewView#getViewTransform()` 只在
  `TextureViewImplementation`（`COMPATIBLE` 模式）下 `postConcat` 应用层矩阵，
  `SurfaceView`（`PERFORMANCE`）下直接打 `Logger.w("PreviewView needs to be in COMPATIBLE mode…")`。
  而 `COMPATIBLE` 模式的矩阵又要叠加框架自己算的 Surface 变换，很容易和我们的旋转/补偿打架。

Camera2 的写法（API 28+，minSdk 29 满足）：

```kotlin
val output = OutputConfiguration(surface).apply { setPhysicalCameraId(physicalId) }
device.createCaptureSession(
    SessionConfiguration(SESSION_REGULAR, listOf(output), bgExecutor, sessionStateCallback)
)
```

注意 `openCamera()` 用的必须是**逻辑摄像头 id**，物理镜头只是把输出流路由过去。
若某颗物理镜头被设备拒绝（`onConfigureFailed`），本应用会记进 `droppedIds`、Toast 提示，
并自动回退到其逻辑摄像头，不会黑屏。

### 2. `TextureView#setTransform()` 的坐标系（最容易踩的坑）

**变换矩阵作用在「目标四边形」上，坐标系是 view 空间，不是 buffer 空间。**
默认（单位矩阵）时，整块 buffer 已经被拉伸铺满整个 view。
所以矩阵的第一步必须是把 view 矩形还原成目标矩形：

```kotlin
setRectToRect(RectF(0, 0, viewW, viewH), RectF(0, 0, surfW, surfH), Matrix.ScaleToFit.FILL)
// ScaleToFit.FILL = x/y 独立缩放，正好完整映射
```

⚠️ 这里的 `surfW / surfH` 必须是 **Surface 变换之后**的有效宽高（见第 3 条），**不是** `bufW / bufH`。
少了这一步，后面的旋转中心、平移量、缩放支点会全部错位。
实测 4:3 模式下画面会跑到 `x=480..2342`、底部缺 250px。
官方 `Camera2Basic` 的 `configureTransform()` 也是这个顺序。

### 3. Surface 自带的 90° 视觉旋转（v4 修的就是这个）

**现象**：v3 在小米 17 与模拟器上，后置摄像头切换后画面被**顺时针旋转 90°**，
而且因为源是 4:3 全画幅，转 90° 后又被拉伸变形。

**根因**：Camera2 在创建 output stream 时，相机会通过 `native_window_set_buffers_transform`
把「传感器方向」写进 Surface。这个变换由 `TextureView` 在贴纹理时**自动叠加**，
应用层拿不到、也改不掉。

因此：

```
我们实际要下发的旋转 = net − surfaceRotation + rotateFix
net = 后置 (sensor - display + 360) % 360
      前置 (sensor + display) % 360        // 与 CameraX getRelativeImageRotation 一致
surfaceRotation = 90                       // 绝大多数 Camera2 设备；可用「画面旋转」按钮兜底
```

模拟器实测（Pixel 7 / Android 14，横屏 2400×1080，`sensor=90 display=90`）：
`net=0`，需要下发 `rot=270`（即逆时针 90°）才能摆正 —— 正好被 Surface 抢转了 90°。

> **为什么不用 `getTransformMatrix()` 自动探测**：实测该矩阵是 `[0,-1;-1,0]`，
> **行列式 = −1**，也就是说它含**反射**成分（GLConsumer 会把 GL 的 y 翻转烘进去），
> 无法唯一分解为「旋转 + 翻转」，用 `atan2` 提角度必然得到错误结论
> （会给出 270°，而真实值是 90°，正好差 180°）。它在 session 切换期间还会变脏。
> 结论：**自动探测这条路走不通**，改用「硬编码 90 + 用户可循环微调」这一稳健方案。

### 4. 两个「宽高」绝不能混用（会让画面各向异性拉伸）

`applyViewTransform()` 里同时存在两组宽高，用途完全不同：

| 变量 | 含义 | 用在哪 |
| --- | --- | --- |
| `bufW / bufH` | 相机输出的原始 buffer 尺寸（如 `1280×720`、`1856×1392`） | 只用于日志 |
| `surfW / surfH` | **Surface 变换之后、我们旋转之前** 的有效宽高（`surfaceRotation` 为 90/270 时两者互换） | step 0 还原、旋转支点、平移量 |
| `effW / effH` | **最终呈现在屏幕上的画面**宽高（在 `surf` 基础上再按我们下发的 `rotation` 互换） | 计算填充缩放 `scale` |

三者的关系（`surfSwapped = surfaceRotation ∈ {90,270}`，`effSwapped = rotation ∈ {90,270}`）：

```kotlin
val effW = if (effSwapped) surfH else surfW
val effH = if (effSwapped) surfW else surfH
```

**实测踩坑**：v4 之前的错误版本用「我们下发的 `rotation`」去判断 `swapped`，
于是 `scale` 变成 **3.3333**（把 1280×720 当成 720×1280 来算），
画面虽然角度对了，但只剩中间一小块被放大到糊。修正后：

| 模式 | buf | surf | rotation | eff | scale | 屏幕结果 |
| --- | --- | --- | --- | --- | --- | --- |
| 填充（16:9） | 1280×720 | 720×1280 | 270 | 1280×720 | **1.875** | 铺满 2400×1080 ✓ |
| 完整（4:3） | 1856×1392 | 1392×1856 | 270 | 1856×1392 | **0.776** | 1440×1080 居中，左右各 480 黑边 ✓ |

### 6. 自动息屏保护：只关背光，不动画面

**需求**：长时间无操作时让手机屏幕熄灭（防烧屏、省电），但**投屏画面必须完全不变**。

**为什么能同时做到**：关键在 `WindowManager.LayoutParams#screenBrightness` 的语义 ——
它调的是**背光**，作用在显示硬件上，**不改变 SurfaceFlinger 合成出来的 framebuffer**。于是：

- 手机屏幕变黑（OLED 上像素完全不发光，约等于息屏）；
- **HDMI 镜像输出的是 framebuffer，因此投屏画面完全不受影响**；
- CPU / 相机 / 预览管线全部照常运行，`FLAG_KEEP_SCREEN_ON` 也不用摘（它防的是 CPU 休眠，不是背光）。

```kotlin
attrs.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_OFF  // 0f：背光关到最低
attrs.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE // -1f：交还系统亮度
```

要求 window 全屏才生效（本应用是沉浸式全屏，满足）。任意 `ACTION_DOWN` 立即恢复亮度，
统一在 `dispatchTouchEvent()` 里处理，覆盖所有触摸来源。

> **为什么不走 `Presentation`（独立副屏）**：那样主屏才能「真正息屏」。但普通应用在
> HyperOS 上能否拿到 presentation display 没有保证，且整套相机 + UI 都要搬到另一个 window，
> 改动量与兼容性风险都不划算。当前方案只用 2 行代码就拿到了 95% 的收益。

**模拟器实测**：60 秒后 `mScreenBrightnessOverrideFromWindowManager` 由 `NaN` → `0.0`；
息屏前后 framebuffer 平均亮度 `196.98` → `197.67`（差 3.57，纯属相机场景漂移），
**证明画面内容确实没被改动**；触摸后立刻回到 `NaN`。

### 7. 延迟：已经做了什么，还能做什么，理论下限

**v4.1 已做的（约省 1~2 帧 = 33~66ms）**

| 改动 | 说明 |
| --- | --- |
| 后处理压到 `FAST` 档 | `NOISE_REDUCTION_MODE_FAST` / `EDGE_MODE_FAST` / `COLOR_CORRECTION_ABERRATION_MODE_FAST`。**延迟大户是时域降噪（TNR）**——它要拿前后多帧比对，天然引入 1~3 帧延迟。用 `FAST`（而不是 `OFF`）只保留单帧空域处理，去延迟但不明显掉画质 |
| 关电子防抖 | `CONTROL_VIDEO_STABILIZATION_MODE_OFF`：EIS 要额外裁切 + 缓冲 |
| 对焦改 `CONTINUOUS_VIDEO` | 为「持续取流」设计，比 `CONTINUOUS_PICTURE` 更平滑，不会反复拉风箱 |
| 单条 repeating request | 只挂 `TEMPLATE_PREVIEW`，不挂 `ImageAnalysis` / `ImageCapture` |

**剩下的延迟都在哪**

| 环节 | 大致耗时 | 还能压吗 |
| --- | --- | --- |
| 传感器曝光 | 1 帧（33ms @30fps） | ❌ 物理极限 |
| ISP 处理 | 1~2 帧 | 已压到最低档 |
| Buffer → TextureView 绘制 | ~1 帧 | 见下 |
| SurfaceFlinger 合成 | 1 帧（16.7ms @60Hz） | ❌ |
| HDMI 镜像输出 | <5ms | ❌ 硬件直通 |
| **采集卡** | **30~100ms** | ⚠️ **换更快的采集卡才有效** |
| Switch 2 合成显示 | ~1 帧 | ❌ |

**评估过但否决的方案**

- **换 `SurfaceView`**：能省掉 TextureView 那一次 GL 绘制（~16ms），**但 SurfaceView 不支持
  `setTransform()`** —— 旋转、填充、4:3 适配、拉伸补偿全部做不了。**直接否掉。**
- **自己写 GL 渲染**（GLSurfaceView + OES 纹理）：TextureView 内部本来就是
  `HardwareLayer` + GL，收益 **<10ms**，却要自己管 EGL 生命周期、会话重建、线程同步，
  代码量翻倍 → 不划算。
- **上 60fps**：手机端能省约 33ms，**但采集卡大概率仍按 30fps 采样，收益会被量化掉**；
  且 60fps 要求更短曝光，弱光下画质下降；Switch 2 的摄像头输入通常也是 30fps → 不值得。
- **降分辨率**：延迟主要是管线固定开销，降分辨率省的那点时间换画质损失 → 不划算。

**理论下限**

```
手机端    ≈ 2 帧（采集 + 合成）≈ 66ms @30fps      ← 再低要换硬件
采集卡    30~100ms                                  ← 最大一块，不由本应用决定
Switch 2  ≈ 16~33ms
────────────────────────────────────
整链路    ≈ 100~130ms（当前 v4.1 约 130~200ms）
```

**结论**：`FAST` 后处理这一项已经做完（改动小、无副作用、不损画质）。
剩下的手机端优化空间只有 **30~60ms**，而**大头（采集卡 30~100ms）完全压不动**。
所以**继续在手机端抠延迟不值得**——想真正再降一档，正确做法是
**换一块延迟更低的采集卡**（支持 60fps 直通、或带 "game mode / low latency" 的型号），
这比在代码里抠 10ms 有效得多。

### 8. 其它

- **低延迟**：只建一条 `TEMPLATE_PREVIEW` 的 repeating request，不挂 `ImageAnalysis` / `ImageCapture`。
- **分辨率挑选**：`SCALER_STREAM_CONFIGURATION_MAP.getOutputSizes(SurfaceTexture::class)`，
  4:3 模式上限 1920×1440，16:9 上限 1920×1080，取目标比例内的最大档。
- **固定横屏**：`requestedOrientation = SCREEN_ORIENTATION_LANDSCAPE`（v4 起不再提供竖屏切换）。
  Manifest 仍声明 `configChanges="orientation|screenSize|..."`，`onConfigurationChanged` 只重算矩阵、
  **不重建相机会话**，因此系统栏短暂出现等引起的配置变化不会黑帧。
- **沉浸式**：`WindowInsetsControllerCompat` 隐藏系统栏 + 旧版 `SYSTEM_UI_FLAG_IMMERSIVE_STICKY` 双保险；
  `layoutInDisplayCutoutMode = SHORT_EDGES` 让画面延伸到打孔区域。
- **生命周期**：`onResume` 起相机、`onPause` 解绑、`onDestroy` shutdown，避免后台占用相机。

---

## 八、已知限制

- 本应用不提供「手动对焦距离滑杆」：绝大多数手机模组不支持 `MANUAL_SENSOR`。取而代之提供更有实用价值的 **AF-L 对焦锁定**（锁定当前焦点距离，固定机位输出时画面不会再反复对焦）。
- 曝光补偿依赖 `CONTROL_AE_COMPENSATION_RANGE`，个别机型返回空范围时会提示「当前设备不支持曝光补偿」。
- 固定 30fps 是**目标值**，最终由相机 HAL 决定；部分机型在弱光下会自动降帧。
- 多摄像头列表会把 logical camera 本身和它的物理子摄都列出来，因此可能出现两个选项画面相同（一般等于主摄）；这是为了保证「物理摄像头打不开时仍有可用入口」。
- 物理子摄路由需要设备 HAL 支持（API 28+）。模拟器的虚拟**前置**摄像头带物理子摄（id 3/4）可验证；**后置**多焦段切换需真机（小米 17）确认。
- **`surfaceRotation` 目前硬编码为 90**（绝大多数 Camera2 设备的实测值）。若你的设备恰好是 0/180/270，画面会多转一个 90°，点一次「画面旋转」即可摆正并自动记忆。之所以不做自动探测，是因为 `SurfaceTexture#getTransformMatrix()` 含反射成分（行列式 −1），无法可靠分解出旋转角（见第七章第 3 条）。
- v4 起不再提供左右/上下镜像。**如果确实需要镜像**（例如接前置自拍），可在采集卡/播放软件的输入端翻转，或在 Switch 2 侧处理；应用内已用「画面旋转」覆盖了「上下颠倒」这一实际需求场景。

---

## 九、模拟器验证记录（Android 14 模拟器 x86_64）

在 headless 模拟器（Pixel 7 机型、`system-images;android-34;google_apis;x86_64`）上用坐标点击 + 像素比对验证过。
验证环境为**非可视化**（不靠肉眼，全部用像素差 / 包围盒 / 相关系数判定）。

### v4.1 验证（提示条 + 息屏保护 + 低延迟）

| 项目 | 方法 | 结果 |
| --- | --- | --- |
| **提示条不排队** | 连点 5 次「画面旋转」，抓 `toast:` 日志 | 5 条日志间隔约 230ms（点击节奏），**每次都立即更新文本**，不等前一条消失 ✓ |
| **提示条不堆积** | 连点 5 次后立即 `uiautomator dump`，数层级里的提示条 TextView | **只剩 1 个**，文本为最后一次的值（`画面旋转 180°`），无残留 ✓ |
| **息屏保护触发** | 静置 68 秒，读 `dumpsys power` 的 `mScreenBrightnessOverrideFromWindowManager` | `NaN` → **`0.0`**（背光关到最低）；日志 `setDimmed: true` ✓ |
| **息屏后画面不变（核心）** | 息屏前后各 `screencap`，比 framebuffer 平均亮度 | `196.98` → `197.67`，像素差 **3.57**（纯相机场景漂移 2~4）。**证明只动背光、不动画面内容** ✓ |
| **触摸立即恢复** | `input tap` 后再读 `dumpsys power` | 回到 `NaN`（交还系统亮度），日志 `setDimmed: false` ✓ |
| **低延迟档生效** | 抓 `applyLowLatency` 日志 | `NR=1 EDGE=1` —— `1` 即 `*_FAST` 档（0=OFF / 1=FAST / 2=HIGH_QUALITY）✓ |
| **几何未受影响** | 同一轮看 `applyTransform` | 填充 `buf=1280x720 surf=720x1280 rot=270 eff=1280x720 scale=1.875`，与 v4 完全一致 ✓ |
| 一级面板 5 项 | `uiautomator dump` 读 bounds | `切换摄像头 / 画面旋转 / 息屏保护 / AF/AE / 隐藏控制面板` ✓ |

### v4 验证（旋转与比例修复）

| 项目 | 方法 | 结果 |
| --- | --- | --- |
| **旋转已摆正** | 截图与 CameraX 正立基线 `ref_fill.png` 对比构图（同一条纹结构、同一取景） | 修复前是**横条带**（转了 90°），修复后是**竖条带**、取景与基线一致 ✓ |
| **填充模式几何** | 逐列/逐行最大亮度找硬边 | 内容 `x=0..2399 (宽2400) / y=0..1079 (高1080)`，铺满无黑边 ✓ |
| **完整(4:3) 模式几何** | 同上 | 内容 `x=480..1919 (宽1440) / y=0..1079 (高1080)`，中心 `(1199,539)`；左边界 x=460 亮度 **0** → x=480 亮度 **253**（硬边）✓ |
| **比例未变形（无过度放大）** | 读 `applyTransform` 日志的 `scale` | 填充 `eff=1280x720 scale=1.875`；完整 `eff=1856x1392 scale=0.7759`。修复前的错误版本会算出 **3.3333** ✓ |
| **`surf` 与 `buf` 正确区分** | 日志 | 填充 `buf=1280x720 → surf=720x1280`；完整 `buf=1856x1392 → surf=1392x1856` ✓ |
| 前置角度公式 | 切到前置（`sensor=270 display=90`） | `net=(270+90)%360=0`、`rot=270`、`scale=0.75` → 1440×1080，画面正立无 180° 翻转 ✓ |
| **一级菜单已精简** | `uiautomator dump` 读 bounds | 只剩 `切换摄像头 [915,864][1041,990]` / `画面旋转 [1063,864][1189,990]` / `AF/AE [1211,864][1337,990]` / `隐藏控制面板 [1359,864][1485,990]` —— 竖屏、镜像按钮均已删除 ✓ |
| 镜头循环切换 | 连点 4 次「切换摄像头」+ 抓 `相机就绪` 日志 | 后中焦35mm·主(0) → 前广角26mm·主(1) → 前广角26mm 物理(3) → 前中焦43mm 物理(4) → 回后置；每次 `applyTransform` 都按新镜头的 `sensor`/`buf` 重算 ✓ |
| release 包冒烟 | 装 release APK 冷启动 | 无崩溃（`logcat` 无 `FATAL`/`AndroidRuntime`），默认后置 + 填充 `scale=1.875` ✓ |

### v3 及更早的验证（仍有效）

| 项目 | 方法 | 结果 |
| --- | --- | --- |
| 三层状态机 | `input tap` + `logcat PureCamDbg` | CLEAN → 点击 → FLOATING_ICON，静置 **3.013s** 自动回 CLEAN；点图标 → FULL_PANEL ✓ |
| 跨模式几何自洽 | 填充/完整两图按解析仿射映射重采样后求相关系数 | `rot=0` 时 **r=+0.80**（后置）/ **+0.85**（前置）；`rot=180` 为 **-0.52** / **-0.42** ✓ |
| 前置旋转量决定性判定 | 同摄像头横屏 vs 竖屏（竖屏公式无歧义，作为基准） | 还原后 **r=+0.9714** 对应横屏 `rot=0`（即 `sensor+display`）；`rot=180`（JPEG 标准式）为 **-0.4153** ⇒ 确认用「+」✓ |
| 物理子摄路由 | 循环切换后各镜头画面互相比对 | 前置逻辑(1) vs 物理(3) = **1.17**（同一颗硬件的基线漂移）；物理(3) vs 物理(4) = **4.63**（明显大于基线 ⇒ 确实换了硬件）；后置 vs 前置 ≈ 80 ✓ |
| `getTransformMatrix()` 探测不可行 | 打印矩阵并算行列式 | `[0,-1;-1,0]`，det = **−1**（含反射），`atan2` 提角度会给出 270°（真实 90°）⇒ 放弃自动探测 ✓ |
| 垂直拉伸补偿 | 拉伸系数 1.00 → 0.75，量顶部/底部像素亮度 | 1.00 时顶行亮度 133；0.75 时顶部 0~135px 变为纯黑（1080×0.75=810，上下各 135px，与计算完全吻合）✓ |
| **点面板灰色空白** | 点 (1600,780) 后 dump UI 层级 | 面板**仍在**（层级 11280 字节，按钮齐全）✓ |
| **点面板外画面** | 点 (300,300) 后 dump UI 层级 | 只剩根节点（2048 字节），UI 全部收起 ✓ |
| 二级菜单不挤走一级 | 展开 AF/AE 后 dump 各按钮 bounds | 面板 `[705,284][1695,1017]`；一级行完整可见；`AF-C 连续` 按钮宽 **252dp**（原来只有 48dp）✓ |

调试开关：`adb logcat | grep PureCamDbg` 可看到状态机跳转、分辨率回调，
以及每次变换下发的 `view / buf / surf / sensor / display / net / fix / rot / eff / scale / front / sy`。

---

## 十、为什么做不到「后台输出相机画面」

**结论：在当前这条链路上做不到，而且不是实现问题，是架构决定的。**

### 原因

```
手机屏幕 ──(SurfaceFlinger 合成的 framebuffer)──> USB-C DP Alt Mode ──> HDMI ──> 采集卡 ──> Switch 2
                    ▲
        送出去的永远是「屏幕此刻显示的内容」
```

HDMI 输出走的是 DP Alt Mode 的**屏幕镜像**，送出去的是 SurfaceFlinger 合成出来的 framebuffer。
所以：**app 一退到后台，屏幕就被别的 app 占据，送出去的也就变成了别的 app。**
「后台输出相机画面 + 前台自由使用手机」这个需求，在这条链路上逻辑上就不成立。

补充几点容易走错的路：

| 想法 | 为什么不行 |
| --- | --- |
| 加前台服务让相机在后台继续跑 | 前台服务（`FOREGROUND_SERVICE_TYPE_CAMERA`）确实能让**相机**在后台继续工作，但送出去的仍是屏幕内容，相机画面根本到不了 HDMI |
| 用画中画（PiP） | 唯一能「接近」的方案：前台可以跑其他 app，相机画面在小窗持续显示。但 HDMI 输出里会**混入其他 app 的内容**，且 PiP 窗口尺寸受系统限制，做不到全屏 —— 不是想要的效果 |
| 用「USB 用作 → 网络摄像头」 | Android 14+ 的系统级 UVC gadget 功能（仅部分机型提供），它送出去的同样是**屏幕内容**，不是相机数据流。换汤不换药 |

### 唯一真正可行的分支：扩展模式（独立副屏）

如果设备的 USB-C 输出支持**扩展模式**（把外接显示器识别为一个**独立** display，而不是镜像），
那就可以用：

```kotlin
val displays = displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
val presentation = Presentation(context, displays.first())
presentation.setContentView(previewLayout)   // 相机预览只画到副屏
presentation.show()
```

这样主屏可以照常使用其他 app，副屏持续输出相机画面。

**但前提是设备支持扩展模式**——手机厂商普遍把 USB-C 输出限制为镜像
（三星 DeX、华为 PC 模式这类是有意做的扩展模式，小米是否有需要实测）。

**怎么判断小米 17 支不支持**：插上扩展坞后看手机是否弹出「桌面模式 / 扩展模式」提示；
或连 adb 执行 `adb shell dumpsys display | grep -c DisplayDevice`，结果 **>1** 说明有独立副屏。

如果确认支持，这套改动是本项目唯一能实现「后台输出」的路径。

把手机变成 Switch 2 的 USB 摄像头。

```
小米 17 ──USB-C 扩展坞(HDMI)──> HDMI 线 ──> 采集卡(UVC) ──USB──> Switch 2
```

### 这个版本能做什么

- **纯净全屏画面**：三层 UI 状态机，不操作时屏幕上没有任何 UI，HDMI 输出干净画面
- **垂直拉伸补偿**（0.50~1.50）：抵消采集卡造成的画面拉长，附校准网格可直接在 Switch 2 上比对
- **三种画面模式**：填充（16:9 裁满）/ 完整（4:3 全画幅）/ 16:9框
- **多摄像头切换**：支持物理子摄（超广角 / 长焦 / 前置）
- **自动息屏保护**：60 秒无操作关掉屏幕背光，投屏画面完全不变
- **低延迟管线**：去掉时域降噪的多帧延迟，省约 33~66ms
- 固定 30fps · 强制横屏 · 屏幕常亮 · AF-L 对焦锁定

### 安装

```bash
adb install -r PureCam-release.apk
```

或直接把 APK 传到手机点击安装。首次启动需要授予相机权限。

### 使用前提

- **采集卡必须能输出 1080p（或更低）**。上报 2K / 1440p 模式的采集卡 Switch 2 不识别。
- 采集卡必须是标准 UVC、免驱型号。
- **只在小米 17 上验证过**，其他机型大概率能用但未测试。

### 已知限制

- 做不到「App 挂后台 + 前台干别的」——HDMI 走的是屏幕镜像，App 退到后台送出去的就是别的 App 画面了。
  这是架构决定的，详见 [已知限制](https://github.com/acocosum/purecam/blob/main/docs/limitations.md)。
- 不提供手动对焦距离滑杆（绝大多数手机模组不支持），用 AF-L 对焦锁定替代。
- `surfaceRotation` 硬编码 90°，个别设备可能要多点一次「画面旋转」。

### 校验

| | |
| --- | --- |
| 文件 | `PureCam-release.apk` |
| 大小 | 4.58 MB (4,802,306 字节) |
| SHA-256 | `90acac92d7cc78abc3fe437f7e144a1beb04b6dcd26046a89a3ac9329ca383d0` |
| 签名 | 自签名（CN=PureCam），非 Google Play 分发 |

> ⚠️ 如果你自己重新编译，签名会不同。切换不同来源的包之前需要先
> `adb uninstall com.purecam.app`，否则会报 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`。

### 文档

- [连接与排查](https://github.com/acocosum/purecam/blob/main/docs/setup-guide.md)
- [画面拉伸校准](https://github.com/acocosum/purecam/blob/main/docs/calibration.md)
- [开发笔记](https://github.com/acocosum/purecam/blob/main/docs/development-notes.md)
- [技术博客](https://github.com/acocosum/purecam/blob/main/docs/blog-use-phone-as-switch2-webcam.md)

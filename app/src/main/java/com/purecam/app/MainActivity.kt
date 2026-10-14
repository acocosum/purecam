package com.purecam.app

import android.Manifest
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Size
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlin.math.roundToInt

/**
 * PureCam 主界面。
 *
 * 职责：
 * 1. 全屏 + 常亮 + **固定横屏** + 沉浸式系统栏；
 * 2. 使用 Camera2（[CameraController]）输出全屏相机画面（自建 TextureView，变换矩阵 100% 自控）；
 * 3. 实现「纯净画面 → 悬浮图标 → 完整 UI 面板」三层状态机；
 * 4. 提供画面旋转微调、镜头切换、对焦/曝光、画面模式与拉伸补偿等控制。
 *
 * 继承 [ComponentActivity] 是为了拿到稳定可用的 `onConfigurationChanged` /
 * `WindowInsetsControllerCompat` 等现代 API；本应用不使用 Lifecycle 与 CameraX。
 */
class MainActivity : ComponentActivity() {

    /** 三层 UI 状态 */
    private enum class UiState {
        /** 纯净画面：只有相机预览，无任何 UI */
        CLEAN,

        /** 悬浮图标：右上角半透明小图标，3 秒无操作自动淡出回 CLEAN */
        FLOATING_ICON,

        /** 完整 UI：底部控制面板 + 高级（AF/AE）面板 */
        FULL_PANEL
    }

    /** 画面填充模式 */
    private enum class FrameMode {
        /** 填满屏幕（等比放大并裁切边缘） */
        FILL,

        /** 完整显示（等比缩小，可能留黑边） */
        FIT,

        /** 强制 16:9 输出框（屏幕其余部分为黑，便于采集卡输出标准 16:9） */
        RATIO_16_9
    }

    private companion object {
        const val TAG = "MainActivity"
        const val DBG = "PureCamDbg"
        const val REQ_CAMERA_PERMISSION = 1001

        /** 悬浮图标自动隐藏延时（需求：3~5 秒，这里取 3 秒） */
        const val AUTO_HIDE_DELAY_MS = 3_000L

        /**
         * 息屏保护延时：多久无操作后关掉屏幕背光。
         *
         * 取 60 秒：短了会在「慢慢调拉伸/画幅」时误触发（虽然点一下就亮，但总归打断），
         * 长了防烧屏的收益就低了。本应用的典型用法是摆好手机后长时间不碰，60 秒是个平衡点。
         *
         * 注意它**不是**真正的息屏 —— 只是把背光关到最低，CPU 和相机都照常工作，
         * 因此 HDMI 输出的画面完全不变（详见 [setDimmed] 的注释）。
         */
        const val AUTO_DIM_DELAY_MS = 60_000L

        /**
         * 提示条停留时长。
         *
         * 用自定义提示条而不是系统 `Toast`：系统 Toast 是 2 秒且由系统排队，
         * 连点按钮时会一个个排队弹出（MIUI/HyperOS 上尤其明显）。
         * 这里设 1.2 秒、新提示直接覆盖旧的，连点也不排队。
         */
        const val TOAST_DURATION_MS = 1_200L

        /** 淡入淡出动画时长 */
        const val FADE_DURATION_MS = 220L

        /** 偏好设置 */
        const val PREFS_NAME = "purecam_prefs"
        const val KEY_ASPECT_SCALE_Y = "output_scale_y"
        const val KEY_ROTATE_FIX = "rotate_fix"
        const val KEY_CAMERA_ID = "camera_id"
        const val KEY_FRAME_MODE = "frame_mode"
        const val KEY_AUTO_DIM = "auto_dim"

        /** 垂直补偿范围与步长 */
        const val SCALE_MIN = 0.50f
        const val SCALE_MAX = 1.50f
        const val SCALE_STEP = 0.01f

        /** 画面旋转微调的可选档位（按「画面旋转」按钮循环切换） */
        val ROTATE_STEPS = intArrayOf(0, 90, 180, 270)

        /** 常用补偿预设：1.00 原始 / 0.75（16:9 → 4:3 压缩）/ 0.80 / 1.33（4:3 → 16:9） */
        val SCALE_PRESETS = floatArrayOf(1.00f, 0.75f, 0.80f, 1.33f)
    }

    // ------------------------------------------------------------------ 视图
    private lateinit var rootLayout: FrameLayout

    /**
     * 预览用的 TextureView。
     *
     * 之所以不用 CameraX 的 PreviewView：我们要自己掌控变换矩阵
     * （旋转 / 填充 / 镜像 / 垂直补偿全部在一个矩阵里算清楚），
     * 并且 CameraX 无法把画面路由到物理子摄（超广角 / 长焦），详见 [CameraController] 的说明。
     */
    private lateinit var previewTexture: TextureView

    private lateinit var touchLayer: View
    private lateinit var floatingIcon: ImageView
    /**
     * 面板容器（外层是 ScrollView）。
     *
     * 用 ScrollView 包一层是为了兜底：面板内容再高也不会把一级菜单顶出屏幕，
     * 超出部分可以上下滚动（正常情况下内容远小于屏幕，不会滚动）。
     */
    private lateinit var panelContainer: ScrollView
    private lateinit var controlPanel: LinearLayout
    private lateinit var advancedPanel: LinearLayout
    private lateinit var calibrationOverlay: CalibrationOverlay

    /**
     * 自定义提示条（替代系统 Toast）。
     *
     * 系统 Toast 有两个硬伤：① 时长固定 2 秒、无法缩短；② 由 NotificationManagerService
     * 排队派发，连点按钮时提示会一个个排着队弹出来，非常难受。
     * 这里用一个覆盖层 TextView 自己控制，新提示直接覆盖旧文本并重置计时。
     */
    private lateinit var toastView: TextView

    private lateinit var btnSwitchCamera: ImageButton
    private lateinit var btnRotate: ImageButton
    private lateinit var btnAutoDim: ImageButton
    private lateinit var btnAdvanced: TextView
    private lateinit var btnHide: ImageButton

    private lateinit var btnEvMinus: TextView
    private lateinit var btnEvPlus: TextView
    private lateinit var tvEvValue: TextView
    private lateinit var btnFocusCenter: TextView
    private lateinit var btnFocusLock: TextView

    private lateinit var btnScaleMinus: TextView
    private lateinit var btnScalePlus: TextView
    private lateinit var tvScaleValue: TextView
    private lateinit var btnScalePreset: TextView
    private lateinit var btnCalibration: TextView
    private lateinit var btnFrameMode: TextView
    private lateinit var tvStatus: TextView

    // ------------------------------------------------------------------ 逻辑
    private lateinit var cameraController: CameraController
    private val mainHandler = Handler(Looper.getMainLooper())

    private val autoHideRunnable = Runnable { setState(UiState.CLEAN) }
    private val autoDimRunnable = Runnable { setDimmed(true) }
    private val hideToastRunnable = Runnable { fadeOut(toastView) }

    private var uiState = UiState.CLEAN

    /** 息屏保护开关（默认开：本应用长期无人值守输出，烧屏是真实风险） */
    private var autoDimEnabled = true

    /** 当前是否已把背光关到最低 */
    private var isDimmed = false

    private var advancedVisible = false
    private var frameMode = FrameMode.FILL

    /**
     * **Surface 自带的视觉旋转量**（0 / 90 / 180 / 270，顺时针为正）。
     *
     * Camera2 的相机服务会通过 `native_window_set_buffers_transform` 把「传感器方向」写进
     * Surface，这个变换由 TextureView 在贴纹理时自动叠加，**不受应用控制**。
     * 因此：
     * - 我们实际要下发的旋转 = `net − surfaceRotation`；
     * - 画面在被我们旋转之前的「有效宽高」也要按它先互换一次（下面 [applyViewTransform] 的 step 0）。
     *
     * **默认 90**：与绝大多数 Camera2 设备一致（模拟器已验证，小米 17 上的表现也吻合）。
     * 若画面转了 90°，用一级面板的「画面旋转」按钮循环切换，会自动记忆。
     *
     * 曾尝试用 `SurfaceTexture#getTransformMatrix()` 自动探测，**已废弃**：
     * 实测该矩阵含**反射**成分（模拟器上为 `[0,-1;-1,0]`，行列式 **−1**），
     * 反射矩阵无法唯一分解为「旋转 + 翻转」，`atan2` 提角度必然得出错误结论
     * （这里会给出 270°，真实值是 90°，正好差 180°）。
     */
    private var surfaceRotation = 90

    /** 用户微调的额外旋转（0/90/180/270），持久化；非 0 时「画面旋转」按钮点亮 */
    private var rotateFix = 0

    /** 垂直方向输出补偿：1.00 = 原始比例，<1 压扁，>1 拉高 */
    private var outputScaleY = 1.00f
    private var presetIndex = 0

    private var cameraResolution: Size = Size(1920, 1080)

    // ------------------------------------------------------------------ 生命周期

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        restorePrefs()
        setupWindow()
        buildUi()
        setupCameraController()
        // 恢复上次选中的摄像头（例如上次用的是超广角）
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getString(KEY_CAMERA_ID, null)
            ?.let { cameraController.selectCameraById(it) }
        applyOrientation()
        applyFrameMode()
        refreshToggleStates()
        applyViewTransform()

        if (hasCameraPermission()) {
            startCamera()
        } else {
            requestCameraPermission()
        }
    }

    override fun onResume() {
        super.onResume()
        if (hasCameraPermission()) startCamera()
        // 回到前台重新开始息屏倒计时（默认 30s 无操作息屏）
        scheduleAutoDim()
    }

    override fun onPause() {
        super.onPause()
        cancelAutoHide()
        // 切后台必须恢复亮度：否则回到桌面后屏幕还是黑的，像是卡死了
        cancelAutoDim()
        cameraController.unbind()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(autoHideRunnable)
        mainHandler.removeCallbacks(autoDimRunnable)
        mainHandler.removeCallbacks(hideToastRunnable)
        cameraController.shutdown()
        super.onDestroy()
    }

    /**
     * 方向 / 屏幕尺寸变化：重新计算布局与变换矩阵。
     *
     * Camera2 不需要重新绑定相机 —— 旋转由我们自己的变换矩阵处理（见 [applyViewTransform]），
     * 这样切竖屏不会黑一帧。
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        rootLayout.post {
            applyFrameMode()
            applyViewTransform()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (uiState == UiState.FULL_PANEL) {
            setState(UiState.FLOATING_ICON)
        } else {
            super.onBackPressed()
        }
    }

    @Suppress("DEPRECATION")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_CAMERA_PERMISSION) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                startCamera()
            } else {
                showToast(getString(R.string.permission_camera_denied))
            }
        }
    }

    // ------------------------------------------------------------------ 偏好设置

    private fun restorePrefs() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        outputScaleY = prefs.getFloat(KEY_ASPECT_SCALE_Y, 1.00f).coerceIn(SCALE_MIN, SCALE_MAX)
        rotateFix = prefs.getInt(KEY_ROTATE_FIX, 0) % 360
        autoDimEnabled = prefs.getBoolean(KEY_AUTO_DIM, true)
        frameMode = when (prefs.getString(KEY_FRAME_MODE, "fill")) {
            "fit" -> FrameMode.FIT
            "169" -> FrameMode.RATIO_16_9
            else -> FrameMode.FILL
        }
        presetIndex = SCALE_PRESETS.indexOfFirst { kotlin.math.abs(it - outputScaleY) < 0.005f }
            .let { if (it < 0) 0 else it }
    }

    private fun savePrefs() {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putFloat(KEY_ASPECT_SCALE_Y, outputScaleY)
            .putInt(KEY_ROTATE_FIX, rotateFix)
            .putBoolean(KEY_AUTO_DIM, autoDimEnabled)
            .putString(
                KEY_FRAME_MODE,
                when (frameMode) {
                    FrameMode.FIT -> "fit"
                    FrameMode.RATIO_16_9 -> "169"
                    FrameMode.FILL -> "fill"
                }
            )
            .apply()
    }

    // ------------------------------------------------------------------ 窗口

    private fun setupWindow() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val attrs = window.attributes
            attrs.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = attrs
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            WindowInsetsControllerCompat(window, window.decorView)
                .hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    // ------------------------------------------------------------------ UI 构建

    private fun buildUi() {
        rootLayout = FrameLayout(this).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
        }

        previewTexture = TextureView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER
            )
            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                    cameraController.onSurfaceReady(st)
                    applyViewTransform()
                }

                override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {
                    applyViewTransform()
                }

                override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                    cameraController.onSurfaceDestroyed()
                    // 返回 false：保留 SurfaceTexture，切后台再回来能立刻恢复画面
                    return false
                }

                override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
            }
        }

        touchLayer = View(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            isClickable = true
            setOnClickListener { onPreviewTapped() }
        }

        floatingIcon = ImageView(this).apply {
            val size = UiFactory.dp(this@MainActivity, 48)
            layoutParams = FrameLayout.LayoutParams(size, size).apply {
                gravity = Gravity.TOP or Gravity.END
                topMargin = UiFactory.dp(this@MainActivity, 24)
                rightMargin = UiFactory.dp(this@MainActivity, 24)
                marginEnd = UiFactory.dp(this@MainActivity, 24)
            }
            setImageResource(R.drawable.ic_camera)
            background = UiFactory.solidDrawable(this@MainActivity, UiFactory.COLOR_BUTTON_BG, 24)
            val p = UiFactory.dp(this@MainActivity, 12)
            setPadding(p, p, p, p)
            alpha = 0f
            visibility = View.GONE
            contentDescription = getString(R.string.cd_show_panel)
            setOnClickListener { setState(UiState.FULL_PANEL) }
        }

        // 自定义提示条（顶部居中，默认隐藏）。放在最上层，不被面板遮挡。
        toastView = TextView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL
            ).apply {
                topMargin = UiFactory.dp(this@MainActivity, 96)
            }
            background = UiFactory.solidDrawable(this@MainActivity, 0xCC000000.toInt(), 20)
            val px = UiFactory.dp(this@MainActivity, 14)
            setPadding(px, UiFactory.dp(this@MainActivity, 8), px, UiFactory.dp(this@MainActivity, 8))
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            alpha = 0f
            visibility = View.GONE
        }

        // 校准图案（默认隐藏）：正方形网格 + 同心圆，用于在 Switch 2 上判断失真方向
        calibrationOverlay = CalibrationOverlay(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            visibility = View.GONE
        }

        controlPanel = buildControlPanel()
        advancedPanel = buildAdvancedPanel()

        val panelBody = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
            addView(advancedPanel)
            addView(controlPanel)
            // 一级 / 二级面板之间的间隙同样吸收点击，避免误触收起面板
            setOnClickListener { markUserInteraction() }
        }

        panelContainer = ScrollView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                bottomMargin = UiFactory.dp(this@MainActivity, 24)
            }
            isFillViewport = false
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(panelBody)
            alpha = 0f
            visibility = View.GONE
        }

        // 添加顺序决定层级：预览 → 触摸层 → 校准图案 → 悬浮图标 → 面板 → 提示条
        rootLayout.addView(previewTexture)
        rootLayout.addView(touchLayer)
        rootLayout.addView(calibrationOverlay)
        rootLayout.addView(floatingIcon)
        rootLayout.addView(panelContainer)
        rootLayout.addView(toastView)

        setContentView(rootLayout)
    }

    private fun buildControlPanel(): LinearLayout {
        btnSwitchCamera = UiFactory.createImageButton(
            this, R.drawable.ic_switch_camera, getString(R.string.cd_switch_camera)
        ).apply {
            setOnClickListener {
                // 循环切换所有可用摄像头：后超广角 → 后广角 → 后长焦 → 前置 → …
                val option = cameraController.switchCamera()
                val msg = option?.let { "${it.label} · id ${it.cameraId}" }
                    ?: getString(R.string.camera_none)
                showToast(msg)
                option?.let { saveCameraId(it.cameraId) }
                updateStatusText()
                markUserInteraction()
            }
        }

        /**
         * 画面旋转微调：在自动算出的角度上再叠加 0/90/180/270。
         *
         * 两个用途：
         * 1. 兜底：极少数设备的 Surface 自带变换解析不出（或带镜像）时，现场一键摆正；
         * 2. 扩展坞 / 采集卡装反导致画面上下颠倒时，转 180° 即可（原来靠垂直镜像，现在镜像已移除）。
         */
        btnRotate = UiFactory.createImageButton(
            this, R.drawable.ic_rotate, getString(R.string.cd_rotate)
        ).apply {
            setOnClickListener {
                val idx = (ROTATE_STEPS.indexOf(rotateFix) + 1) % ROTATE_STEPS.size
                rotateFix = ROTATE_STEPS[idx]
                applyViewTransform()
                refreshToggleStates()
                savePrefs()
                showToast(getString(R.string.rotate_hint, rotateFix))
                markUserInteraction()
            }
        }

        /**
         * 息屏保护开关。
         *
         * 开启后，超过 [AUTO_DIM_DELAY_MS] 无操作就把屏幕背光关到最低。
         * **这不是真正的息屏**：CPU / 相机 / HDMI 输出全部照常运行，
         * 只是屏幕不再发光 —— 所以既防烧屏、又省掉屏幕这份耗电，投屏画面却完全不变。
         */
        btnAutoDim = UiFactory.createImageButton(
            this, R.drawable.ic_dim, getString(R.string.cd_auto_dim)
        ).apply {
            setOnClickListener {
                autoDimEnabled = !autoDimEnabled
                if (autoDimEnabled) scheduleAutoDim() else cancelAutoDim()
                refreshToggleStates()
                savePrefs()
                showToast(
                    if (autoDimEnabled) {
                        getString(R.string.dim_on, (AUTO_DIM_DELAY_MS / 1000).toInt())
                    } else {
                        getString(R.string.dim_off)
                    }
                )
                markUserInteraction()
            }
        }

        btnAdvanced = UiFactory.createTextButton(this, getString(R.string.btn_af_ae)).apply {
            setOnClickListener {
                advancedVisible = !advancedVisible
                advancedPanel.visibility = if (advancedVisible) View.VISIBLE else View.GONE
                UiFactory.setTextSelected(this, advancedVisible)
                markUserInteraction()
            }
        }

        btnHide = UiFactory.createImageButton(
            this, R.drawable.ic_collapse, getString(R.string.cd_hide_ui)
        ).apply {
            setOnClickListener { setState(UiState.FLOATING_ICON) }
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = UiFactory.solidDrawable(this@MainActivity, UiFactory.COLOR_BUTTON_BG, 8)
            val p = UiFactory.dp(this@MainActivity, 6)
            setPadding(p, p, p, p)
            addView(btnSwitchCamera)
            addView(btnRotate)
            addView(btnAutoDim)
            addView(btnAdvanced)
            addView(btnHide)
        }
    }

    private fun buildAdvancedPanel(): LinearLayout {
        // 第一行：曝光补偿 + 中心对焦
        btnEvMinus = UiFactory.createTextButton(this, "−", 40).apply {
            setOnClickListener { adjustExposure(-1) }
        }
        tvEvValue = UiFactory.createLabel(this, "EV 0.0")
        btnEvPlus = UiFactory.createTextButton(this, "+", 40).apply {
            setOnClickListener { adjustExposure(1) }
        }
        btnFocusCenter = UiFactory.createTextButton(this, getString(R.string.btn_focus_center), 76).apply {
            setOnClickListener {
                cameraController.focusAtCenter()
                markUserInteraction()
            }
        }

        // 第二行：对焦锁定
        btnFocusLock = UiFactory.createTextButton(
            this, getString(R.string.btn_focus_continuous), 96, 48
        ).apply {
            setOnClickListener {
                val locked = !cameraController.isFocusLocked()
                cameraController.setFocusLocked(locked)
                text = getString(
                    if (locked) R.string.btn_focus_locked else R.string.btn_focus_continuous
                )
                UiFactory.setTextSelected(this, locked)
                markUserInteraction()
            }
        }
        // 第一行（宽排布）：曝光补偿 + 中心对焦 + 对焦锁定 —— 合成一行，压缩纵向高度
        val exposureRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(UiFactory.createLabel(this@MainActivity, "EV"))
            addView(btnEvMinus)
            addView(tvEvValue)
            addView(btnEvPlus)
            addView(btnFocusCenter)
            addView(btnFocusLock)
        }

        // 第三行：垂直拉伸补偿（用于抵消采集卡 / Switch 2 的非等比缩放）
        btnScaleMinus = UiFactory.createTextButton(this, "−", 40).apply {
            setOnClickListener { adjustAspectScale(-1) }
        }
        tvScaleValue = UiFactory.createLabel(this, "%.2f".format(outputScaleY))
        btnScalePlus = UiFactory.createTextButton(this, "+", 40).apply {
            setOnClickListener { adjustAspectScale(1) }
        }
        btnScalePreset = UiFactory.createTextButton(this, getString(R.string.btn_preset), 60).apply {
            setOnClickListener {
                presetIndex = (presetIndex + 1) % SCALE_PRESETS.size
                outputScaleY = SCALE_PRESETS[presetIndex]
                applyViewTransform()
                updateAspectLabel()
                savePrefs()
                markUserInteraction()
            }
        }
        val scaleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(UiFactory.createLabel(this@MainActivity, getString(R.string.label_stretch)))
            addView(btnScaleMinus)
            addView(tvScaleValue)
            addView(btnScalePlus)
            addView(btnScalePreset)
        }

        // 第四行：显示模式 + 校准图案
        btnFrameMode = UiFactory.createTextButton(this, getString(R.string.frame_fill), 72, 48).apply {
            setOnClickListener {
                frameMode = when (frameMode) {
                    FrameMode.FILL -> FrameMode.FIT
                    FrameMode.FIT -> FrameMode.RATIO_16_9
                    FrameMode.RATIO_16_9 -> FrameMode.FILL
                }
                applyFrameMode()
                savePrefs()     // 画幅也要落盘，否则下次启动会退回上一个模式
                showToast(
                    getString(
                        when (frameMode) {
                            FrameMode.FILL -> R.string.frame_fill_hint
                            FrameMode.FIT -> R.string.frame_fit_hint
                            FrameMode.RATIO_16_9 -> R.string.frame_169_hint
                        }
                    )
                )
                markUserInteraction()
            }
        }
        btnCalibration = UiFactory.createTextButton(this, getString(R.string.btn_calibration), 68, 48).apply {
            setOnClickListener {
                val show = calibrationOverlay.visibility != View.VISIBLE
                calibrationOverlay.visibility = if (show) View.VISIBLE else View.GONE
                UiFactory.setTextSelected(this, show)
                markUserInteraction()
            }
        }
        val frameRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(btnFrameMode)
            addView(btnCalibration)
        }

        // 第五行：状态（分辨率 · 帧率 · 当前镜头）
        tvStatus = UiFactory.createLabel(this, "")
        val statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(tvStatus)
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = UiFactory.solidDrawable(this@MainActivity, UiFactory.COLOR_PANEL_BG, 8)
            val p = UiFactory.dp(this@MainActivity, 6)
            setPadding(p, p, p, p)
            visibility = View.GONE
            // 只保留 3 行（曝光/对焦 → 拉伸 → 画幅）+ 状态行，行高统一 48dp，
            // 纵向高度比原来的 5 行少了近一半，展开二级菜单不会再把一级菜单挤出屏幕。
            addView(exposureRow)
            addView(scaleRow)
            addView(frameRow)
            addView(statusRow)
            // 面板空白处也要「吃掉」点击，否则会穿透到下面的触摸层把面板收起来（易误触）。
            // 注意：设置点击监听会把 isClickable 置为 true，触摸会先派发给子按钮，
            // 落到空白处时才由本容器吸收，什么都不做。
            setOnClickListener { markUserInteraction() }
        }
    }

    // ------------------------------------------------------------------ 相机

    private fun setupCameraController() {
        cameraController = CameraController(this).apply {
            onCameraReady = { option -> onCameraReadyInternal(option) }
            onError = { error ->
                Log.e(TAG, "相机错误", error)
                runOnUiThread {
                    // 错误提示停留久一点（普通提示 1.2s 太短，来不及看）
                    showToast(getString(R.string.camera_error, error.message ?: ""), 4_000L)
                }
            }
            onResolutionChanged = { size ->
                cameraResolution = size
                Log.d(DBG, "分辨率回调: ${size.width}x${size.height}")
                updateStatusText()
                applyViewTransform()
            }
            // 物理镜头被设备拒绝时提示并把该项从列表剔除
            onCameraDropped = { option ->
                showToast("「${option.label}」无法单独打开，已跳过", 3_000L)
                updateStatusText()
            }
        }
    }

    private fun onCameraReadyInternal(option: CameraController.CameraOption) {
        updateStatusText()
        val step = cameraController.exposureStep()?.toFloat()
        tvEvValue.text = if (cameraController.exposureRange() != null && step != null) {
            "EV %+.1f".format(cameraController.currentExposureIndex() * step)
        } else "EV --"

        btnFocusLock.text = getString(R.string.btn_focus_continuous)
        UiFactory.setTextSelected(btnFocusLock, false)
        // 会话重建后重新算一次变换矩阵（分辨率可能变了）
        applyViewTransform()
        Log.d(DBG, "相机就绪: ${option.label} id=${option.cameraId} 物理=${option.isPhysical}")
    }

    private fun startCamera() {
        cameraController.startCamera()
    }

    private fun adjustExposure(delta: Int) {
        val range = cameraController.exposureRange()
        if (range == null) {
            showToast(getString(R.string.exposure_unsupported))
            return
        }
        val next = (cameraController.currentExposureIndex() + delta).coerceIn(range.lower, range.upper)
        if (cameraController.setExposureCompensation(next)) {
            val step = cameraController.exposureStep()?.toFloat() ?: (1f / 3f)
            tvEvValue.text = "EV %+.1f".format(next * step)
        }
        markUserInteraction()
    }

    private fun adjustAspectScale(delta: Int) {
        outputScaleY = (outputScaleY + delta * SCALE_STEP).coerceIn(SCALE_MIN, SCALE_MAX)
        applyViewTransform()
        updateAspectLabel()
        savePrefs()
        markUserInteraction()
    }

    private fun updateAspectLabel() {
        tvScaleValue.text = "%.2f".format(outputScaleY)
    }

    private fun updateStatusText() {
        val camera = cameraController.currentCamera()?.label ?: getString(R.string.camera_none)
        tvStatus.text = getString(
            R.string.status_format,
            cameraResolution.width,
            cameraResolution.height,
            CameraController.TARGET_FPS,
            camera
        )
    }

    private fun saveCameraId(id: String) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putString(KEY_CAMERA_ID, id)
            .apply()
    }

    // ------------------------------------------------------------------ 变换（旋转 + 比例补偿）

    /**
     * 计算并下发完整的预览变换：**还原 → 旋转 → 填充/适配 → 垂直补偿**，全部合成一个矩阵。
     *
     * 四个绕不开的坑（都在本方法里处理了，改动前请先读完）：
     *
     * 1. **坐标系**：`TextureView#setTransform()` 变换的是**目标四边形**，坐标系是 view 空间；
     *    默认（单位矩阵）时整块 buffer 已被拉伸铺满 view。所以矩阵第一步必须先用
     *    `setRectToRect(view, surf, FILL)` 把 view 矩形还原成 **surf 矩形**，
     *    后面按该坐标算的旋转/平移/缩放才有意义。官方 Camera2Basic 也是这个顺序。
     *
     * 2. **旋转量要扣掉 Surface 自带的角度**（见 [surfaceRotation]）。
     *
     * 3. **两个「宽高」不能混用**：`surfW/surfH`（Surface 变换后、我们旋转前）用于 step 0/1/2；
     *    `effW/effH`（最终呈现）用于算填充缩放。混用会导致画面被各向异性拉伸变形。
     *
     * 4. **只保留横屏**：`display` 固定为横屏下的值，不再有竖屏分支。
     */
    private fun applyViewTransform() {
        val viewW = previewTexture.width
        val viewH = previewTexture.height
        if (viewW == 0 || viewH == 0) return

        val sx = 1f
        val sy = outputScaleY

        // 校准图案跟随补偿一起缩放，方便在 Switch 2 上直接判断「正方」与否
        if (calibrationOverlay.scaleY != outputScaleY) {
            calibrationOverlay.scaleY = outputScaleY
        }

        val bufW = cameraController.previewSize.width
        val bufH = cameraController.previewSize.height
        val display = displayRotationDegrees()
        val sensor = cameraController.sensorOrientation
        val isFront = cameraController.currentCamera()?.let {
            it.lensFacing == CameraCharacteristics.LENS_FACING_FRONT
        } ?: false

        /**
         * 净旋转量（画面相对屏幕还差多少度才算「正的」）：
         * - 后置：`sensor - display`
         * - 前置：`sensor + display`
         *
         * 前置为什么是「+」：与 CameraX `CameraOrientationUtil#getRelativeImageRotation`
         * 完全一致（"If flipping horizontally, rotations should be negative"）。
         * 注意这与 `JPEG_ORIENTATION` 的算法**不同**，那条公式假设 Surface 上还带镜像，
         * 会给前置多转 180°。
         */
        val net = if (isFront) (sensor + display) % 360 else ((sensor - display) + 360) % 360

        /**
         * 我们**实际要下发**的旋转 = 净旋转 − Surface 自带旋转 + 用户微调。
         *
         * 直觉：Surface 已经帮我们转了 `surfaceRotation`，我们再补上剩下的差额即可。
         * 若不做这个减法，画面就会多转一个 `surfaceRotation`
         * —— v3 在小米 17 和模拟器上「画面被顺时针旋转 90°」就是这个原因。
         */
        val rotation = ((net - surfaceRotation + rotateFix) % 360 + 360) % 360

        /**
         * 画面在**被我们旋转之前**（即 Surface 变换之后）的有效宽高。
         *
         * 这是本方法里最容易写错的地方：
         * - step 0 的「还原步」、旋转支点、平移量，全部要用 **surfW / surfH**（Surface 变换后的尺寸），
         *   而不是 `bufW / bufH`（原始 buffer 尺寸）。用错了纹理就会被各向异性拉伸（画面变形）。
         * - 而 `effW / effH`（**最终**呈现在屏幕上的画面尺寸）要在 surf 的基础上，
         *   再按我们下发的 `rotation` 互换一次。
         */
        val surfSwapped = surfaceRotation == 90 || surfaceRotation == 270
        val surfW = if (surfSwapped) bufH else bufW
        val surfH = if (surfSwapped) bufW else bufH

        /** 最终画面是否宽高互换 —— 只由我们下发的 `rotation` 决定（surf 那次已在上面算过了） */
        val effSwapped = rotation == 90 || rotation == 270
        val effW = if (effSwapped) surfH else surfW
        val effH = if (effSwapped) surfW else surfH

        val scale = if (frameMode == FrameMode.FILL) {
            maxOf(viewW / effW.toFloat(), viewH / effH.toFloat())   // 填满：裁掉多余
        } else {
            minOf(viewW / effW.toFloat(), viewH / effH.toFloat())   // 完整：留黑边
        }

        val cx = viewW / 2f
        val cy = viewH / 2f

        val m = Matrix().apply {
            /**
             * 0) **最关键的一步**：先把「view 矩形」还原成「buffer 矩形」。
             *
             * TextureView 的默认行为（transform 为单位矩阵时）是把**整块 buffer 拉伸铺满整个 view**
             * —— 也就是说变换矩阵作用在**目标四边形**上、坐标系是 **view 空间**，
             * 而不是我们直觉以为的「把 buffer 映射进 view」。
             * 官方 Camera2Basic 也是这个写法：
             * `matrix.setRectToRect(viewRect, bufferRect, ScaleToFit.FILL)` 之后再 rotate/scale。
             * 少了这一步，后面的旋转中心、平移量、缩放支点全都会错位
             * （实测 4:3 模式下画面会跑到 x=480..2342、底部缺 250px）。
             *
             * ScaleToFit.FILL = x/y **独立**缩放，正好把 src 完整映射到 dst。
             *
             * 目标是 **surfW × surfH**（Surface 变换后的有效宽高），不是 bufW × bufH。
             */
            setRectToRect(
                RectF(0f, 0f, viewW.toFloat(), viewH.toFloat()),
                RectF(0f, 0f, surfW.toFloat(), surfH.toFloat()),
                Matrix.ScaleToFit.FILL
            )
            // 1) 绕画面中心旋转
            postRotate(rotation.toFloat(), surfW / 2f, surfH / 2f)
            // 2) 把画面中心平移到 view 中心
            postTranslate(cx - surfW / 2f, cy - surfH / 2f)
            // 3) 等比缩放（填充 = 裁掉多余 / 完整 = 留黑边）
            postScale(scale, scale, cx, cy)
            // 4) 垂直比例补偿（在最终显示空间绕 view 中心做，顺序必须在最后）
            postScale(sx, sy, cx, cy)
        }
        previewTexture.setTransform(m)
        Log.d(
            DBG,
            "applyTransform: view=${viewW}x${viewH} buf=${bufW}x${bufH} surf=${surfW}x${surfH} " +
                "sensor=$sensor display=$display net=$net fix=$rotateFix rot=$rotation " +
                "eff=${effW}x${effH} scale=$scale front=$isFront sy=$sy"
        )
    }


    /** 当前显示相对「自然方向」的旋转角度（0 / 90 / 180 / 270） */
    private fun displayRotationDegrees(): Int {
        val rotation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display?.rotation ?: Surface.ROTATION_0
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.rotation
        }
        return when (rotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
    }

    // ------------------------------------------------------------------ 方向与画面模式

    /** 固定横屏（v4 起不再提供竖屏：本应用的唯一用途就是给 Switch 2 输出横屏画面） */
    private fun applyOrientation() {
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
    }

    /**
     * 应用画面模式（同时决定「相机源画面比例」与「屏幕上的显示方式」）：
     * - **填充**：源 16:9 + 等比放大铺满 —— 画面填满屏幕，上下裁掉一部分传感器视野
     * - **完整**：源 **4:3 全画幅** + 等比适配 —— 拿满传感器画面，屏幕上左右留黑边
     * - **16:9框**：源 16:9 + 等比适配，并把预览限制在屏幕中央的 16:9 区域
     */
    private fun applyFrameMode() {
        Log.d(DBG, "applyFrameMode: mode=$frameMode")
        // 只有「完整」模式使用 4:3 全画幅（其余沿用 16:9）
        val ratio = if (frameMode == FrameMode.FIT) {
            CameraController.SourceRatio.RATIO_4_3
        } else {
            CameraController.SourceRatio.RATIO_16_9
        }
        cameraController.setSourceRatio(ratio)

        btnFrameMode.text = when (frameMode) {
            FrameMode.FILL -> getString(R.string.frame_fill)
            FrameMode.FIT -> getString(R.string.frame_fit)
            FrameMode.RATIO_16_9 -> getString(R.string.frame_169)
        }

        val w = rootLayout.width
        val h = rootLayout.height
        previewTexture.layoutParams = if (frameMode == FrameMode.RATIO_16_9 && w > 0 && h > 0) {
            val targetW = minOf(w, (h * 16f / 9f).roundToInt())
            val targetH = (targetW * 9f / 16f).roundToInt()
            FrameLayout.LayoutParams(targetW, targetH, Gravity.CENTER)
        } else {
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER
            )
        }
        applyViewTransform()
    }

    /** 旋转微调生效、息屏保护开启时把对应按钮点亮 */
    private fun refreshToggleStates() {
        UiFactory.setIconTint(
            btnRotate,
            if (rotateFix != 0) UiFactory.COLOR_ACCENT else UiFactory.COLOR_ICON_NORMAL
        )
        UiFactory.setIconTint(
            btnAutoDim,
            if (autoDimEnabled) UiFactory.COLOR_ACCENT else UiFactory.COLOR_ICON_NORMAL
        )
    }

    // ------------------------------------------------------------------ 提示条与息屏保护

    /**
     * 显示一条提示（**完全替代系统 Toast**）。
     *
     * 系统 Toast 由 `NotificationManagerService` 统一排队派发，连续点按钮时提示会一个个排着队
     * 弹出来（MIUI / HyperOS 上尤其明显），而且时长固定 2 秒改不了。
     * 这里改用自绘的覆盖层：新提示**直接覆盖**旧文本并重置计时，连点也不会排队。
     *
     * @param durationMs 停留时长，默认 [TOAST_DURATION_MS]（1.2s，比系统 Toast 短一半）
     */
    private fun showToast(text: String, durationMs: Long = TOAST_DURATION_MS) {
        toastView.text = text
        toastView.animate().cancel()
        toastView.alpha = 1f
        toastView.visibility = View.VISIBLE
        mainHandler.removeCallbacks(hideToastRunnable)
        mainHandler.postDelayed(hideToastRunnable, durationMs)
        Log.d(DBG, "toast: $text")
    }

    /**
     * 息屏保护：把屏幕背光关到最低 —— **注意它不是真正的息屏**。
     *
     * 关键在 `WindowManager.LayoutParams#screenBrightness` 的语义：它调的是**背光**，
     * 作用在显示硬件上，**不会改变 SurfaceFlinger 合成出来的 framebuffer**。于是：
     * - 手机屏幕变黑（OLED 上像素完全不发光，约等于息屏，既防烧屏也省掉屏幕这份耗电）；
     * - **HDMI 镜像输出的是 framebuffer，因此投屏画面完全不受影响**；
     * - CPU / 相机 / 预览管线全部照常运行，`FLAG_KEEP_SCREEN_ON` 也不用摘。
     *
     * 这是在「屏幕镜像」方案下让「手机屏熄灭 + 投屏画面不变」唯一干净的做法。
     *
     * 另一条路是改用 `Presentation` 把画面投到独立的副屏，那样主屏可以真正息屏；
     * 但普通应用在 HyperOS 上能否拿到 presentation display 没有保证，
     * 且整套相机/UI 都要搬到另一个 window 里，改动量不划算 —— 不做。
     */
    private fun setDimmed(dim: Boolean) {
        if (isDimmed == dim) return
        isDimmed = dim
        val attrs = window.attributes
        attrs.screenBrightness = if (dim) {
            WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_OFF   // 0f：背光关到最低
        } else {
            WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE  // -1f：交还给系统亮度
        }
        window.attributes = attrs
        Log.d(DBG, "setDimmed: $dim")
    }

    /** 重置息屏倒计时（开启息屏保护时才真正计时） */
    private fun scheduleAutoDim() {
        mainHandler.removeCallbacks(autoDimRunnable)
        if (autoDimEnabled) {
            mainHandler.postDelayed(autoDimRunnable, AUTO_DIM_DELAY_MS)
        }
    }

    /** 停止息屏倒计时并立即恢复亮度（切后台、关闭开关时用） */
    private fun cancelAutoDim() {
        mainHandler.removeCallbacks(autoDimRunnable)
        setDimmed(false)
    }

    /**
     * 统一在这里捕捉「任意触摸」（ACTION_DOWN），处理三件事：
     * 1. 立刻恢复背光（息屏保护下，第一次触摸先唤醒屏幕）；
     * 2. 重置息屏倒计时；
     * 3. 悬浮图标状态下重置 3 秒自动隐藏倒计时。
     *
     * 放在 `dispatchTouchEvent` 而不是给每个 View 挂监听，是为了覆盖所有情况
     * （点画面、点面板按钮、点面板空白、点悬浮图标都算），不用到处补 `markUserInteraction()`。
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.action == MotionEvent.ACTION_DOWN) {
            setDimmed(false)
            scheduleAutoDim()
            if (uiState == UiState.FLOATING_ICON) scheduleAutoHide()
        }
        return super.dispatchTouchEvent(ev)
    }

    // ------------------------------------------------------------------ 状态机

    private fun onPreviewTapped() {
        if (!hasCameraPermission()) {
            Log.d(DBG, "onPreviewTapped: 无相机权限 -> 请求")
            requestCameraPermission()
            return
        }
        Log.d(DBG, "onPreviewTapped: state=$uiState")
        when (uiState) {
            UiState.CLEAN -> setState(UiState.FLOATING_ICON)
            UiState.FLOATING_ICON -> markUserInteraction()
            UiState.FULL_PANEL -> setState(UiState.FLOATING_ICON)
        }
    }

    private fun setState(state: UiState) {
        Log.d(DBG, "setState: $uiState -> $state")
        uiState = state
        when (state) {
            UiState.CLEAN -> {
                cancelAutoHide()
                fadeOut(floatingIcon)
                fadeOut(panelContainer)
            }

            UiState.FLOATING_ICON -> {
                fadeOut(panelContainer)
                fadeIn(floatingIcon)
                scheduleAutoHide()
            }

            UiState.FULL_PANEL -> {
                cancelAutoHide()
                fadeOut(floatingIcon)
                fadeIn(panelContainer)
            }
        }
    }

    private fun markUserInteraction() {
        if (uiState == UiState.FLOATING_ICON) scheduleAutoHide()
    }

    private fun scheduleAutoHide() {
        cancelAutoHide()
        mainHandler.postDelayed(autoHideRunnable, AUTO_HIDE_DELAY_MS)
    }

    private fun cancelAutoHide() {
        mainHandler.removeCallbacks(autoHideRunnable)
    }

    private fun fadeIn(view: View) {
        view.animate().cancel()
        view.visibility = View.VISIBLE
        view.alpha = 0f
        view.animate().alpha(1f).setDuration(FADE_DURATION_MS).setListener(null).start()
    }

    private fun fadeOut(view: View) {
        if (view.visibility == View.GONE) return
        view.animate().cancel()
        view.animate()
            .alpha(0f)
            .setDuration(FADE_DURATION_MS)
            .withEndAction { view.visibility = View.GONE }
            .start()
    }

    // ------------------------------------------------------------------ 权限

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    private fun requestCameraPermission() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.CAMERA),
            REQ_CAMERA_PERMISSION
        )
    }
}

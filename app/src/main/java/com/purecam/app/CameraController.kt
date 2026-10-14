package com.purecam.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.util.Range
import android.util.Rational
import android.util.Size
import android.view.Surface
import androidx.core.content.ContextCompat
import kotlin.math.atan
import kotlin.math.sqrt
import java.util.concurrent.Executor

/**
 * 相机控制层（**Camera2 实现**）。
 *
 * ## 为什么不用 CameraX
 * CameraX 的 `CameraSelector` 只能选中 `CameraManager#getCameraIdList()` 里的
 * **逻辑摄像头**（源码 `Camera2CameraFactory#getAvailableCameraIds()`），
 * 而超广角 / 长焦往往是藏在逻辑摄像头里面的**物理子摄**（`getPhysicalCameraIds()`），
 * 用 CameraX 的 `addCameraFilter` 按物理 id 过滤会匹配为空，于是永远只能拿到同一颗主摄
 * —— 表现就是「后置切了好几个焦段，画面完全不变」。
 *
 * Camera2 可以用「打开逻辑摄像头 + 把输出流路由到指定物理镜头」的方式真正切换镜头：
 * ```kotlin
 * OutputConfiguration(surface).apply { setPhysicalCameraId(physicalId) }
 * device.createCaptureSession(SessionConfiguration(SESSION_REGULAR, listOf(it), ...))
 * ```
 * （minSdk 29 ≥ API 28，接口可用。）
 *
 * 顺带的收益：预览画在我们自己的 TextureView 上，变换矩阵 100% 由我们掌握，
 * 镜像 / 垂直拉伸补偿不再受任何框架的「实现模式」限制。
 */
class CameraController(
    private val context: Context
) {

    /** 源画面比例：传感器原生是 4:3，16:9 会裁掉上下视野 */
    enum class SourceRatio {
        RATIO_16_9,
        RATIO_4_3
    }

    /**
     * 一个可切换的摄像头。
     *
     * @param cameraId 实际取画面用的 id（物理镜头时为「0-physical-2」这类 id）
     * @param openId   `openCamera()` 用的 id —— 物理镜头必须打开它所属的逻辑摄像头
     * @param isPhysical 是否为逻辑摄像头下面的物理子摄
     */
    data class CameraOption(
        val cameraId: String,
        val openId: String,
        val lensFacing: Int,
        val focalLengthMm: Float,
        val equivFocalMm: Float,
        val fovDegrees: Float,
        val label: String,
        val isPhysical: Boolean
    )

    companion object {
        const val TAG = "CameraController"

        /** 目标帧率（固定 30fps，最终由 HAL 决定） */
        const val TARGET_FPS = 30

        private const val MAX_WIDTH_16_9 = 1920
        private const val MAX_HEIGHT_16_9 = 1080
        private const val MAX_WIDTH_4_3 = 1920
        private const val MAX_HEIGHT_4_3 = 1440

        private const val ASPECT_TOLERANCE = 0.05f

        /** 35mm 胶片对角线（mm），用于换算等效焦距 */
        private const val FILM_DIAGONAL_MM = 43.27f
    }

    // ---------------------------------------------------------------- 回调

    /** 相机就绪（含当前镜头信息） */
    var onCameraReady: ((CameraOption) -> Unit)? = null

    /** 相机异常 */
    var onError: ((Throwable) -> Unit)? = null

    /** 实际生效的预览分辨率变化 */
    var onResolutionChanged: ((Size) -> Unit)? = null

    /** 物理镜头被设备拒绝（路由失败），已自动从可切换列表移除 */
    var onCameraDropped: ((CameraOption) -> Unit)? = null

    // ---------------------------------------------------------------- 内部状态

    private val cameraManager =
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    private val mainHandler = Handler(Looper.getMainLooper())
    private val bgThread = HandlerThread("PureCam-Camera2").apply { start() }
    private val bgHandler = Handler(bgThread.looper)
    private val bgExecutor = Executor { bgHandler.post(it) }

    private var surfaceTexture: SurfaceTexture? = null
    private var previewSurface: Surface? = null

    private var cameraDevice: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var requestBuilder: CaptureRequest.Builder? = null

    private var allOptions: List<CameraOption> = emptyList()
    private val droppedIds = mutableSetOf<String>()
    private var currentId: String? = null

    private var sourceRatio: SourceRatio = SourceRatio.RATIO_16_9
    private var focusLocked = false
    private var exposureIndex = 0
    private var aeRange: Range<Int>? = null
    private var aeStep: Rational? = null

    /** 当前生效的预览尺寸（Activity 用它计算变换矩阵） */
    var previewSize: Size = Size(1280, 720)
        private set

    /** 当前摄像头的传感器方向（0/90/180/270），Activity 算变换矩阵要用 */
    var sensorOrientation: Int = 90
        private set

    // ---------------------------------------------------------------- 生命周期

    /** 打开相机；SurfaceTexture 就绪后会自动创建会话 */
    fun startCamera() {
        val option = currentCamera() ?: run {
            onError?.invoke(IllegalStateException("设备没有可用摄像头"))
            return
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            onError?.invoke(SecurityException("没有相机权限"))
            return
        }
        if (cameraDevice != null) {
            createSession(option)
            return
        }
        try {
            Log.i(TAG, "打开摄像头 ${option.label}（id=${option.cameraId}, open=${option.openId}）")
            cameraManager.openCamera(option.openId, deviceStateCallback, bgHandler)
        } catch (e: Exception) {
            Log.e(TAG, "openCamera 失败", e)
            mainHandler.post { onError?.invoke(e) }
        }
    }

    fun unbind() {
        closeSession()
        cameraDevice?.close()
        cameraDevice = null
        focusLocked = false
    }

    fun shutdown() {
        unbind()
        bgThread.quitSafely()
    }

    /** TextureView 的 SurfaceTexture 就绪 */
    fun onSurfaceReady(st: SurfaceTexture) {
        surfaceTexture = st
        cameraDevice?.let { currentCamera()?.let { opt -> createSession(opt) } }
    }

    fun onSurfaceDestroyed() {
        closeSession()
    }

    // ---------------------------------------------------------------- 摄像头枚举 / 切换

    /** 可切换的摄像头（后置在前，按等效焦距从广到长；已剔除无法打开的） */
    fun availableCameras(): List<CameraOption> {
        if (allOptions.isEmpty()) {
            allOptions = enumerateCameras()
            // 默认选中后置里「等效焦距最接近 24mm」的那颗（通常是主摄）
            val back = allOptions.filter {
                it.lensFacing == CameraCharacteristics.LENS_FACING_BACK
            }
            currentId = (back.minByOrNull { kotlin.math.abs(it.equivFocalMm - 24f) }
                ?: allOptions.firstOrNull())?.cameraId
        }
        return allOptions.filter { it.cameraId !in droppedIds }
    }

    fun currentCamera(): CameraOption? {
        val list = availableCameras()
        if (list.isEmpty()) return null
        return list.firstOrNull { it.cameraId == currentId } ?: list.first()
    }

    /** 按 cameraId 选中（用于恢复上次选择） */
    fun selectCameraById(cameraId: String): Boolean {
        val hit = availableCameras().firstOrNull { it.cameraId == cameraId } ?: return false
        currentId = hit.cameraId
        return true
    }

    /** 循环切换到下一个摄像头 */
    fun switchCamera(): CameraOption? {
        val list = availableCameras()
        if (list.isEmpty()) return null
        val idx = list.indexOfFirst { it.cameraId == currentId }
        val next = list[(idx + 1) % list.size]
        currentId = next.cameraId
        reopen()
        return next
    }

    fun isBackCamera(): Boolean =
        currentCamera()?.lensFacing == CameraCharacteristics.LENS_FACING_BACK

    /** 切换源画面比例（16:9 / 4:3 全画幅），重建会话即可，不用重开摄像头 */
    fun setSourceRatio(ratio: SourceRatio) {
        if (sourceRatio == ratio && session != null) return
        sourceRatio = ratio
        currentCamera()?.let { createSession(it) }
    }

    private fun reopen() {
        val option = currentCamera() ?: return
        if (cameraDevice?.id == option.openId) {
            createSession(option)          // 同一颗逻辑摄像头：只重建会话（更快、不黑屏）
        } else {
            unbind()
            startCamera()
        }
    }

    // ---------------------------------------------------------------- 对焦 / 曝光

    fun isFocusLocked(): Boolean = focusLocked

    /** 中心区域对焦 + 测光 */
    fun focusAtCenter() {
        val b = requestBuilder ?: return
        val chars = characteristicsOf(currentCamera()?.cameraId) ?: return
        val array = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return
        val cx = array.width() / 2
        val cy = array.height() / 2
        val half = minOf(array.width(), array.height()) / 8
        val rect = MeteringRectangle(
            (cx - half).coerceAtLeast(0),
            (cy - half).coerceAtLeast(0),
            half * 2, half * 2,
            MeteringRectangle.METERING_WEIGHT_MAX
        )
        b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
        b.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(rect))
        b.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(rect))
        // 先用一次单次请求触发对焦，再恢复连续对焦（锁定状态除外）
        b.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
        submitOnce(b)
        b.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE)
        if (!focusLocked) {
            b.set(CaptureRequest.CONTROL_AF_MODE, pickContinuousAfMode(chars))
        }
        submitRepeating(b)
    }

    /** AF-L：把焦距停在当前位置（固定机位输出时画面不会反复拉风箱） */
    fun setFocusLocked(locked: Boolean) {
        focusLocked = locked
        val b = requestBuilder ?: return
        val chars = characteristicsOf(currentCamera()?.cameraId)
        if (locked) {
            // 取消正在进行的对焦扫描，然后停在 AUTO 模式不再触发 = 焦距锁定
            b.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL)
            submitOnce(b)
            b.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE)
            b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
        } else {
            b.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE)
            b.set(CaptureRequest.CONTROL_AF_MODE, pickContinuousAfMode(chars))
        }
        submitRepeating(b)
    }

    fun exposureRange(): Range<Int>? = aeRange
    fun exposureStep(): Rational? = aeStep
    fun currentExposureIndex(): Int = exposureIndex

    fun setExposureCompensation(index: Int): Boolean {
        val range = aeRange ?: return false
        exposureIndex = index.coerceIn(range.lower, range.upper)
        requestBuilder?.let {
            it.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, exposureIndex)
            submitRepeating(it)
        }
        return true
    }

    // ---------------------------------------------------------------- 会话

    private val deviceStateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(device: CameraDevice) {
            cameraDevice = device
            currentCamera()?.let { createSession(it) }
        }

        override fun onDisconnected(device: CameraDevice) {
            device.close()
            cameraDevice = null
        }

        override fun onError(device: CameraDevice, error: Int) {
            device.close()
            cameraDevice = null
            Log.e(TAG, "摄像头打开失败 code=$error")
            mainHandler.post { onError?.invoke(RuntimeException("摄像头打开失败（$error）")) }
        }
    }

    private val sessionStateCallback = object : CameraCaptureSession.StateCallback() {
        override fun onConfigured(s: CameraCaptureSession) {
            session = s
            startPreview(s)
        }

        override fun onConfigureFailed(s: CameraCaptureSession) {
            val option = currentCamera()
            if (option?.isPhysical == true) {
                // 这颗物理镜头被设备拒绝：记下来，从可切换列表里剔除，改用逻辑摄像头本身
                Log.w(TAG, "物理镜头 ${option.cameraId} 不可用，回退到 ${option.openId}")
                droppedIds.add(option.cameraId)
                mainHandler.post { onCameraDropped?.invoke(option) }
                createSession(option.copy(cameraId = option.openId, isPhysical = false))
                return
            }
            Log.e(TAG, "会话创建失败")
            mainHandler.post { onError?.invoke(RuntimeException("相机会话创建失败")) }
        }
    }

    private fun createSession(option: CameraOption) {
        val device = cameraDevice ?: return
        val st = surfaceTexture ?: return
        closeSession()

        val chars = characteristicsOf(option.cameraId) ?: run {
            mainHandler.post { onError?.invoke(RuntimeException("读不到摄像头参数")) }
            return
        }
        previewSize = pickPreviewSize(chars)
        sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        aeRange = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
        aeStep = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)

        st.setDefaultBufferSize(previewSize.width, previewSize.height)
        val surface = Surface(st).also { previewSurface = it }

        // 关键：物理镜头通过 OutputConfiguration 路由（API 28+）
        val output = OutputConfiguration(surface).apply {
            if (option.isPhysical && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                setPhysicalCameraId(option.cameraId)
            }
        }
        val config = SessionConfiguration(
            SessionConfiguration.SESSION_REGULAR,
            listOf(output),
            bgExecutor,
            sessionStateCallback
        )
        Log.i(TAG, "创建会话：${option.label} ${previewSize.width}x${previewSize.height} @ ${TARGET_FPS}fps")
        try {
            device.createCaptureSession(config)
        } catch (e: Exception) {
            Log.e(TAG, "createCaptureSession 异常", e)
            mainHandler.post { onError?.invoke(e) }
        }
    }

    private fun closeSession() {
        runCatching { session?.close() }
        session = null
        runCatching { previewSurface?.release() }
        previewSurface = null
    }

    private fun startPreview(s: CameraCaptureSession) {
        val device = cameraDevice ?: return
        val surface = previewSurface ?: return
        val chars = characteristicsOf(currentCamera()?.cameraId)
        val b = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(surface)
            set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            set(
                CaptureRequest.CONTROL_AF_MODE,
                if (focusLocked) CaptureRequest.CONTROL_AF_MODE_AUTO
                else pickContinuousAfMode(chars)
            )
            // 固定 30fps：只在 HAL 支持该区间时才下发
            val ranges = chars?.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            if (ranges == null || ranges.any { it.lower <= TARGET_FPS && it.upper >= TARGET_FPS }) {
                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(TARGET_FPS, TARGET_FPS))
            }
            if (exposureIndex != 0 && aeRange != null) {
                set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, exposureIndex)
            }
            applyLowLatency(this, chars)
        }
        requestBuilder = b
        submitRepeating(b)
        mainHandler.post {
            onResolutionChanged?.invoke(previewSize)
            currentCamera()?.let { onCameraReady?.invoke(it) }
        }
    }

    // ---------------------------------------------------------------- 低延迟

    /**
     * **手机端能榨出来的最大一块延迟就在这里。**
     *
     * 相机 HAL 默认会开一串后处理，其中带「时域（多帧）」成分的才是延迟大户：
     * - **时域降噪 TNR**：要拿前后若干帧比对，**天然引入 1~3 帧（33~100ms）延迟**；
     * - 边缘增强 / 色差（紫边）校正：部分 HAL 也带时域成分；
     * - 视频防抖 EIS：额外裁切 + 缓冲，也会加延迟。
     *
     * 对「实时视频输出」这个场景，这些处理的收益本来就很小（画面一直在动，
     * 多帧降噪反而容易拖影），代价却是实打实的延迟。
     *
     * ⚠️ 一律用 **FAST 而不是 OFF**：FAST 只保留空域（单帧）处理，既去掉了帧间延迟，
     * 又不会像 OFF 那样让噪点/紫边明显变差。这是「延迟 vs 画质」最划算的一档。
     *
     * 实测预期：能省 1~2 帧 ≈ **33~66ms**。已经写进日志方便核对。
     */
    private fun applyLowLatency(b: CaptureRequest.Builder, chars: CameraCharacteristics?) {
        // 降噪（延迟大户：时域降噪需要多帧）
        b.set(
            CaptureRequest.NOISE_REDUCTION_MODE,
            pickMode(
                chars?.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES),
                CaptureRequest.NOISE_REDUCTION_MODE_FAST,
                CaptureRequest.NOISE_REDUCTION_MODE_OFF
            )
        )
        // 边缘增强
        b.set(
            CaptureRequest.EDGE_MODE,
            pickMode(
                chars?.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES),
                CaptureRequest.EDGE_MODE_FAST,
                CaptureRequest.EDGE_MODE_OFF
            )
        )
        // 色差（紫边）校正：API 33+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            b.set(
                CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE,
                pickMode(
                    chars?.get(CameraCharacteristics.COLOR_CORRECTION_AVAILABLE_ABERRATION_MODES),
                    CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_FAST,
                    CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_OFF
                )
            )
        }
        // 电子防抖：会额外裁切视野并加缓冲，明确关掉
        val vsModes =
            chars?.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)
        if (vsModes == null ||
            vsModes.contains(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
        ) {
            b.set(
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF
            )
        }
        Log.i(
            TAG,
            "低延迟模式：NR=${b.get(CaptureRequest.NOISE_REDUCTION_MODE)} " +
                "EDGE=${b.get(CaptureRequest.EDGE_MODE)}"
        )
    }

    /**
     * 连续对焦模式：优先 `CONTINUOUS_VIDEO`。
     *
     * 与 `CONTINUOUS_PICTURE` 的区别：VIDEO 是为「持续取流」优化的，对焦过程更平滑，
     * 不会为了追求静止画面的锐利而反复拉风箱 —— 我们输出的是视频流，VIDEO 才是正确选择。
     * 部分老旧 HAL 只支持 PICTURE，此时退回 PICTURE。
     */
    private fun pickContinuousAfMode(chars: CameraCharacteristics?): Int =
        pickMode(
            chars?.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES),
            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO,
            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
        )

    /**
     * 在硬件支持的模式列表里挑一个：优先 [preferred]，不支持则退 [fallback]，
     * 都不可用（或读不到列表）时原样返回 [preferred] —— Camera2 会自行忽略无效设置，
     * 比不设置更安全（不设置时 HAL 会用默认模式，那往往正是带时域处理的那档）。
     */
    private fun pickMode(available: IntArray?, preferred: Int, fallback: Int): Int {
        if (available == null) return preferred
        if (available.contains(preferred)) return preferred
        if (available.contains(fallback)) return fallback
        return preferred
    }

    private fun submitRepeating(b: CaptureRequest.Builder) {
        runCatching { session?.setRepeatingRequest(b.build(), null, bgHandler) }
            .onFailure { Log.w(TAG, "setRepeatingRequest 失败", it) }
    }

    private fun submitOnce(b: CaptureRequest.Builder) {
        runCatching { session?.capture(b.build(), null, bgHandler) }
            .onFailure { Log.w(TAG, "capture 失败", it) }
    }

    // ---------------------------------------------------------------- 硬件查询

    private fun characteristicsOf(cameraId: String?): CameraCharacteristics? {
        if (cameraId == null) return null
        return runCatching { cameraManager.getCameraCharacteristics(cameraId) }
            .onFailure { Log.w(TAG, "读取 $cameraId 特性失败", it) }
            .getOrNull()
    }

    /**
     * 枚举所有可切换的摄像头：
     * 1. `cameraIdList` 里的逻辑摄像头；
     * 2. Android 9+ 每个逻辑摄像头下面的物理子摄（超广角 / 长焦都在这里）。
     */
    private fun enumerateCameras(): List<CameraOption> {
        val result = mutableListOf<CameraOption>()
        val ids = runCatching { cameraManager.cameraIdList.toList() }.getOrDefault(emptyList())

        for (logicalId in ids) {
            val chars = characteristicsOf(logicalId) ?: continue
            buildOption(logicalId, logicalId, chars, false)?.let { result.add(it) }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                for (physicalId in runCatching { chars.physicalCameraIds }.getOrDefault(emptySet())) {
                    if (physicalId == logicalId) continue
                    val pChars = characteristicsOf(physicalId) ?: continue
                    buildOption(physicalId, logicalId, pChars, true)?.let { result.add(it) }
                }
            }
        }

        return result
            .distinctBy { it.cameraId }
            .sortedWith(
                compareBy(
                    {
                        if (it.lensFacing == CameraCharacteristics.LENS_FACING_BACK) 0 else 1
                    },
                    { it.equivFocalMm }
                )
            )
            .also {
                Log.i(TAG, "可用摄像头 ${it.size} 个：" + it.joinToString { o ->
                    "${o.label}(${o.cameraId}${if (o.isPhysical) " 物理" else " 逻辑"})"
                })
            }
    }

    private fun buildOption(
        cameraId: String,
        openId: String,
        chars: CameraCharacteristics,
        isPhysical: Boolean
    ): CameraOption? {
        val facing = chars.get(CameraCharacteristics.LENS_FACING) ?: return null
        val focals = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            ?.filter { it > 0f }?.takeIf { it.isNotEmpty() } ?: return null
        val focal = focals.first()

        var equiv = 0f
        var fov = 0f
        val sensor = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        if (sensor != null && sensor.width > 0f && sensor.height > 0f) {
            val diagonal = sqrt(sensor.width * sensor.width + sensor.height * sensor.height)
            equiv = focal * FILM_DIAGONAL_MM / diagonal
            fov = (2.0 * atan((sensor.width / (2f * focal)).toDouble()) * 180.0 / Math.PI).toFloat()
        }

        return CameraOption(
            cameraId = cameraId,
            openId = openId,
            lensFacing = facing,
            focalLengthMm = focal,
            equivFocalMm = equiv,
            fovDegrees = fov,
            label = buildLabel(facing, equiv, fov, isPhysical),
            isPhysical = isPhysical
        )
    }

    private fun buildLabel(facing: Int, equiv: Float, fov: Float, isPhysical: Boolean): String {
        val side = if (facing == CameraCharacteristics.LENS_FACING_BACK) "后" else "前"
        val tag = if (isPhysical) "" else "·主"
        val type = when {
            equiv <= 0f -> "置摄像头"
            equiv < 18f -> "超广角"
            equiv < 35f -> "广角"
            equiv < 60f -> "中焦"
            else -> "长焦"
        }
        return when {
            equiv > 0f -> "$side$type${equiv.toInt()}mm$tag"
            fov > 0f -> "$side$type${fov.toInt()}°$tag"
            else -> "${side}置摄像头$tag"
        }
    }

    /**
     * 挑选预览尺寸：优先「目标比例内的最大档」，退而求其次取「不超过上限的最大档」。
     * 用 `SurfaceTexture` 支持的尺寸，避免选到设备不支持的档位。
     */
    private fun pickPreviewSize(chars: CameraCharacteristics): Size {
        val sizes = runCatching {
            chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?.getOutputSizes(SurfaceTexture::class.java)?.toList()
        }.getOrNull()

        if (sizes.isNullOrEmpty()) {
            return if (sourceRatio == SourceRatio.RATIO_4_3) Size(1440, 1080) else Size(1280, 720)
        }

        val target = if (sourceRatio == SourceRatio.RATIO_4_3) 4f / 3f else 16f / 9f
        val maxW = if (sourceRatio == SourceRatio.RATIO_4_3) MAX_WIDTH_4_3 else MAX_WIDTH_16_9
        val maxH = if (sourceRatio == SourceRatio.RATIO_4_3) MAX_HEIGHT_4_3 else MAX_HEIGHT_16_9

        val inRatio = sizes.filter {
            it.width <= maxW && it.height <= maxH &&
                kotlin.math.abs(it.width.toFloat() / it.height - target) < ASPECT_TOLERANCE
        }
        if (inRatio.isNotEmpty()) {
            return inRatio.maxByOrNull { it.width * it.height }!!
        }
        val withinLimit = sizes.filter { it.width <= maxW && it.height <= maxH }
        if (withinLimit.isNotEmpty()) {
            return withinLimit.maxByOrNull { it.width * it.height }!!
        }
        return sizes.maxByOrNull { it.width * it.height }!!
    }
}

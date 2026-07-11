// 火崽崽助手（HZZS）屏幕截图采集器。
//
// 职责：
// - 通过多方法 fallback 机制获取当前屏幕像素
// - 返回 ARGB 像素数组（IntArray），供 C++ 视觉识别模块使用
// - 在所有前置条件不满足时优雅降级（日志 + Toast），不崩溃
//
// 截图方法优先级（从高到低）：
// 1. AccessibilityService.takeScreenshot() — API 31+，无障碍服务已连接（最快，无弹窗）
// 2. SurfaceControl.screenshot() — API 29+，反射调用系统底层截图（无需权限，华为 EMUI 可用）
// 3. MediaProjection — 需要用户授权弹窗（可靠，所有 API 21+ 可用）
// 4. 全部失败 → 返回 null，视觉识别跳过
//
// 线程模型：
// - takeScreenshot() 内部会阻塞调用线程（最长 TIMEOUT_MS 毫秒）
// - 应在后台线程调用，不要在主线程直接调用
// - 多方法 fallback 机制会在首次调用时自动探测可用方法并缓存

package top.azek431.hzzs.data.vision

import android.app.Activity
import android.app.Activity.RESULT_OK
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import top.azek431.hzzs.features.service.AutoOperationService
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 截图结果：包含像素数组和屏幕尺寸。
 *
 * @property pixels ARGB 像素数组（每元素 0xAARRGGBB），长度 = width * height
 * @property width 屏幕宽度（像素）
 * @property height 屏幕高度（像素）
 * @property density 屏幕密度（dpi），可用于 DPI 归一化
 * @property methodName 使用的截图方法名称（用于日志追踪）
 */
data class CaptureResult(
    val pixels: IntArray,
    val width: Int,
    val height: Int,
    val density: Int,
    val methodName: String = "unknown",
)

/**
 * 屏幕截图采集器。
 *
 * 多方法 fallback 截图：
 * 1. AccessibilityService.takeScreenshot() — API 31+，无障碍服务已连接
 * 2. SurfaceControl.screenshot() — API 29+，反射调用系统底层截图
 * 3. MediaProjection — 需要用户授权弹窗
 *
 * 首次调用时自动探测可用方法，后续调用直接使用最佳方法。
 * 所有方法失败时返回 null，不抛异常。
 *
 * 调用示例：
 * ```
 * val capture = ScreenshotCapture.takeScreenshot(context)
 * if (capture != null) {
 *     val (pixelArray, w, h) = capture
 *     val bottleResult = VisionAnalysisBridge.scanGreenBottle(pixelArray, w, h, playerBounds)
 * }
 * ```
 */
object ScreenshotCapture {

    private const val TAG = "HZZS-Screenshot"

    /** takeScreenshot() 超时时间（毫秒）— 5 秒 */
    private const val TIMEOUT_MS = 5000L

    // ==================== 截图方法探测 ====================

    /** 已探测的可用截图方法（首次调用时自动设置） */
    @Volatile
    private var detectedMethod: ScreenshotMethod? = null

    /** 截图方法枚举 */
    private enum class ScreenshotMethod {
        ACCESSIBILITY,
        SURFACE_CONTROL,
        MEDIA_PROJECTION,
    }

    // ==================== MediaProjection 状态 ====================

    /** MediaProjection 管理器（通过反射持有，避免 stubs 不可用） */
    @Volatile
    private var mediaProjectionManager: Any? = null

    /** MediaProjection 实例（截图授权后持有，Any 避免 stubs 不可用） */
    @Volatile
    private var mediaProjection: Any? = null

    /** VirtualDisplay 实例（用于创建截图 Surface） */
    @Volatile
    private var virtualDisplay: Any? = null

    /** MediaProjection 截图结果缓存（用于一次性读取） */
    @Volatile
    private var capturedBitmap: Bitmap? = null

    /** 用于 MediaProjection 截图的线程池 */
    private val mediaProjectionExecutor = Executors.newSingleThreadExecutor {
        Thread(it, "hzzs-media-projection").apply { isDaemon = true }
    }

    // ==================== 公开 API ====================

    /**
     * 尝试截取当前屏幕。
     *
     * 流程：
     * 1. 如果尚未探测可用方法，按优先级依次尝试
     * 2. 使用最佳可用方法截图
     * 3. 截图失败时降级到下一方法
     * 4. 全部失败时返回 null
     *
     * 注意：此方法内部会阻塞调用线程（最长 TIMEOUT_MS 毫秒），
     * 应在后台线程调用，不要在主线程直接调用。
     *
     * @param context 上下文（用于获取系统服务和 Toast 展示）
     * @return 截图结果，或 null（所有方法均不可用时）
     */
    @JvmStatic
    fun takeScreenshot(context: Context): CaptureResult? {
        val appContext = context.applicationContext

        // 首次调用时自动探测可用方法
        if (detectedMethod == null) {
            detectedMethod = detectBestMethod(appContext)
        }

        val method = detectedMethod ?: run {
            Log.w(TAG, "[Screenshot] no screenshot method available.")
            return null
        }

        // 按探测到的方法截图，失败则降级
        return try {
            when (method) {
                ScreenshotMethod.ACCESSIBILITY -> takeScreenshotViaAccessibility(appContext)
                ScreenshotMethod.SURFACE_CONTROL -> takeScreenshotViaSurfaceControl(appContext)
                ScreenshotMethod.MEDIA_PROJECTION -> takeScreenshotViaMediaProjection(appContext)
            }
        } catch (e: Exception) {
            Log.w(TAG, "[Screenshot] method $method failed: ${e.message}")
            // 降级：清除缓存，下次重新探测
            detectedMethod = null
            null
        }
    }

    /**
     * 请求 MediaProjection 授权（需要 Activity 上下文）。
     *
     * 此方法会弹出一个系统级授权对话框（"开始录制/截图"），
     * 用户确认后返回一个 Intent，调用方需在 onActivityResult 中保存结果。
     *
     * @param activity 活动上下文（用于弹出授权对话框）
     * @param requestCode 请求码（用于 onActivityResult 识别）
     */
    @JvmStatic
    fun requestMediaProjectionPermission(activity: Activity, requestCode: Int = 1001) {
        try {
            val appContext = activity.applicationContext
            val manager = appContext.getSystemService(Context.MEDIA_PROJECTION_SERVICE)
            if (manager == null) {
                Log.w(TAG, "[Screenshot] MediaProjectionManager not available.")
                return
            }
            mediaProjectionManager = manager

            val intent = manager.javaClass.getMethod("createScreenCaptureIntent").invoke(manager) as Intent
            activity.startActivityForResult(intent, requestCode)
            Log.i(TAG, "[Screenshot] MediaProjection permission dialog shown.")
        } catch (e: Exception) {
            Log.w(TAG, "[Screenshot] Failed to request MediaProjection permission: ${e.message}")
        }
    }

    /**
     * 处理 MediaProjection 授权结果。
     *
     * 在 Activity 的 onActivityResult 中调用此方法。
     *
     * @param resultCode 授权结果码（应为 RESULT_OK）
     * @param data 授权 Intent（包含 MediaProjection 凭据）
     * @return true 如果授权成功
     */
    @JvmStatic
    fun handleMediaProjectionResult(context: Context, resultCode: Int, data: Intent?): Boolean {
        if (resultCode != RESULT_OK || data == null) {
            Log.w(TAG, "[Screenshot] MediaProjection permission denied (code=$resultCode).")
            return false
        }

        try {
            val projectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE)
            if (projectionManager == null) {
                Log.w(TAG, "[Screenshot] MediaProjectionManager not available after auth.")
                return false
            }

            val proj = projectionManager.javaClass.getMethod(
                "getMediaProjection",
                Int::class.java, Intent::class.java
            ).invoke(projectionManager, resultCode, data)
            if (proj == null) {
                Log.w(TAG, "[Screenshot] Failed to get MediaProjection instance.")
                return false
            }

            mediaProjection = proj
            detectedMethod = ScreenshotMethod.MEDIA_PROJECTION

            Log.i(TAG, "[Screenshot] MediaProjection authorized successfully.")
            return true
        } catch (e: Exception) {
            Log.w(TAG, "[Screenshot] MediaProjection auth failed: ${e.message}")
            return false
        }
    }

    /**
     * 释放 MediaProjection 资源。
     *
     * 在应用退出或悬浮窗关闭时调用。
     */
    @JvmStatic
    fun release() {
        try {
            mediaProjection?.javaClass?.getMethod("stop")?.invoke(mediaProjection)
        } catch (e: Exception) { /* ignore */ }
        mediaProjection = null
        capturedBitmap?.recycle()
        capturedBitmap = null
        try {
            virtualDisplay?.javaClass?.getMethod("release")?.invoke(virtualDisplay)
        } catch (e: Exception) { /* ignore */ }
        virtualDisplay = null
        mediaProjectionExecutor.shutdown()
        detectedMethod = null
        Log.d(TAG, "[Screenshot] resources released.")
    }

    // ==================== 自动探测 ====================

    /**
     * 自动探测最佳截图方法。
     *
     * 按优先级从高到低尝试：
     * 1. AccessibilityService（API 31+，无障碍服务已连接）
     * 2. SurfaceControl（API 29+）
     * 3. MediaProjection（需要用户授权，不在此处探测）
     *
     * @param context 上下文
     * @return 最佳可用方法，或 null（全部不可用）
     */
    private fun detectBestMethod(context: Context): ScreenshotMethod? {
        // 方法 1：AccessibilityService（API 31+）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                val dummyLatch = CountDownLatch(1)
                val available = AutoOperationService.proxyTakeScreenshot(
                    /* displayId = */ 0,
                    /* executor = */ context.mainExecutor,
                    object : android.accessibilityservice.AccessibilityService.TakeScreenshotCallback {
                        override fun onSuccess(screenShotResult: android.accessibilityservice.AccessibilityService.ScreenshotResult) { dummyLatch.countDown() }
                        override fun onFailure(errorCode: Int) { dummyLatch.countDown() }
                    },
                )
                if (available) {
                    dummyLatch.await(500, TimeUnit.MILLISECONDS)
                    Log.i(TAG, "[Detect] AccessibilityService available.")
                    return ScreenshotMethod.ACCESSIBILITY
                } else {
                    Log.d(TAG, "[Detect] AccessibilityService proxy returned false.")
                }
            } catch (e: Exception) {
                Log.d(TAG, "[Detect] AccessibilityService check failed: ${e.message}")
            }
        }

        // 方法 2：SurfaceControl（API 29+）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val testResult = trySurfaceControlScreenshot(context)
                if (testResult != null) {
                    Log.i(TAG, "[Detect] SurfaceControl available.")
                    return ScreenshotMethod.SURFACE_CONTROL
                } else {
                    Log.d(TAG, "[Detect] SurfaceControl returned null.")
                }
            } catch (e: Exception) {
                Log.d(TAG, "[Detect] SurfaceControl check failed: ${e.message}")
            }
        }

        // 方法 3：MediaProjection（需要用户授权，标记为可用但不自动使用）
        Log.d(TAG, "[Detect] MediaProjection requires user authorization — not auto-detected.")

        Log.w(TAG, "[Detect] no automatic screenshot method available (API ${Build.VERSION.SDK_INT}).")
        return null
    }

    // ==================== 方法 1：AccessibilityService ====================

    private fun takeScreenshotViaAccessibility(context: Context): CaptureResult? {
        val appContext = context.applicationContext

        val latch = CountDownLatch(1)
        var capturedResult: CaptureResult? = null
        var captureError: String? = null

        try {
            val success = AutoOperationService.proxyTakeScreenshot(
                /* displayId = */ 0,
                /* executor = */ appContext.mainExecutor,
                object : android.accessibilityservice.AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(screenShotResult: android.accessibilityservice.AccessibilityService.ScreenshotResult) {
                        try {
                            capturedResult = convertScreenshotResult(screenShotResult, appContext, "AccessibilityService")
                        } catch (e: Exception) {
                            captureError = "Failed to convert screenshot: ${e.message}"
                        } finally {
                            latch.countDown()
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        captureError = when (errorCode) {
                            -1 -> "takeScreenshot failed (code=$errorCode)"
                            1 -> "takeScreenshot denied (permission/service issue)"
                            2 -> "takeScreenshot timed out"
                            else -> "takeScreenshot unknown error (code=$errorCode)"
                        }
                        latch.countDown()
                    }
                },
            )

            if (!success) {
                Log.w(TAG, "[Screenshot] AccessibilityService proxy returned false.")
                return null
            }
        } catch (e: Exception) {
            captureError = "takeScreenshot invocation threw: ${e.message}"
            latch.countDown()
        }

        latch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)

        if (capturedResult != null) {
            Log.i(TAG, "[Screenshot] captured ${capturedResult.width}x${capturedResult.height} via AccessibilityService.")
            return capturedResult
        }

        Log.w(TAG, "[Screenshot] AccessibilityService failed: ${captureError ?: "unknown"}")
        return null
    }

    // ==================== 方法 2：SurfaceControl ====================

    private fun trySurfaceControlScreenshot(context: Context): CaptureResult? {
        return takeScreenshotViaSurfaceControl(context)
    }

    @Suppress("PrivateApi", "LongMethod")
    private fun takeScreenshotViaSurfaceControl(context: Context): CaptureResult? {
        val appContext = context.applicationContext
        val metrics = appContext.resources.displayMetrics

        try {
            val surfaceControlClass = Class.forName("android.view.SurfaceControl")

            // API 30+：使用 getBuiltInScreenshot(context)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    val getBuiltInScreenshot = surfaceControlClass.getMethod(
                        "getBuiltInScreenshot",
                        android.content.Context::class.java
                    )
                    val screenshotObj = getBuiltInScreenshot.invoke(null, appContext)
                    if (screenshotObj != null) {
                        val getImageBuffer = screenshotObj.javaClass.getMethod("getImageBuffer")
                        val buffer = getImageBuffer.invoke(screenshotObj) as? ByteBuffer
                        if (buffer != null && buffer.hasArray()) {
                            val width = screenshotObj.javaClass.getMethod("getWidth").invoke(screenshotObj) as Int
                            val height = screenshotObj.javaClass.getMethod("getHeight").invoke(screenshotObj) as Int
                            val density = metrics.densityDpi
                            return buffer.toIntCaptureResult(width, height, density, "SurfaceControl")
                        }
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "[Screenshot] getBuiltInScreenshot not available: ${e.message}")
                }
            }

            // API 29-30：使用 getScreenshotResult(displayToken, width, height)
            val displayToken = surfaceControlClass.getDeclaredField("DISPLAY_TOKEN").get(null)
            val getScreenshotResultMethod = surfaceControlClass.getMethod(
                "getScreenshotResult",
                android.os.IBinder::class.java,
                Int::class.java,
                Int::class.java
            )

            val result = getScreenshotResultMethod.invoke(
                null,
                displayToken,
                metrics.widthPixels,
                metrics.heightPixels
            )

            if (result != null) {
                // 尝试 ByteBuffer 路径
                val getImageBuffer = result.javaClass.getMethod("getImageBuffer")
                val buffer = getImageBuffer.invoke(result) as? ByteBuffer

                if (buffer != null && buffer.hasArray()) {
                    val width = result.javaClass.getMethod("getWidth").invoke(result) as Int
                    val height = result.javaClass.getMethod("getHeight").invoke(result) as Int
                    val density = metrics.densityDpi
                    return buffer.toIntCaptureResult(width, height, density, "SurfaceControl")
                }

                // 尝试 HardwareBuffer 路径
                val getHardwareBuffer = result.javaClass.getMethod("getHardwareBuffer")
                val hardwareBuffer = getHardwareBuffer.invoke(result)

                if (hardwareBuffer != null) {
                    val width = result.javaClass.getMethod("getWidth").invoke(result) as Int
                    val height = result.javaClass.getMethod("getHeight").invoke(result) as Int
                    val density = metrics.densityDpi
                    return convertHardwareBuffer(
                        hardwareBuffer as android.hardware.HardwareBuffer,
                        width, height, density, "SurfaceControl"
                    )
                }
            }
        } catch (e: NoSuchMethodException) {
            Log.d(TAG, "[Screenshot] SurfaceControl method not found: ${e.message}")
        } catch (e: ClassNotFoundException) {
            Log.d(TAG, "[Screenshot] SurfaceControl class not found: ${e.message}")
        } catch (e: Exception) {
            Log.w(TAG, "[Screenshot] SurfaceControl screenshot failed: ${e.message}")
        }

        return null
    }

    // ==================== 方法 3：MediaProjection ====================

    /**
     * 通过 MediaProjection 截图。
     *
     * 需要用户事先授权（通过 requestMediaProjectionPermission + handleMediaProjectionResult）。
     * 授权后持有 MediaProjection 实例，可以反复截图。
     *
     * 流程：
     * 1. 检查 MediaProjection 是否已授权
     * 2. 创建 VirtualDisplay（指向截图 Surface）
     * 3. 通过 ImageReader 读取截图像素
     */
    @Suppress("LongMethod")
    private fun takeScreenshotViaMediaProjection(context: Context): CaptureResult? {
        val proj = mediaProjection ?: run {
            Log.w(TAG, "[Screenshot] MediaProjection not authorized. Call requestMediaProjectionPermission first.")
            return null
        }

        val appContext = context.applicationContext
        val metrics = appContext.resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        // 创建 ImageReader 用于接收截图
        val imageReader = android.media.ImageReader.newInstance(
            width, height,
            android.graphics.PixelFormat.RGBA_8888,
            1
        )

        // 创建 VirtualDisplay，将屏幕内容输出到 ImageReader 的 Surface
        try {
            val createVD = proj.javaClass.getMethod(
                "createVirtualDisplay",
                String::class.java, Int::class.java, Int::class.java, Int::class.java,
                Int::class.java, android.view.Surface::class.java,
                android.os.Handler::class.java
            )
            // FLAG_MATCH_DISPLAY_CONTENT = 0x00000004
            val result = createVD.invoke(
                proj,
                "hzzs-screenshot",
                width, height, density,
                0x00000004,
                imageReader.surface,
                null
            )
            virtualDisplay = result
        } catch (e: Exception) {
            Log.w(TAG, "[Screenshot] createVirtualDisplay failed: ${e.message}")
            imageReader.close()
            return null
        }

        // 异步截图（避免阻塞调用线程太久）
        val latch = CountDownLatch(1)
        var result: CaptureResult? = null

        mediaProjectionExecutor.execute {
            try {
                // 等待一帧渲染完成
                Thread.sleep(200)

                val image = imageReader.acquireLatestImage()
                if (image != null) {
                    try {
                        val planes = image.planes
                        val buffer = planes[0].buffer
                        val pixelStride = planes[0].pixelStride

                        if (pixelStride == 1) {
                            // 紧凑排列，直接读取为字节再转 IntArray
                            val bytes = ByteArray(width * height * 4)
                            buffer.get(bytes)
                            val pixels = IntArray(width * height)
                            for (i in 0 until width * height) {
                                val base = i * 4
                                val a = bytes[base].toInt() and 0xFF
                                val r = bytes[base + 1].toInt() and 0xFF
                                val g = bytes[base + 2].toInt() and 0xFF
                                val b = bytes[base + 3].toInt() and 0xFF
                                pixels[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
                            }
                            result = CaptureResult(pixels, width, height, density, "MediaProjection")
                        } else {
                            // 需要处理 stride
                            val pixels = IntArray(width * height)
                            val rowBytes = planes[0].rowStride
                            for (row in 0 until height) {
                                for (col in 0 until width) {
                                    val offset = row * rowBytes + col * pixelStride
                                    val a = buffer.get(offset).toInt() and 0xFF
                                    val r = buffer.get(offset + 1).toInt() and 0xFF
                                    val g = buffer.get(offset + 2).toInt() and 0xFF
                                    val b = buffer.get(offset + 3).toInt() and 0xFF
                                    pixels[row * width + col] = (a shl 24) or (r shl 16) or (g shl 8) or b
                                }
                            }
                            result = CaptureResult(pixels, width, height, density, "MediaProjection")
                        }
                    } finally {
                        image.close()
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "[Screenshot] MediaProjection capture failed: ${e.message}")
            } finally {
                latch.countDown()
            }
        }

        // 等待截图完成（最长 3 秒）
        latch.await(3000, TimeUnit.MILLISECONDS)

        // 清理
        try { virtualDisplay?.javaClass?.getMethod("release")?.invoke(virtualDisplay) } catch (e: Exception) { /* ignore */ }
        imageReader.close()

        if (result != null) {
            Log.i(TAG, "[Screenshot] captured ${result.width}x${result.height} via MediaProjection.")
            return result
        }

        Log.w(TAG, "[Screenshot] MediaProjection screenshot returned null.")
        return null
    }

    // ==================== 截图结果转换 ====================

    /**
     * 将 AccessibilityService.ScreenshotResult 转换为 CaptureResult。
     */
    private fun convertScreenshotResult(
        result: android.accessibilityservice.AccessibilityService.ScreenshotResult,
        context: Context,
        methodName: String,
    ): CaptureResult {
        val resultClass = result.javaClass

        val width = (resultClass.getDeclaredMethod("getWidth").invoke(result) as Number).toInt()
        val height = (resultClass.getDeclaredMethod("getHeight").invoke(result) as Number).toInt()
        val density = context.resources.displayMetrics.densityDpi

        // 策略 1：HardwareBuffer 路径（API 33+）
        try {
            val getHardwareBuffer = resultClass.getMethod("getHardwareBuffer")
            val hardwareBuffer = getHardwareBuffer.invoke(result)
            if (hardwareBuffer != null) {
                return convertHardwareBuffer(hardwareBuffer as android.hardware.HardwareBuffer, width, height, density, methodName)
            }
        } catch (e: Exception) {
            Log.d(TAG, "[Screenshot] HardwareBuffer path not available: ${e.message}")
        }

        // 策略 2：ByteBuffer imageBuffer 路径（API 31-32）
        try {
            val getImageBuffer = resultClass.getMethod("getImageBuffer")
            val imageBuffer = getImageBuffer.invoke(result) as? ByteBuffer
            if (imageBuffer != null && imageBuffer.hasArray()) {
                return imageBuffer.toIntCaptureResult(width, height, density, methodName)
            }
        } catch (e: Exception) {
            Log.w(TAG, "[Screenshot] imageBuffer path failed: ${e.message}")
        }

        throw IllegalStateException("ScreenshotResult conversion failed: neither HardwareBuffer nor imageBuffer available")
    }

    /**
     * 将 HardwareBuffer 转换为 CaptureResult。
     */
    @Suppress("UNCHECKED_CAST")
    private fun convertHardwareBuffer(
        hb: android.hardware.HardwareBuffer,
        width: Int,
        height: Int,
        density: Int,
        methodName: String,
    ): CaptureResult {
        return try {
            val createMethod = Bitmap::class.java.getDeclaredMethod(
                "createFromHardwareBuffer",
                android.hardware.HardwareBuffer::class.java
            )
            val bitmap = createMethod.invoke(null, hb) as Bitmap
            bitmap.toIntArray(width, height, density, methodName)
        } catch (e: Exception) {
            Log.w(TAG, "[Screenshot] createFromHardwareBuffer failed: ${e.message}")
            try {
                val createMethod2 = Bitmap::class.java.getDeclaredMethod(
                    "create",
                    android.hardware.HardwareBuffer::class.java,
                    Int::class.java,
                    Int::class.java
                )
                val bitmap = createMethod2.invoke(null, hb, width, height) as Bitmap
                bitmap.toIntArray(width, height, density, methodName)
            } catch (e2: Exception) {
                throw IllegalStateException("HardwareBuffer to Bitmap conversion failed: ${e2.message}", e2)
            }
        }
    }

    /**
     * 将 Bitmap 转换为 CaptureResult。
     */
    private fun Bitmap.toIntArray(
        width: Int,
        height: Int,
        density: Int,
        methodName: String,
    ): CaptureResult {
        val pixels = IntArray(width * height)
        val config = config
        if (config == Bitmap.Config.ARGB_8888) {
            getPixels(pixels, 0, width, 0, 0, width, height)
        } else {
            val argbBitmap = copy(Bitmap.Config.ARGB_8888, true)
            argbBitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            argbBitmap.recycle()
        }

        return CaptureResult(pixels, width, height, density, methodName)
    }

    // ==================== 日志与提示 ====================

    /**
     * 记录截图不可用的日志，并弹出 Toast 提示用户。
     */
    private fun LogNotAvailable(
        context: Context,
        reason: String,
    ): Nothing? {
        Log.w(TAG, "[Screenshot] not available: $reason")

        @Suppress("DEPRECATION")
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            android.widget.Toast.makeText(
                context.applicationContext,
                "截图暂不可用：${reason.take(60)}",
                android.widget.Toast.LENGTH_SHORT,
            ).show()
        }

        return null
    }
}

// ==================== 像素数组转换扩展 ====================

/**
 * 将 ByteBuffer 中的像素数据转换为 CaptureResult。
 */
@OptIn(ExperimentalUnsignedTypes::class)
private fun ByteBuffer.toIntCaptureResult(
    width: Int,
    height: Int,
    density: Int,
    methodName: String,
): CaptureResult {
    val size = width * height
    val result = IntArray(size)

    val position = position()
    try {
        for (i in 0 until size) {
            val base = i * 4
            val a = get(base + position).toInt() and 0xFF
            val r = get(base + 1 + position).toInt() and 0xFF
            val g = get(base + 2 + position).toInt() and 0xFF
            val b = get(base + 3 + position).toInt() and 0xFF
            result[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }
    } finally {
        position(position)
    }

    return CaptureResult(
        pixels = result,
        width = width,
        height = height,
        density = density,
        methodName = methodName,
    )
}

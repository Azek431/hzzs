// 火崽崽助手（HZZS）屏幕截图采集器。
//
// 职责：
// - 通过 AccessibilityService.takeScreenshot() 获取当前屏幕像素
// - 返回 ARGB 像素数组（IntArray），供后续视觉识别模块使用
// - 在所有前置条件不满足时优雅降级（日志 + Toast），不崩溃
//
// API 兼容性：
// - takeScreenshot() 需要 API 31（Android 12）及以上
// - 需要无障碍服务处于启用/绑定状态（通过 AutoOperationService.proxyTakeScreenshot 调用）
// - API < 31 时直接返回 null，不影响其他功能
//
// 线程模型：
// - takeScreenshot() 是异步操作，通过 TakeScreenshotCallback 返回结果
// - 本类内部封装了同步等待（CountDownLatch），调用方可以同步获取
// - 阻塞时间最长 TIMEOUT_MS 毫秒
//
// 设计原因：
// - 与 VisionAnalysisBridge 配合使用：前者负责采集，后者负责分析
// - 不引入额外权限（MediaProjection 等），仅依赖已有的无障碍服务
// - 保持轻量：仅返回像素数组，不做压缩/存储/分享
// - 通过 AutoOperationService.proxyTakeScreenshot 静态代理，避免强依赖服务实例

package top.azek431.hzzs.data.vision

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import top.azek431.hzzs.core.data.native.NativeLibraryLoader
import top.azek431.hzzs.service.AutoOperationService
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 屏幕截图采集器。
 *
 * 通过 AutoOperationService.proxyTakeScreenshot() 获取当前屏幕像素。
 * 所有前置条件检查失败时返回 null，不抛异常。
 *
 * 调用示例：
 * ```
 * val pixels = ScreenshotCapture.takeScreenshot(context)
 * if (pixels != null) {
 *     val (pixelArray, w, h) = pixels
 *     val result = VisionAnalysisBridge.scanGreenBottle(pixelArray, w, h, playerBounds)
 * }
 * ```
 */
object ScreenshotCapture {

    /**
     * 截图结果：包含像素数组和屏幕尺寸。
     *
     * @property pixels ARGB 像素数组（每元素 0xAARRGGBB），长度 = width * height
     * @property width 屏幕宽度（像素）
     * @property height 屏幕高度（像素）
     * @property density 屏幕密度（dpi），可用于 DPI 归一化
     */
    data class CaptureResult(
        val pixels: IntArray,
        val width: Int,
        val height: Int,
        val density: Int,
    )

    /** takeScreenshot() 超时时间（毫秒）— 5 秒 */
    private const val TIMEOUT_MS = 5000L

    /**
     * 尝试截取当前屏幕。
     *
     * 前置条件检查（全部通过才会真正截图）：
     * 1. API >= 31（Android 12）— takeScreenshot 可用
     * 2. NativeLibraryLoader.isAvailable — 确保下游分析引擎可用
     * 3. AutoOperationService 已连接 — 无障碍服务处于启用状态
     *
     * 任一条件不满足时：
     * - 记录 WARN 日志说明原因
     * - 在主线程发送 Toast 提示用户
     * - 返回 null
     *
     * 注意：此方法内部会阻塞调用线程（最长 TIMEOUT_MS 毫秒），
     * 应在后台线程调用，不要在主线程直接调用。
     *
     * @param context 上下文（用于获取系统服务和 Toast 展示）
     * @return 截图结果，或 null（前置条件不满足时）
     */
    fun takeScreenshot(context: Context): CaptureResult? {
        val appContext = context.applicationContext

        // 检查 API 级别：takeScreenshot() 需要 API 31+
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            LogNotAvailable(appContext, "API ${Build.VERSION.SDK_INT} < 31 (takeScreenshot requires Android 12+)")
            return null
        }

        // 检查原生库是否可用：如果没有 C++ 引擎，截图也没有意义
        if (!NativeLibraryLoader.isAvailable) {
            LogNotAvailable(appContext, "Native library not loaded — screenshot would have no consumer")
            return null
        }

        // 检查无障碍服务是否已连接
        if (AutoOperationService.getInstance() == null) {
            LogNotAvailable(appContext, "AutoOperationService not connected — enable it in system settings")
            return null
        }

        // 执行截图（通过无障碍服务静态代理）
        val latch = CountDownLatch(1)
        var capturedResult: CaptureResult? = null
        var captureError: String? = null

        try {
            val success = AutoOperationService.proxyTakeScreenshot(
                /* displayId = */ 0,  // 主显示器
                /* executor = */ appContext.mainExecutor,
                object : android.accessibilityservice.AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(screenShotResult: android.accessibilityservice.AccessibilityService.ScreenshotResult) {
                        try {
                            capturedResult = convertScreenshotResult(screenShotResult, appContext)
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
                LogNotAvailable(appContext, "proxyTakeScreenshot returned false — service unavailable")
                return null
            }
        } catch (e: Exception) {
            captureError = "takeScreenshot invocation threw: ${e.message}"
            latch.countDown()
        }

        // 同步等待截图完成
        latch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)

        // 检查结果
        if (capturedResult != null) {
            android.util.Log.i("HZZS-Screenshot", "[Screenshot] captured ${capturedResult.width}x${capturedResult.height}, ${capturedResult.pixels.size} pixels.")
            return capturedResult
        }

        val errorMsg = captureError ?: "Screenshot completed but returned null result"
        LogNotAvailable(appContext, errorMsg)
        return null
    }

    // ==================== 截图结果转换 ====================

    /**
     * 将 ScreenshotResult 转换为 CaptureResult。
     *
     * 策略：
     * 1. 优先使用 getHardwareBuffer() → Bitmap → IntArray（API 33+ 推荐）
     * 2. 回退到 getImageBuffer() → ByteBuffer → IntArray（API 31-32）
     * 3. 两者都失败时抛出异常
     *
     * 使用反射调用，因为 stubs 中属性名可能不完整。
     */
    private fun convertScreenshotResult(
        result: android.accessibilityservice.AccessibilityService.ScreenshotResult,
        context: Context,
    ): CaptureResult {
        val resultClass = result.javaClass

        // 获取截图宽度和高度
        val width = (resultClass.getDeclaredMethod("getWidth").invoke(result) as Number).toInt()
        val height = (resultClass.getDeclaredMethod("getHeight").invoke(result) as Number).toInt()
        val density = context.resources.displayMetrics.densityDpi

        // 策略 1：HardwareBuffer 路径（API 33+）
        try {
            val getHardwareBuffer = resultClass.getMethod("getHardwareBuffer")
            val hardwareBuffer = getHardwareBuffer.invoke(result)
            if (hardwareBuffer != null) {
                return convertViaHardwareBuffer(hardwareBuffer as android.hardware.HardwareBuffer, width, height, density)
            }
        } catch (e: Exception) {
            android.util.Log.d("HZZS-Screenshot", "[Screenshot] HardwareBuffer path not available: ${e.message}")
        }

        // 策略 2：ByteBuffer imageBuffer 路径（API 31-32）
        try {
            val getImageBuffer = resultClass.getMethod("getImageBuffer")
            val imageBuffer = getImageBuffer.invoke(result) as? ByteBuffer
            if (imageBuffer != null && imageBuffer.hasArray()) {
                return imageBuffer.toIntCaptureResult(width, height, density)
            }
        } catch (e: Exception) {
            android.util.Log.w("HZZS-Screenshot", "[Screenshot] imageBuffer path failed: ${e.message}")
        }

        throw IllegalStateException("ScreenshotResult conversion failed: neither HardwareBuffer nor imageBuffer available")
    }

    /**
     * 通过 HardwareBuffer 转换为 CaptureResult。
     *
     * 使用 Bitmap.createFromHardwareBuffer（API 33+）将 HardwareBuffer
     * 直接转为 Bitmap，再从中提取像素数组。
     */
    @Suppress("UNCHECKED_CAST")
    private fun convertViaHardwareBuffer(
        hb: android.hardware.HardwareBuffer,
        width: Int,
        height: Int,
        density: Int,
    ): CaptureResult {
        return try {
            // 尝试 Bitmap.createFromHardwareBuffer（API 33+）
            val createMethod = Bitmap::class.java.getDeclaredMethod(
                "createFromHardwareBuffer",
                android.hardware.HardwareBuffer::class.java
            )
            val bitmap = createMethod.invoke(null, hb) as Bitmap
            bitmap.toIntArray(width, height, density)
        } catch (e: Exception) {
            android.util.Log.w("HZZS-Screenshot", "[Screenshot] createFromHardwareBuffer failed, trying Bitmap.create: ${e.message}")
            // 回退：尝试 Bitmap.create(hardwareBuffer)
            try {
                val createMethod2 = Bitmap::class.java.getDeclaredMethod(
                    "create",
                    android.hardware.HardwareBuffer::class.java,
                    Int::class.java,
                    Int::class.java
                )
                val bitmap = createMethod2.invoke(null, hb, width, height) as Bitmap
                bitmap.toIntArray(width, height, density)
            } catch (e2: Exception) {
                throw IllegalStateException("HardwareBuffer to Bitmap conversion failed: ${e2.message}", e2)
            }
        }
    }

    /**
     * 将 Bitmap 转换为 CaptureResult。
     *
     * 使用 getPixels() 提取 ARGB 像素数组。
     */
    private fun Bitmap.toIntArray(
        width: Int,
        height: Int,
        density: Int,
    ): CaptureResult {
        val pixels = IntArray(width * height)
        // Bitmap.getPixels 要求 Bitmap config 为 ARGB_8888
        // takeScreenshot 返回的通常是 ARGB_8888，但如果不是则需要转换
        val config = config
        if (config == Bitmap.Config.ARGB_8888) {
            getPixels(pixels, 0, width, 0, 0, width, height)
        } else {
            // 如果不是 ARGB_8888，先转换为 ARGB_8888
            val argbBitmap = copy(Bitmap.Config.ARGB_8888, true)
            argbBitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            argbBitmap.recycle()
        }

        return CaptureResult(pixels, width, height, density)
    }

    // ==================== 日志与提示 ====================

    /**
     * 记录截图不可用的日志，并弹出 Toast 提示用户。
     *
     * @param context 上下文
     * @param reason 不可用的原因说明
     * @return 始终返回 null
     */
    private fun LogNotAvailable(
        context: Context,
        reason: String,
    ): Nothing? {
        android.util.Log.w("HZZS-Screenshot", "[Screenshot] not available: $reason")

        // 在主线程弹出 Toast，告知用户当前无法截图
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
 *
 * ByteBuffer 中的数据按 ARGB_8888 格式排列（每像素 4 字节）。
 * 由于 Android 使用 little-endian 字节序，ByteBuffer 中的字节顺序为 ABGR，
 * 需要正确打包为 IntArray 的 0xAARRGGBB 格式。
 */
@OptIn(ExperimentalUnsignedTypes::class)
private fun ByteBuffer.toIntCaptureResult(
    width: Int,
    height: Int,
    density: Int,
): ScreenshotCapture.CaptureResult {
    val size = width * height
    val result = IntArray(size)

    // 逐像素读取：ByteBuffer 中每像素 4 字节（ABGR 顺序，little-endian）
    for (i in 0 until size) {
        val base = i * 4
        val a = get(base).toInt() and 0xFF
        val r = get(base + 1).toInt() and 0xFF
        val g = get(base + 2).toInt() and 0xFF
        val b = get(base + 3).toInt() and 0xFF
        result[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    return ScreenshotCapture.CaptureResult(
        pixels = result,
        width = width,
        height = height,
        density = density,
    )
}

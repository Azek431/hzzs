package top.azek431.hzzs.data.vision

import android.content.Context
import android.graphics.Bitmap
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import top.azek431.hzzs.core.model.DeveloperConfig
import top.azek431.hzzs.service.capture.CapturedFrame
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 调试帧元数据：只暴露文件名、大小与修改时间，不包含图像字节。
 */
data class DebugFrameInfo(
    val name: String,
    val sizeBytes: Long,
    val modifiedAtEpochMs: Long,
)

/**
 * 可选调试帧采样器：按低频上限将截图副本写入应用私有目录。
 *
 * 职责：
 * - 在开发者开关开启时，对 [CapturedFrame] 做间隔门控后异步落盘 JPEG；
 * - 限制文件数量与写入互斥，避免撑爆存储或并发写坏目录。
 *
 * 所有权与生命周期：
 * - [CapturedFrame] 仍归帧循环所有，[offer] 返回后循环可立即 close；
 * - 仅在间隔门控通过后 [copyOf] 像素，异步 IO 使用副本而非帧缓冲引用。
 *
 * 安全：文件仅存私有存储，不自动上传；默认关闭（依赖 [DeveloperConfig]）。
 * 线程：[offer] 可在分析线程调用（快速路径）；写盘在 IO 协程 + [ioMutex]。
 */
@Singleton
class DebugFrameRecorder @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val directory = File(context.filesDir, DIRECTORY_NAME)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lastAcceptedNanos = AtomicLong(Long.MIN_VALUE)
    private val ioMutex = Mutex()

    /**
     * 尝试提交一帧调试样本。
     *
     * 快速返回：开发者开关关闭、间隔未到、或 CAS 失败时不复制像素。
     * 成功接受后复制像素并异步 [writeFrame]。
     */
    fun offer(frame: CapturedFrame, config: DeveloperConfig) {
        if (!config.enabled || !config.saveDebugFrames) return
        val now = frame.elapsedRealtimeNanos
        while (true) {
            val previous = lastAcceptedNanos.get()
            if (previous != Long.MIN_VALUE && now - previous < MIN_INTERVAL_NANOS) return
            if (lastAcceptedNanos.compareAndSet(previous, now)) break
        }

        val width = frame.width
        val height = frame.height
        // 必须在帧 close 前复制；异步任务不得持有 frame 引用。
        val pixels = frame.pixels.copyOf()
        val sequence = frame.sequence
        scope.launch {
            ioMutex.withLock {
                runCatching { writeFrame(sequence, width, height, pixels) }
            }
        }
    }

    /** 列出私有目录中的 JPEG 调试帧元数据（新→旧），不含像素。 */
    suspend fun list(): List<DebugFrameInfo> = ioMutex.withLock {
        directory.listFiles()
            .orEmpty()
            .asSequence()
            .filter { it.isFile && it.extension.equals("jpg", ignoreCase = true) }
            .sortedByDescending(File::lastModified)
            .map { DebugFrameInfo(it.name, it.length(), it.lastModified()) }
            .toList()
    }

    /** 删除已落盘调试帧文件，返回成功删除数量。 */
    suspend fun clear(): Int = ioMutex.withLock {
        directory.listFiles().orEmpty().count { it.isFile && it.delete() }
    }

    /**
     * 将 ARGB 像素编码为 JPEG 并裁剪目录至 [MAX_FILES]。
     * 尺寸与数组长度不匹配时静默跳过（fail-closed）。
     */
    private fun writeFrame(sequence: Long, width: Int, height: Int, pixels: IntArray) {
        if (width <= 0 || height <= 0 || pixels.size.toLong() != width.toLong() * height.toLong()) return
        check(directory.exists() || directory.mkdirs()) { "无法创建调试帧目录" }
        val target = File(directory, "frame_${sequence}_${width}x${height}.jpg")
        val bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        try {
            target.outputStream().buffered().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)) {
                    "调试帧编码失败"
                }
            }
        } finally {
            bitmap.recycle()
        }
        directory.listFiles()
            .orEmpty()
            .filter { it.isFile }
            .sortedByDescending(File::lastModified)
            .drop(MAX_FILES)
            .forEach(File::delete)
    }

    private companion object {
        const val DIRECTORY_NAME = "debug-frames"
        const val MAX_FILES = 20
        const val JPEG_QUALITY = 88
        const val MIN_INTERVAL_NANOS = 5_000_000_000L
    }
}

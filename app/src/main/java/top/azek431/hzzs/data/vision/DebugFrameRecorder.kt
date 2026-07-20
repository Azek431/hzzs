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

/** Metadata intentionally exposed to diagnostics without exposing image bytes. */
data class DebugFrameInfo(
    val name: String,
    val sizeBytes: Long,
    val modifiedAtEpochMs: Long,
)

/**
 * Persists a bounded, low-frequency sample of captured frames for opt-in debugging.
 *
 * Ownership rules:
 * - [CapturedFrame] remains owned by the runtime loop and may close immediately after [offer].
 * - The recorder copies pixels only after the interval gate succeeds.
 * - Files stay in private app storage and are never uploaded automatically.
 */
@Singleton
class DebugFrameRecorder @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val directory = File(context.filesDir, DIRECTORY_NAME)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lastAcceptedNanos = AtomicLong(Long.MIN_VALUE)
    private val ioMutex = Mutex()

    /** Returns quickly unless a new debug sample is due. */
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
        val pixels = frame.pixels.copyOf()
        val sequence = frame.sequence
        scope.launch {
            ioMutex.withLock {
                runCatching { writeFrame(sequence, width, height, pixels) }
            }
        }
    }

    suspend fun list(): List<DebugFrameInfo> = ioMutex.withLock {
        directory.listFiles()
            .orEmpty()
            .asSequence()
            .filter { it.isFile && it.extension.equals("jpg", ignoreCase = true) }
            .sortedByDescending(File::lastModified)
            .map { DebugFrameInfo(it.name, it.length(), it.lastModified()) }
            .toList()
    }

    suspend fun clear(): Int = ioMutex.withLock {
        directory.listFiles().orEmpty().count { it.isFile && it.delete() }
    }

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

package top.azek431.hzzs.service.capture

import android.media.Image
import android.os.SystemClock
import kotlinx.coroutines.flow.StateFlow
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * A frame owns its pixel lease. Call [close] after analysis so the source can
 * reuse the buffer. Frames are immutable to consumers while leased.
 */
data class CapturedFrame(
    val sequence: Long,
    val elapsedRealtimeNanos: Long,
    val width: Int,
    val height: Int,
    val pixels: IntArray,
    private val releaseLease: (() -> Unit)? = null,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    init {
        require(sequence >= 0)
        require(width > 0 && height > 0)
        require(width.toLong() * height.toLong() == pixels.size.toLong())
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) releaseLease?.invoke()
    }
}

sealed interface CaptureState {
    data object Idle : CaptureState
    data object RequestingPermission : CaptureState
    data object Ready : CaptureState
    data class Failed(val message: String, val recoverable: Boolean) : CaptureState
}

interface FrameSource {
    val state: StateFlow<CaptureState>
    suspend fun start()
    suspend fun nextFrame(afterSequence: Long): CapturedFrame?
    suspend fun stop()
}

/**
 * Bounded pool. When every buffer is being analyzed the capture backend drops
 * the incoming frame instead of mutating a buffer that is still in use.
 */
class IntFramePool(private val capacity: Int = 3) {
    init { require(capacity >= 2) }
    private val free = ArrayDeque<IntArray>()
    private var allocated = 0
    private var activeSize = 0

    data class Lease internal constructor(
        val pixels: IntArray,
        private val releaseBlock: () -> Unit,
    ) : AutoCloseable {
        private val closed = AtomicBoolean(false)
        override fun close() { if (closed.compareAndSet(false, true)) releaseBlock() }
    }

    @Synchronized
    fun tryAcquire(size: Int): Lease? {
        require(size > 0)
        if (activeSize != size) {
            // A resolution change invalidates only currently-free buffers.
            free.clear()
            activeSize = size
            allocated = 0
        }
        val pixels = when {
            free.isNotEmpty() -> free.removeFirst()
            allocated < capacity -> IntArray(size).also { allocated++ }
            else -> return null
        }
        return Lease(pixels) { release(pixels, size) }
    }

    @Synchronized
    private fun release(pixels: IntArray, size: Int) {
        if (size == activeSize && pixels.size == activeSize && free.size < capacity) {
            free.addLast(pixels)
        }
    }
}

/**
 * Copies RGBA_8888 Image.Plane row by row. It never assumes trailing padding
 * exists after the last valid pixel, avoiding copyPixelsFromBuffer underflow.
 */
object PlaneRgbaReader {
    fun copy(image: Image, output: IntArray) {
        require(image.width > 0 && image.height > 0)
        require(output.size >= image.width * image.height)
        val plane = image.planes.firstOrNull() ?: error("Image has no planes")
        require(plane.pixelStride >= 4) { "Unsupported pixelStride=${plane.pixelStride}" }
        val buffer = plane.buffer.duplicate()
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        require(rowStride >= image.width * pixelStride)
        var out = 0
        for (y in 0 until image.height) {
            val rowStart = y.toLong() * rowStride.toLong()
            for (x in 0 until image.width) {
                val offsetLong = rowStart + x.toLong() * pixelStride.toLong()
                require(offsetLong + 3 < buffer.limit()) {
                    "Plane truncated at y=$y x=$x limit=${buffer.limit()}"
                }
                val offset = offsetLong.toInt()
                val r = buffer.get(offset).toInt() and 0xFF
                val g = buffer.get(offset + 1).toInt() and 0xFF
                val b = buffer.get(offset + 2).toInt() and 0xFF
                val a = buffer.get(offset + 3).toInt() and 0xFF
                output[out++] = (a shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
    }
}

class FrameSequencer {
    private val counter = AtomicLong(0)
    fun next(): Pair<Long, Long> = counter.incrementAndGet() to SystemClock.elapsedRealtimeNanos()
}

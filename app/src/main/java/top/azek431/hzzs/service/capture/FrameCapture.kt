package top.azek431.hzzs.service.capture

import android.media.Image
import android.os.SystemClock
import kotlinx.coroutines.flow.StateFlow
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * A frame owns its pixel lease. Call [close] after analysis so the source can
 * reuse the buffer. This is intentionally not a data class: copying a leased
 * frame could return the same mutable buffer to the pool more than once.
 */
class CapturedFrame(
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
        require(width in 1..MAX_CAPTURE_FRAME_DIMENSION && height in 1..MAX_CAPTURE_FRAME_DIMENSION)
        val pixelCount = width.toLong() * height.toLong()
        require(pixelCount <= MAX_CAPTURE_FRAME_PIXELS)
        require(pixelCount == pixels.size.toLong())
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
 * Bounded, generation-aware pool. Buffers leased before a resolution change
 * can never re-enter a later generation, even when the old resolution returns.
 */
class IntFramePool(private val capacity: Int = 3) {
    init { require(capacity >= 2) }

    private data class Buffer(val pixels: IntArray, val generation: Long)

    private val free = ArrayDeque<Buffer>()
    private var allocated = 0
    private var activeSize = 0
    private var generation = 0L

    class Lease internal constructor(
        val pixels: IntArray,
        private val releaseBlock: () -> Unit,
    ) : AutoCloseable {
        private val closed = AtomicBoolean(false)
        override fun close() {
            if (closed.compareAndSet(false, true)) releaseBlock()
        }
    }

    @Synchronized
    fun tryAcquire(size: Int): Lease? {
        require(size in 1..MAX_CAPTURE_FRAME_PIXELS.toInt())
        if (activeSize != size) {
            free.clear()
            activeSize = size
            allocated = 0
            generation++
        }

        val currentGeneration = generation
        val buffer = when {
            free.isNotEmpty() -> free.removeFirst()
            allocated < capacity -> Buffer(IntArray(size), currentGeneration).also { allocated++ }
            else -> return null
        }
        return Lease(buffer.pixels) {
            release(buffer, size, currentGeneration)
        }
    }

    @Synchronized
    private fun release(buffer: Buffer, size: Int, leaseGeneration: Long) {
        if (
            leaseGeneration == generation &&
            buffer.generation == generation &&
            size == activeSize &&
            buffer.pixels.size == activeSize &&
            free.size < capacity
        ) {
            free.addLast(buffer)
        }
    }
}

/**
 * Copies RGBA_8888 Image.Plane row by row without assuming that the final row
 * contains trailing padding. All arithmetic is widened before bounds checks.
 */
object PlaneRgbaReader {
    private val rowScratch = ThreadLocal<ByteArray>()

    fun copy(image: Image, output: IntArray) {
        require(image.width > 0 && image.height > 0)
        val pixelCount = image.width.toLong() * image.height.toLong()
        require(pixelCount <= output.size.toLong()) { "Output buffer too small" }

        val plane = image.planes.firstOrNull() ?: error("Image has no planes")
        require(plane.pixelStride >= 4) { "Unsupported pixelStride=${plane.pixelStride}" }
        val buffer = plane.buffer.duplicate()
        val base = buffer.position().toLong()
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val rowBytesLong = image.width.toLong() * pixelStride.toLong()
        require(rowStride.toLong() >= rowBytesLong)
        require(rowBytesLong in 1..Int.MAX_VALUE.toLong()) { "Plane row size overflow" }
        val rowBytes = rowBytesLong.toInt()
        var scratch = rowScratch.get()
        if (scratch == null || scratch.size < rowBytes) {
            scratch = ByteArray(rowBytes)
            rowScratch.set(scratch)
        }

        var out = 0
        for (y in 0 until image.height) {
            val rowStartLong = base + y.toLong() * rowStride.toLong()
            require(rowStartLong >= 0 && rowStartLong + rowBytesLong <= buffer.limit().toLong()) {
                "Plane truncated at y=$y limit=${buffer.limit()}"
            }
            require(rowStartLong <= Int.MAX_VALUE.toLong()) { "Plane offset overflow" }
            buffer.position(rowStartLong.toInt())
            buffer.get(scratch, 0, rowBytes)
            var offset = 0
            repeat(image.width) {
                val r = scratch[offset].toInt() and 0xFF
                val g = scratch[offset + 1].toInt() and 0xFF
                val b = scratch[offset + 2].toInt() and 0xFF
                val a = scratch[offset + 3].toInt() and 0xFF
                output[out++] = (a shl 24) or (r shl 16) or (g shl 8) or b
                offset += pixelStride
            }
        }
    }
}

class FrameSequencer(
    private val clockNanos: () -> Long = SystemClock::elapsedRealtimeNanos,
) {
    private val counter = AtomicLong(0)

    fun next(): Pair<Long, Long> = counter.incrementAndGet() to clockNanos()
}

private const val MAX_CAPTURE_FRAME_DIMENSION = 4_096
private const val MAX_CAPTURE_FRAME_PIXELS = 8_388_608L

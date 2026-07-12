package top.azek431.hzzs.runtime.capture

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Root 永不参与 AUTO，也不会在普通状态刷新时偷偷触发 su 授权弹窗。 */
object RootFrameSource {
    @Volatile private var cachedAvailable: Boolean? = null
    private val ioExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "hzzs-root-capture").apply { isDaemon = true }
    }

    fun isAvailable(): Boolean = cachedAvailable == true

    fun probeAvailability(): Boolean {
        val available = runCatching {
            val process = ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start()
            val finished = process.waitFor(1_200L, TimeUnit.MILLISECONDS)
            if (!finished) process.destroyForcibly()
            finished && process.exitValue() == 0
        }.getOrDefault(false)
        cachedAvailable = available
        return available
    }

    fun capture(): Bitmap? = runCatching {
        val process = ProcessBuilder("su", "-c", "screencap -p").redirectErrorStream(false).start()
        val reader = ioExecutor.submit<ByteArray> { process.inputStream.use { it.readBytes() } }
        val finished = process.waitFor(2_500L, TimeUnit.MILLISECONDS)
        if (!finished) {
            process.destroyForcibly()
            reader.cancel(true)
            cachedAvailable = false
            return null
        }
        val bytes = reader.get(500L, TimeUnit.MILLISECONDS)
        if (process.exitValue() != 0 || bytes.isEmpty()) {
            cachedAvailable = false
            return null
        }
        cachedAvailable = true
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }.getOrElse {
        cachedAvailable = false
        null
    }
}

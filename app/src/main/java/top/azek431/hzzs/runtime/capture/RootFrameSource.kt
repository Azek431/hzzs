package top.azek431.hzzs.runtime.capture

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import java.lang.IllegalThreadStateException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Root 永不参与 AUTO，也不会在普通状态刷新时偷偷触发 su 授权弹窗。 */
object RootFrameSource {
    @Volatile
    private var cachedAvailable: Boolean? = null

    private val ioExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "hzzs-root-capture").apply { isDaemon = true }
    }

    fun isAvailable(): Boolean = cachedAvailable == true

    fun probeAvailability(): Boolean {
        val available = runCatching {
            val process = ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start()
            val finished = waitForCompat(process, 1_200L)
            if (!finished) process.destroy()
            finished && process.exitValue() == 0
        }.getOrDefault(false)
        cachedAvailable = available
        return available
    }

    fun capture(): Bitmap? = runCatching {
        val process = ProcessBuilder("su", "-c", "screencap -p")
            .redirectErrorStream(false)
            .start()
        val reader = ioExecutor.submit { process.inputStream.use { it.readBytes() } }
        val finished = waitForCompat(process, 2_500L)
        if (!finished) {
            runCatching { process.inputStream.close() }
            runCatching { process.errorStream.close() }
            process.destroy()
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

    private fun waitForCompat(process: Process, timeoutMs: Long): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs.coerceAtLeast(0L)
        while (SystemClock.uptimeMillis() < deadline) {
            try {
                process.exitValue()
                return true
            } catch (_: IllegalThreadStateException) {
                SystemClock.sleep(20L)
            }
        }
        return try {
            process.exitValue()
            true
        } catch (_: IllegalThreadStateException) {
            false
        }
    }
}

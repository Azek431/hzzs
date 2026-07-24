package top.azek431.hzzs.service.automation

import android.content.pm.PackageManager
import android.os.SystemClock
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku

/**
 * Shell 进程辅助（手势路径专用；与截图 FrameSource 类边界分离，实现模式对齐）。
 *
 * 安全：stdout/stderr 限长、超时 destroy、失败返回 null。
 */
internal object ShellProcessSupport {
    fun isShizukuAuthorized(): Boolean = runCatching {
        Shizuku.pingBinder() &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    /**
     * Shizuku 13+ 将 `newProcess` 标为 private；反射调用，失败 null。
     */
    fun openShizukuProcess(command: Array<String>): Process? = runCatching {
        val method = Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java,
        )
        method.isAccessible = true
        method.invoke(null, command, null, null) as Process
    }.getOrNull()

    fun openRootProcess(shellCommand: String): Process? = runCatching {
        ProcessBuilder("su", "-c", shellCommand).redirectErrorStream(false).start()
    }.getOrNull()

    suspend fun runProcess(
        process: Process,
        maxStdoutBytes: Int,
        timeoutMs: Long,
    ): String? = withContext(Dispatchers.IO) {
        try {
            coroutineScope {
                val stdout = async(Dispatchers.IO) {
                    runCatching { readLimited(process.inputStream, maxStdoutBytes) }
                        .onFailure { process.destroyCompat() }
                        .getOrNull()
                }
                val stderr = async(Dispatchers.IO) {
                    runCatching { readLimited(process.errorStream, MAX_STDERR_BYTES) }
                        .onFailure { process.destroyCompat() }
                        .getOrNull()
                }
                val exited = waitForExit(process, timeoutMs)
                if (!exited) process.destroyCompat()
                val streams = withTimeoutOrNull(1_000L) { stdout.await() to stderr.await() }
                val exitCode = if (exited) runCatching { process.exitValue() }.getOrNull() else null
                if (exitCode != 0 || streams == null) null else streams.first
            }
        } finally {
            process.destroyCompat()
        }
    }

    suspend fun runShizuku(
        command: Array<String>,
        maxStdoutBytes: Int,
        timeoutMs: Long,
    ): String? {
        if (!isShizukuAuthorized()) return null
        val process = openShizukuProcess(command) ?: return null
        return runProcess(process, maxStdoutBytes, timeoutMs)
    }

    suspend fun runRoot(
        shellCommand: String,
        maxStdoutBytes: Int,
        timeoutMs: Long,
    ): String? {
        val process = openRootProcess(shellCommand) ?: return null
        return runProcess(process, maxStdoutBytes, timeoutMs)
    }

    /**
     * 执行命令并要求 exit 0；stdout 可丢弃（input 类命令）。
     * @return true 表示成功退出
     */
    suspend fun runShizukuOk(command: Array<String>, timeoutMs: Long): Boolean {
        if (!isShizukuAuthorized()) return false
        val process = openShizukuProcess(command) ?: return false
        return runProcessOk(process, timeoutMs)
    }

    suspend fun runRootOk(shellCommand: String, timeoutMs: Long): Boolean {
        val process = openRootProcess(shellCommand) ?: return false
        return runProcessOk(process, timeoutMs)
    }

    private suspend fun runProcessOk(process: Process, timeoutMs: Long): Boolean =
        withContext(Dispatchers.IO) {
            try {
                coroutineScope {
                    val stdout = async(Dispatchers.IO) {
                        runCatching { readLimited(process.inputStream, 8 * 1024) }
                            .onFailure { process.destroyCompat() }
                            .getOrNull()
                    }
                    val stderr = async(Dispatchers.IO) {
                        runCatching { readLimited(process.errorStream, MAX_STDERR_BYTES) }
                            .onFailure { process.destroyCompat() }
                            .getOrNull()
                    }
                    val exited = waitForExit(process, timeoutMs)
                    if (!exited) process.destroyCompat()
                    withTimeoutOrNull(500L) {
                        stdout.await()
                        stderr.await()
                    }
                    exited && runCatching { process.exitValue() }.getOrNull() == 0
                }
            } finally {
                process.destroyCompat()
            }
        }

    private fun readLimited(input: InputStream, maxBytes: Int): String {
        input.use { stream ->
            val output = ByteArrayOutputStream(minOf(maxBytes, 64 * 1024))
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                if (output.size().toLong() + count.toLong() > maxBytes.toLong()) {
                    // 截断后仍返回已读内容，供 dumpsys 解析尝试
                    break
                }
                output.write(buffer, 0, count)
            }
            return output.toString(Charsets.UTF_8.name())
        }
    }

    private fun waitForExit(process: Process, timeoutMs: Long): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            val exited = runCatching { process.waitFor(50L, TimeUnit.MILLISECONDS) }.getOrDefault(false)
            if (exited) return true
        }
        return runCatching { process.waitFor(1L, TimeUnit.MILLISECONDS) }.getOrDefault(false)
    }

    private fun Process.destroyCompat() {
        runCatching { destroyForcibly() }
        runCatching { destroy() }
    }

    private const val MAX_STDERR_BYTES = 64 * 1024
}

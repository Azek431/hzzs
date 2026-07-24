package top.azek431.hzzs.platform.compat

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import java.io.OutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import top.azek431.hzzs.service.automation.HzzsAccessibilityService

/**
 * 系统级能力查询与设置页跳转（悬浮窗 / 无障碍 / 修改系统设置 / 指针位置）。
 *
 * 职责：集中 API 边界，避免 feature 散落 `Settings` / Intent / Shell 构造。
 * 不变量：
 * - **不**静默开启任何权限；AUTO 截图路径不经此文件升权。
 * - 指针位置：优先 [WRITE_SETTINGS]；仅当用户**已**授权 Shizuku 或设备可 `su` 时才走升权写入，
 *   **不**在此弹 Shizuku 授权、不静默 root 弹窗之外的额外 UI。
 * - 写入成功以**回读** [isPointerLocationEnabled] 为准（exit0 / putInt true 仍可能被 OEM 忽略）。
 */
object SystemCapabilityAccess {
    /** 是否已授予「显示在其他应用上层」。 */
    fun canDrawOverlays(context: Context): Boolean = Settings.canDrawOverlays(context)

    /**
     * 打开本应用的悬浮窗权限页；失败时回退到通用应用详情。
     * 须在 Activity / 带 NEW_TASK 的 Context 上调用。
     */
    fun openOverlayPermissionSettings(context: Context) {
        val packageUri = Uri.parse("package:${context.packageName}")
        val overlayIntent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            packageUri,
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val launched = runCatching { context.startActivity(overlayIntent) }.isSuccess
        if (!launched) {
            openAppDetailsSettings(context)
        }
    }

    /** 无障碍服务进程是否已连接（比 Secure 设置字符串更准确）。 */
    fun isAccessibilityServiceConnected(): Boolean = HzzsAccessibilityService.isConnected()

    /** 打开系统无障碍设置列表。 */
    fun openAccessibilitySettings(context: Context) {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val launched = runCatching { context.startActivity(intent) }.isSuccess
        if (!launched) {
            openAppDetailsSettings(context)
        }
    }

    /** 是否已授予「修改系统设置」（[WRITE_SETTINGS]）。 */
    fun canWriteSystemSettings(context: Context): Boolean = Settings.System.canWrite(context)

    /**
     * 打开本应用「允许修改系统设置」页；失败时回退到应用详情。
     * 用于指针位置等 [Settings.System] 写入。
     */
    fun openManageWriteSettings(context: Context) {
        val packageUri = Uri.parse("package:${context.packageName}")
        val intent = Intent(
            Settings.ACTION_MANAGE_WRITE_SETTINGS,
            packageUri,
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val launched = runCatching { context.startActivity(intent) }.isSuccess
        if (!launched) {
            openAppDetailsSettings(context)
        }
    }

    /**
     * 系统开发者选项「指针位置」是否开启。
     * 键名 [POINTER_LOCATION_KEY] 为隐藏 API（SDK 无公开常量），读失败视为关闭（fail-soft）。
     */
    fun isPointerLocationEnabled(context: Context): Boolean =
        runCatching {
            Settings.System.getInt(
                context.contentResolver,
                POINTER_LOCATION_KEY,
                0,
            ) == 1
        }.getOrDefault(false)

    /** 当前是否可用已授权的 Shizuku（不请求权限）。 */
    fun isShizukuAuthorized(): Boolean = runCatching {
        Shizuku.pingBinder() &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    /**
     * 同步写入：仅走 [WRITE_SETTINGS]，并以回读校验。
     * 升权路径请用 [setPointerLocationEnabledBestEffort]。
     */
    fun setPointerLocationEnabled(context: Context, enabled: Boolean): Boolean {
        if (!canWriteSystemSettings(context)) return false
        val putOk = runCatching {
            Settings.System.putInt(
                context.contentResolver,
                POINTER_LOCATION_KEY,
                if (enabled) 1 else 0,
            )
        }.getOrDefault(false)
        return putOk && isPointerLocationEnabled(context) == enabled
    }

    /**
     * 写入系统「指针位置」，按优先级尝试：
     * 1. 已授予 [WRITE_SETTINGS]
     * 2. 已授权 Shizuku → `settings put system pointer_location`
     * 3. Root `su -c settings put …`（不弹额外授权 UI，失败即返回）
     *
     * 每条路径成功后均回读确认；不请求 Shizuku 权限、不在 AUTO 路径使用。
     */
    suspend fun setPointerLocationEnabledBestEffort(
        context: Context,
        enabled: Boolean,
    ): PointerLocationWriteResult = withContext(Dispatchers.IO) {
        val value = if (enabled) "1" else "0"
        val appContext = context.applicationContext

        if (canWriteSystemSettings(appContext)) {
            val putOk = runCatching {
                Settings.System.putInt(
                    appContext.contentResolver,
                    POINTER_LOCATION_KEY,
                    if (enabled) 1 else 0,
                )
            }.getOrDefault(false)
            if (putOk && isPointerLocationEnabled(appContext) == enabled) {
                return@withContext PointerLocationWriteResult.Success(
                    PointerLocationWritePath.WRITE_SETTINGS,
                )
            }
        }

        if (isShizukuAuthorized()) {
            if (runSettingsPutViaShizuku(value) &&
                isPointerLocationEnabled(appContext) == enabled
            ) {
                return@withContext PointerLocationWriteResult.Success(
                    PointerLocationWritePath.SHIZUKU,
                )
            }
        }

        if (runSettingsPutViaRoot(value) &&
            isPointerLocationEnabled(appContext) == enabled
        ) {
            return@withContext PointerLocationWriteResult.Success(PointerLocationWritePath.ROOT)
        }

        PointerLocationWriteResult.Failed(
            canWriteSettings = canWriteSystemSettings(appContext),
            shizukuAuthorized = isShizukuAuthorized(),
            observedEnabled = isPointerLocationEnabled(appContext),
        )
    }

    private fun runSettingsPutViaShizuku(value: String): Boolean {
        val process = openShizukuProcess(
            arrayOf("settings", "put", "system", POINTER_LOCATION_KEY, value),
        ) ?: return false
        return waitExitZero(process, TIMEOUT_MS)
    }

    private fun runSettingsPutViaRoot(value: String): Boolean {
        // 键名与取值均为固定白名单字面量，不拼接用户输入。
        val process = runCatching {
            ProcessBuilder(
                "su",
                "-c",
                "settings put system $POINTER_LOCATION_KEY $value",
            ).redirectErrorStream(false).start()
        }.getOrNull() ?: return false
        return waitExitZero(process, TIMEOUT_MS)
    }

    /**
     * Shizuku 13+ 将 `newProcess` 标为 private；反射调用，失败 null。
     * 与手势/截图路径一致。
     */
    private fun openShizukuProcess(command: Array<String>): Process? = runCatching {
        val method = Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java,
        )
        method.isAccessible = true
        method.invoke(null, command, null, null) as Process
    }.getOrNull()

    /**
     * 等待进程结束；**不用** [Process.waitFor] 的超时重载（API 26+），保证 API 24 可用。
     * 排空 stdout/stderr 避免管道阻塞。
     */
    private fun waitExitZero(process: Process, timeoutMs: Long): Boolean {
        return try {
            val drain = Thread {
                runCatching { process.inputStream.copyTo(NullOutputStream) }
                runCatching { process.errorStream.copyTo(NullOutputStream) }
            }.also {
                it.isDaemon = true
                it.start()
            }
            val exited = waitUntilExited(process, timeoutMs)
            if (!exited) {
                runCatching { process.destroyForcibly() }
                runCatching { process.destroy() }
            }
            runCatching { drain.join(200L) }
            exited && runCatching { process.exitValue() }.getOrNull() == 0
        } finally {
            runCatching { process.destroyForcibly() }
            runCatching { process.destroy() }
        }
    }

    private fun waitUntilExited(process: Process, timeoutMs: Long): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (hasExited(process)) return true
            runCatching { Thread.sleep(50L) }
        }
        return hasExited(process)
    }

    private fun hasExited(process: Process): Boolean =
        runCatching {
            process.exitValue()
            true
        }.getOrDefault(false)

    private fun openAppDetailsSettings(context: Context) {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    private const val TIMEOUT_MS = 4_000L

    /**
     * 开发者选项「指针位置」的 [Settings.System] 键。
     * AOSP 中为 `Settings.System.POINTER_LOCATION`，公开 SDK 不暴露该常量。
     */
    private const val POINTER_LOCATION_KEY = "pointer_location"

    private object NullOutputStream : OutputStream() {
        override fun write(b: Int) = Unit
        override fun write(b: ByteArray, off: Int, len: Int) = Unit
    }
}

/** 指针位置写入实际采用的通道。 */
enum class PointerLocationWritePath {
    WRITE_SETTINGS,
    SHIZUKU,
    ROOT,
}

/** [SystemCapabilityAccess.setPointerLocationEnabledBestEffort] 结果。 */
sealed class PointerLocationWriteResult {
    data class Success(val path: PointerLocationWritePath) : PointerLocationWriteResult()

    data class Failed(
        val canWriteSettings: Boolean,
        val shizukuAuthorized: Boolean,
        /** 失败后回读到的当前系统状态，供 UI 对齐。 */
        val observedEnabled: Boolean = false,
    ) : PointerLocationWriteResult()
}

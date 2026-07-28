package top.azek431.hzzs.platform.compat

import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import top.azek431.hzzs.core.model.GestureBackend
import top.azek431.hzzs.service.automation.ShellProcessSupport

/**
 * Shizuku 健康状态检查结果。
 *
 * [isHealthy] 表示本次实际执行的检查均通过；[commandChecked] 区分完整检查与轻量检查。
 * [canExecute] 仅在 [commandChecked] 为 true 时有命令执行语义。
 */
data class ShizukuHealthResult(
    val isHealthy: Boolean,
    val binderAlive: Boolean,
    val serviceRunning: Boolean,
    val permissionGranted: Boolean,
    val commandChecked: Boolean,
    val canExecute: Boolean,
    val reason: String?,
)

/** 检测到 Shizuku 问题时记录的结构。 */
data class ShizukuIssueDetected(
    val backend: GestureBackend,
    val healthyBackend: GestureBackend,
    val reason: String,
    val timestamp: Long,
)

/**
 * Shizuku 健康检查器。
 *
 * 完整检查依次验证 binder、权限与受限 shell 命令；轻量检查不执行命令，供同步 UI 与诊断读取。
 * 不通过本应用 ClassLoader 猜测 Shizuku 是否安装：那只能反映 SDK 是否被编进 APK，不能反映服务应用状态。
 */
object ShizukuHealthCheck {
    suspend fun check(): ShizukuHealthResult = withContext(Dispatchers.IO) {
        val light = checkLight()
        if (!light.isHealthy) return@withContext light

        val canExecute = runCatching {
            ShellProcessSupport.runShizukuResult(arrayOf("id"), 1_000L)
        }.getOrNull()?.ok == true
        ShizukuHealthResult(
            isHealthy = canExecute,
            binderAlive = true,
            serviceRunning = true,
            permissionGranted = true,
            commandChecked = true,
            canExecute = canExecute,
            reason = if (canExecute) null else "Shizuku 命令执行失败（服务可能挂起或 shell 不可用）",
        )
    }

    /** 只检查 binder 与已授权状态；不 requestPermission、不执行 shell。 */
    fun checkLight(): ShizukuHealthResult {
        val binderAlive = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        if (!binderAlive) {
            return ShizukuHealthResult(
                isHealthy = false,
                binderAlive = false,
                serviceRunning = false,
                permissionGranted = false,
                commandChecked = false,
                canExecute = false,
                reason = "Shizuku binder 未存活（未安装或服务未运行）",
            )
        }

        val permissionGranted = runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        if (!permissionGranted) {
            return ShizukuHealthResult(
                isHealthy = false,
                binderAlive = true,
                serviceRunning = true,
                permissionGranted = false,
                commandChecked = false,
                canExecute = false,
                reason = "Shizuku 权限未授予",
            )
        }

        return ShizukuHealthResult(
            isHealthy = true,
            binderAlive = true,
            serviceRunning = true,
            permissionGranted = true,
            commandChecked = false,
            canExecute = false,
            reason = null,
        )
    }

    /** 完整检查通过且命令可执行时才可用于运行时决策。 */
    suspend fun isFullyAvailable(): Boolean = check().let { it.isHealthy && it.canExecute }
}

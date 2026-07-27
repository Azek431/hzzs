package top.azek431.hzzs.platform.compat

import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import top.azek431.hzzs.service.automation.ShellProcessSupport

/**
 * Shizuku 健康状态检查结果。
 *
 * @param isHealthy 综合健康度（所有检查都通过才为 true）
 * @param binderAlive Shizuku binder 是否存活
 * @param serviceRunning Shizuku 服务是否运行（通过 pingBinder 间接推断）
 * @param permissionGranted 本应用是否已获得 Shizuku 权限
 * @param canExecute 是否能实际执行 shell 命令（真正的可用性测试）
 * @param reason 不健康时的具体原因描述
 */
data class ShizukuHealthResult(
    val isHealthy: Boolean,
    val binderAlive: Boolean,
    val serviceRunning: Boolean,
    val permissionGranted: Boolean,
    val canExecute: Boolean,
    val reason: String?,
)

/**
 * 检测到 Shizuku 问题时记录的结构。
 *
 * @backend 出现问题的后端（SHIZUKU）
 * @healthyBackend 降级到的可用后端（如 ACCESSIBILITY）
 * @reason 问题原因
 * @timestamp 检测时间戳
 */
data class ShizukuIssueDetected(
    val backend: GestureBackend,
    val healthyBackend: GestureBackend,
    val reason: String,
    val timestamp: Long,
)

/**
 * Shizuku 健康检查器。
 *
 * 执行一系列检查，判断 Shizuku 是否真正可用，而不仅仅是配置上可用。
 * 检查顺序：
 * 1. 包是否存在
 * 2. binder 是否存活
 * 3. 权限是否授予
 * 4. 是否能实际执行命令（通过 ShellProcessSupport）
 *
 * 注意：此检查可能涉及 IPC 操作，应在 IO 协程中调用。
 */
object ShizukuHealthCheck {

    /**
     * 执行完整的 Shizuku 健康检查。
     *
     * @return ShizukuHealthResult
     */
    suspend fun check(): ShizukuHealthResult = withContext(Dispatchers.IO) {
        // 检查 1: Shizuku 包是否存在
        val hasPackage = runCatching {
            @Suppress("DEPRECATION")
            Shizuku::class.java.classLoader?.loadClass("moe.shizuku.privileged.api.Shizuku") != null
        }.getOrDefault(false)
        if (!hasPackage) {
            return@withContext ShizukuHealthResult(
                isHealthy = false,
                binderAlive = false,
                serviceRunning = false,
                permissionGranted = false,
                canExecute = false,
                reason = "Shizuku 未安装",
            )
        }

        // 检查 2: binder 是否存活
        val binderAlive = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        if (!binderAlive) {
            return@withContext ShizukuHealthResult(
                isHealthy = false,
                binderAlive = false,
                serviceRunning = false,
                permissionGranted = runCatching {
                    Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
                }.getOrDefault(false),
                canExecute = false,
                reason = "Shizuku binder 未存活（服务可能未运行）",
            )
        }

        // 检查 3: 权限是否授予
        val permissionGranted = runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        if (!permissionGranted) {
            return@withContext ShizukuHealthResult(
                isHealthy = false,
                binderAlive = true,
                serviceRunning = true,
                permissionGranted = false,
                canExecute = false,
                reason = "Shizuku 权限未授予",
            )
        }

        // 检查 4: 是否能实际执行命令（真正的可用性测试）
        val canExecuteResult = runCatching {
            ShellProcessSupport.runShizukuResult(arrayOf("id"), 1000L)
        }.getOrNull()?.ok == true
        if (!canExecuteResult) {
            return@withContext ShizukuHealthResult(
                isHealthy = false,
                binderAlive = true,
                serviceRunning = true,
                permissionGranted = true,
                canExecute = false,
                reason = "Shizuku 命令执行失败（服务可能挂起或存在其他问题）",
            )
        }

        // 所有检查通过
        ShizukuHealthResult(
            isHealthy = true,
            binderAlive = true,
            serviceRunning = true,
            permissionGranted = true,
            canExecute = true,
            reason = null,
        )
    }

    /**
     * 轻量同步检查：仅检查包、binder 存活和权限，不执行命令测试。
     * 适用于需要快速响应的场景（如诊断报告导出）。
     */
    fun checkLight(): ShizukuHealthResult {
        val hasPackage = runCatching {
            @Suppress("DEPRECATION")
            Shizuku::class.java.classLoader?.loadClass("moe.shizuku.privileged.api.Shizuku") != null
        }.getOrDefault(false)
        if (!hasPackage) {
            return ShizukuHealthResult(
                isHealthy = false,
                binderAlive = false,
                serviceRunning = false,
                permissionGranted = false,
                canExecute = false,
                reason = "Shizuku 未安装",
            )
        }

        val binderAlive = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        if (!binderAlive) {
            return ShizukuHealthResult(
                isHealthy = false,
                binderAlive = false,
                serviceRunning = false,
                permissionGranted = runCatching {
                    Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
                }.getOrDefault(false),
                canExecute = false,
                reason = "Shizuku binder 未存活",
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
                canExecute = false,
                reason = "权限未授予",
            )
        }

        // 轻量检查通过（未测试命令执行）
        return ShizukuHealthResult(
            isHealthy = true,
            binderAlive = true,
            serviceRunning = true,
            permissionGranted = true,
            canExecute = false,
            reason = "轻量检查通过（命令执行未测试）",
        )
    }

    /**
     * 辅助判断：Shizuku 是否完全可用（适合用于决策）。
     * 只有当 isHealthy 为 true 时才返回 true。
     *
     * 注意：这需要调用 suspend fun check()，所以这个函数也必须是 suspend。
     */
    suspend fun isFullyAvailable(): Boolean = check().isHealthy
}
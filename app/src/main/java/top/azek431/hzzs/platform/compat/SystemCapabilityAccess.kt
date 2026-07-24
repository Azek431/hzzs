package top.azek431.hzzs.platform.compat

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import top.azek431.hzzs.service.automation.HzzsAccessibilityService
import top.azek431.hzzs.service.automation.ShellProcessSupport

/**
 * 系统级能力查询与设置页跳转（悬浮窗 / 无障碍 / 修改系统设置 / 指针位置）。
 *
 * 职责：集中 API 边界，避免 feature 散落 `Settings` / Intent / Shell 构造。
 * 不变量：
 * - **不**静默开启任何权限；AUTO 截图路径不经此文件升权。
 * - 指针位置：若 Shizuku **已授权**则**优先**走 `settings put`（与手势/截图同源 [ShellProcessSupport]）；
 *   否则 [WRITE_SETTINGS]；再否则 Root `su`。**不**在此弹 Shizuku 授权、不静默升 Root。
 * - 写入成功以**回读** [isPointerLocationEnabled] 为准。
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

    /**
     * 当前是否可用已授权的 Shizuku（不请求权限）。
     * 与 [ShellProcessSupport.isShizukuAuthorized] 同判定，避免 UI/手势/指针三套口径。
     */
    fun isShizukuAuthorized(): Boolean = ShellProcessSupport.isShizukuAuthorized()

    /** Shizuku binder 是否在跑（未要求本应用权限）。 */
    fun isShizukuBinderAlive(): Boolean =
        runCatching { Shizuku.pingBinder() }.getOrDefault(false)

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
     * 1. **已授权 Shizuku** → [ShellProcessSupport.runShizukuOk] `settings put system pointer_location`
     * 2. 已授予 [WRITE_SETTINGS] → ContentResolver
     * 3. Root → [ShellProcessSupport.runRootOk]
     *
     * 每条路径成功后均回读确认；不请求 Shizuku 权限、不在 AUTO 路径使用。
     * 优先 Shizuku：用户已把手势设为 Shizuku 时，指针开关应真正走到 shell，而不是卡在 OEM 假成功的 WRITE_SETTINGS。
     */
    suspend fun setPointerLocationEnabledBestEffort(
        context: Context,
        enabled: Boolean,
    ): PointerLocationWriteResult = withContext(Dispatchers.IO) {
        val value = if (enabled) "1" else "0"
        val appContext = context.applicationContext
        val settingsCommand = arrayOf(
            "settings",
            "put",
            "system",
            POINTER_LOCATION_KEY,
            value,
        )
        val rootCommand = "settings put system $POINTER_LOCATION_KEY $value"

        // 1) Shizuku first when already authorized (same process API as gesture/screencap).
        if (ShellProcessSupport.isShizukuAuthorized()) {
            val ok = ShellProcessSupport.runShizukuOk(settingsCommand, TIMEOUT_MS)
            if (ok && isPointerLocationEnabled(appContext) == enabled) {
                return@withContext PointerLocationWriteResult.Success(
                    PointerLocationWritePath.SHIZUKU,
                )
            }
        }

        // 2) WRITE_SETTINGS
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

        // 3) Root last
        if (ShellProcessSupport.runRootOk(rootCommand, TIMEOUT_MS) &&
            isPointerLocationEnabled(appContext) == enabled
        ) {
            return@withContext PointerLocationWriteResult.Success(PointerLocationWritePath.ROOT)
        }

        PointerLocationWriteResult.Failed(
            canWriteSettings = canWriteSystemSettings(appContext),
            shizukuAuthorized = ShellProcessSupport.isShizukuAuthorized(),
            shizukuBinderAlive = isShizukuBinderAlive(),
            observedEnabled = isPointerLocationEnabled(appContext),
        )
    }

    /**
     * 诊断用一行摘要（无密钥）：指针开/关、WRITE_SETTINGS、Shizuku binder/授权。
     */
    fun pointerLocationDiagnosticsLine(context: Context): String {
        val app = context.applicationContext
        return buildString {
            append("pointerLocation=")
            append(if (isPointerLocationEnabled(app)) "on" else "off")
            append(" canWriteSettings=")
            append(canWriteSystemSettings(app))
            append(" shizuku.binder=")
            append(isShizukuBinderAlive())
            append(" shizuku.authorized=")
            append(isShizukuAuthorized())
        }
    }

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
        val shizukuBinderAlive: Boolean = false,
        /** 失败后回读到的当前系统状态，供 UI 对齐。 */
        val observedEnabled: Boolean = false,
    ) : PointerLocationWriteResult()
}

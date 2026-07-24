package top.azek431.hzzs.platform.compat

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku
import top.azek431.hzzs.service.automation.HzzsAccessibilityService
import top.azek431.hzzs.service.automation.ShellProcessSupport
import java.util.concurrent.atomic.AtomicReference

/**
 * 系统级能力查询与设置页跳转（悬浮窗 / 无障碍 / 修改系统设置 / 指针位置）。
 *
 * 职责：集中 API 边界，避免 feature 散落 `Settings` / Intent / Shell 构造。
 * 不变量：
 * - **不**静默开启任何权限；AUTO 截图路径不经此文件升权。
 * - 指针位置：binder 在但未授权时可**用户确认** [requestShizukuPermission]；
 *   已授权优先 shell（绝对路径，与手势 `input` 同源）；否则 WRITE_SETTINGS / Root。
 * - 写入成功以**延迟回读** [isPointerLocationEnabled] 为准（system/secure 任一为 1 视为开）。
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
     * 读 [Settings.System] 与 [Settings.Secure]（部分 ROM 放 Secure）；任一为 1 即视为开。
     */
    fun isPointerLocationEnabled(context: Context): Boolean {
        val cr = context.applicationContext.contentResolver
        val systemOn = runCatching {
            Settings.System.getInt(cr, POINTER_LOCATION_KEY, 0) == 1
        }.getOrDefault(false)
        if (systemOn) return true
        return runCatching {
            Settings.Secure.getInt(cr, POINTER_LOCATION_KEY, 0) == 1
        }.getOrDefault(false)
    }

    /**
     * 当前是否可用已授权的 Shizuku（不请求权限）。
     * 与 [ShellProcessSupport.isShizukuAuthorized] 同判定。
     */
    fun isShizukuAuthorized(): Boolean = ShellProcessSupport.isShizukuAuthorized()

    /** Shizuku binder 是否在跑（未要求本应用权限）。 */
    fun isShizukuBinderAlive(): Boolean =
        runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    /**
     * 若 binder 在跑且尚未授权，弹出 Shizuku 授权（用户确认）。
     * 已授权直接 true；binder 未运行 false。
     * 与截图 Shizuku 帧源同源 API，**不**静默授权。
     */
    suspend fun requestShizukuPermission(): Boolean {
        if (!isShizukuBinderAlive()) return false
        if (isShizukuAuthorized()) return true
        if (runCatching { Shizuku.isPreV11() }.getOrDefault(true)) return false
        return withContext(Dispatchers.Main) {
            val deferred = CompletableDeferred<Boolean>()
            val listener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
                if (requestCode == SHIZUKU_PERMISSION_REQUEST_CODE) {
                    deferred.complete(grantResult == PackageManager.PERMISSION_GRANTED)
                }
            }
            try {
                Shizuku.addRequestPermissionResultListener(listener)
                Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST_CODE)
                withTimeoutOrNull(20_000L) { deferred.await() } == true
            } catch (_: Throwable) {
                false
            } finally {
                runCatching { Shizuku.removeRequestPermissionResultListener(listener) }
            }
        }
    }

    /**
     * 同步写入：仅走 [WRITE_SETTINGS]，并以延迟回读校验。
     * 升权路径请用 [setPointerLocationEnabledBestEffort]。
     */
    fun setPointerLocationEnabled(context: Context, enabled: Boolean): Boolean {
        if (!canWriteSystemSettings(context)) return false
        val putOk = putPointerViaContentResolver(context, enabled)
        return putOk && settleAndMatches(context, enabled)
    }

    /**
     * 写入系统「指针位置」：
     * 1. binder 在但未授权 → 可先 [requestShizukuPermission]（须在主线程友好路径；本方法先切 Main）
     * 2. 已授权 Shizuku → shell（绝对路径优先，**首条成功即停**，会话缓存前缀）
     * 3. WRITE_SETTINGS → ContentResolver
     * 4. Root shell
     *
     * 每条路径成功后延迟回读确认（OEM 异步落库）。
     */
    suspend fun setPointerLocationEnabledBestEffort(
        context: Context,
        enabled: Boolean,
        requestShizukuIfNeeded: Boolean = true,
    ): PointerLocationWriteResult {
        val appContext = context.applicationContext
        val value = if (enabled) "1" else "0"

        // 授权弹窗必须在主线程；与写盘分离，避免在 IO 里嵌套 request。
        if (requestShizukuIfNeeded &&
            isShizukuBinderAlive() &&
            !ShellProcessSupport.isShizukuAuthorized()
        ) {
            requestShizukuPermission()
        }

        return withContext(Dispatchers.IO) {
            var lastShellDetail: String? = null

            // 1) Shizuku shell
            if (ShellProcessSupport.isShizukuAuthorized()) {
                val shell = tryWritePointerViaShell(value, useShizuku = true)
                lastShellDetail = shell.detail
                if (shell.ok && settleAndMatches(appContext, enabled)) {
                    return@withContext PointerLocationWriteResult.Success(
                        PointerLocationWritePath.SHIZUKU,
                        shell.via,
                    )
                }
            }

            // 2) WRITE_SETTINGS
            if (canWriteSystemSettings(appContext)) {
                if (putPointerViaContentResolver(appContext, enabled) &&
                    settleAndMatches(appContext, enabled)
                ) {
                    return@withContext PointerLocationWriteResult.Success(
                        PointerLocationWritePath.WRITE_SETTINGS,
                        "content_resolver",
                    )
                }
            }

            // 3) Root
            val root = tryWritePointerViaShell(value, useShizuku = false)
            if (root.detail != null) lastShellDetail = root.detail
            if (root.ok && settleAndMatches(appContext, enabled)) {
                return@withContext PointerLocationWriteResult.Success(
                    PointerLocationWritePath.ROOT,
                    root.via,
                )
            }

            PointerLocationWriteResult.Failed(
                canWriteSettings = canWriteSystemSettings(appContext),
                shizukuAuthorized = ShellProcessSupport.isShizukuAuthorized(),
                shizukuBinderAlive = isShizukuBinderAlive(),
                observedEnabled = isPointerLocationEnabled(appContext),
                lastShellDetail = lastShellDetail,
            )
        }
    }

    private fun putPointerViaContentResolver(context: Context, enabled: Boolean): Boolean {
        val cr = context.applicationContext.contentResolver
        val v = if (enabled) 1 else 0
        val systemOk = runCatching {
            Settings.System.putInt(cr, POINTER_LOCATION_KEY, v)
        }.getOrDefault(false)
        // Secure 普通应用通常无 WRITE_SECURE_SETTINGS；失败忽略
        runCatching { Settings.Secure.putInt(cr, POINTER_LOCATION_KEY, v) }
        return systemOk
    }

    /** 写后短暂等待再读，避免 OEM 异步落库造成假失败。 */
    private fun settleAndMatches(context: Context, enabled: Boolean): Boolean {
        repeat(SETTLE_ATTEMPTS) { attempt ->
            if (attempt > 0) {
                runCatching { Thread.sleep(SETTLE_STEP_MS * attempt) }
            }
            if (isPointerLocationEnabled(context) == enabled) return true
        }
        return false
    }

    /**
     * Shell 多写法兼容 OEM / 空 PATH（对齐手势 `/system/bin/input`）：
     * - 会话内记住成功过的前缀，优先复用
     * - **首条 exit0 即停**，不再盲跑满表
     */
    private suspend fun tryWritePointerViaShell(
        value: String,
        useShizuku: Boolean,
    ): ShellWriteOutcome {
        val candidates = pointerShellCommands(value)
        val preferred = preferredShellPrefix.get()
        val ordered = if (preferred == null) {
            candidates
        } else {
            val hit = candidates.filter { shellPrefixKey(it) == preferred }
            val rest = candidates.filter { shellPrefixKey(it) != preferred }
            if (hit.isEmpty()) candidates else hit + rest
        }
        var lastDetail: String? = null
        for ((index, cmd) in ordered.withIndex()) {
            val isPreferred = preferred != null && shellPrefixKey(cmd) == preferred
            val isLast = index == ordered.lastIndex
            // 非首选短超时 fail-fast（与 input 手势同策略）
            val timeoutMs = when {
                isPreferred || isLast -> TIMEOUT_MS
                else -> FAIL_FAST_TIMEOUT_MS
            }
            val result = if (useShizuku) {
                ShellProcessSupport.runShizukuResult(cmd, timeoutMs)
            } else {
                ShellProcessSupport.runRootResult(cmd.joinToString(" "), timeoutMs)
            }
            if (result.ok) {
                preferredShellPrefix.set(shellPrefixKey(cmd))
                return ShellWriteOutcome(
                    ok = true,
                    detail = null,
                    via = shellPrefixKey(cmd),
                )
            }
            if (result.detail != null) lastDetail = result.detail
        }
        return ShellWriteOutcome(ok = false, detail = lastDetail, via = null)
    }

    /** 固定字面量命令表（无用户输入拼接）。 */
    private fun pointerShellCommands(value: String): List<Array<String>> = listOf(
        arrayOf("/system/bin/settings", "put", "system", POINTER_LOCATION_KEY, value),
        arrayOf("/system/bin/settings", "put", "secure", POINTER_LOCATION_KEY, value),
        arrayOf("/system/bin/cmd", "settings", "put", "system", POINTER_LOCATION_KEY, value),
        arrayOf("/system/bin/cmd", "settings", "put", "secure", POINTER_LOCATION_KEY, value),
        arrayOf("settings", "put", "system", POINTER_LOCATION_KEY, value),
        arrayOf("settings", "put", "secure", POINTER_LOCATION_KEY, value),
        arrayOf("cmd", "settings", "put", "system", POINTER_LOCATION_KEY, value),
        arrayOf("cmd", "settings", "put", "secure", POINTER_LOCATION_KEY, value),
    )

    private fun shellPrefixKey(command: Array<String>): String = when {
        command.size >= 2 && command[0] == "/system/bin/cmd" && command[1] == "settings" ->
            "abs-cmd-settings"
        command.size >= 2 && command[0] == "cmd" && command[1] == "settings" ->
            "cmd-settings"
        command.isNotEmpty() && command[0] == "/system/bin/settings" ->
            "abs-settings"
        command.isNotEmpty() && command[0] == "settings" ->
            "settings"
        command.isNotEmpty() -> command[0]
        else -> ""
    }

    private data class ShellWriteOutcome(
        val ok: Boolean,
        val detail: String?,
        val via: String?,
    )

    /**
     * 诊断用一行摘要（无密钥）：指针开/关、WRITE_SETTINGS、Shizuku binder/授权、缓存 shell 前缀。
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
            append(" shell.prefix=")
            append(preferredShellPrefix.get() ?: "-")
        }
    }

    private fun openAppDetailsSettings(context: Context) {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    private const val TIMEOUT_MS = 6_000L
    private const val FAIL_FAST_TIMEOUT_MS = 450L
    private const val SETTLE_ATTEMPTS = 3
    private const val SETTLE_STEP_MS = 80L
    private const val SHIZUKU_PERMISSION_REQUEST_CODE = 0x485A_5A53 // 'HZS' + S

    /**
     * 开发者选项「指针位置」键名（公开 SDK 无常量）。
     * AOSP：`Settings.System.POINTER_LOCATION`；部分 ROM 可读 Secure。
     */
    private const val POINTER_LOCATION_KEY = "pointer_location"

    /** 本进程成功过的 shell 前缀（与 input 手势缓存同思路）。 */
    private val preferredShellPrefix = AtomicReference<String?>(null)
}

/** 指针位置写入实际采用的通道。 */
enum class PointerLocationWritePath {
    WRITE_SETTINGS,
    SHIZUKU,
    ROOT,
}

/** [SystemCapabilityAccess.setPointerLocationEnabledBestEffort] 结果。 */
sealed class PointerLocationWriteResult {
    data class Success(
        val path: PointerLocationWritePath,
        /** 成功子路径：content_resolver / abs-settings / … */
        val via: String? = null,
    ) : PointerLocationWriteResult()

    data class Failed(
        val canWriteSettings: Boolean,
        val shizukuAuthorized: Boolean,
        val shizukuBinderAlive: Boolean = false,
        /** 失败后回读到的当前系统状态，供 UI 对齐。 */
        val observedEnabled: Boolean = false,
        /** 最后一条 shell 失败摘要（截断，无密钥）；供 Snackbar 诊断。 */
        val lastShellDetail: String? = null,
    ) : PointerLocationWriteResult()
}

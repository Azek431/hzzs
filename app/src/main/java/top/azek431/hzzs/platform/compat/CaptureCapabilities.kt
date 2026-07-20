package top.azek431.hzzs.platform.compat

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import rikka.shizuku.Shizuku
import top.azek431.hzzs.core.model.CaptureBackend
import top.azek431.hzzs.service.automation.HzzsAccessibilityService
import javax.inject.Inject
import javax.inject.Singleton


/** Version-level availability only. Permission/connection readiness is evaluated separately. */
fun CaptureBackend.isSupportedOnThisDevice(): Boolean = when (this) {
    CaptureBackend.AUTO, CaptureBackend.MEDIA_PROJECTION, CaptureBackend.ROOT, CaptureBackend.SHIZUKU -> true
    CaptureBackend.ACCESSIBILITY -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
}

data class CaptureCapability(
    val backend: CaptureBackend,
    val supported: Boolean,
    val ready: Boolean,
    val recommended: Boolean,
    val title: String,
    val summary: String,
)

/** Centralizes Android-version checks so business and UI code never scatter API gates. */
@Singleton
class CaptureCapabilityResolver @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    fun all(): List<CaptureCapability> = listOf(
        CaptureCapability(
            backend = CaptureBackend.AUTO,
            supported = true,
            ready = true,
            recommended = true,
            title = "自动推荐",
            summary = "使用公开、低权限的屏幕录制接口，不会自动尝试 Root 或 Shell。",
        ),
        CaptureCapability(
            backend = CaptureBackend.MEDIA_PROJECTION,
            supported = true,
            ready = true,
            recommended = true,
            title = "屏幕录制",
            summary = "Android 7+ 推荐，持续帧率高；启动时由系统显示授权窗口。",
        ),
        CaptureCapability(
            backend = CaptureBackend.ACCESSIBILITY,
            supported = CaptureBackend.ACCESSIBILITY.isSupportedOnThisDevice(),
            ready = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && HzzsAccessibilityService.isConnected(),
            recommended = false,
            title = "无障碍截图",
            summary = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                "适合已经开启无障碍服务的用户；系统会限制截图频率。"
            } else {
                "需要 Android 11 或更高版本。"
            },
        ),
        CaptureCapability(
            backend = CaptureBackend.SHIZUKU,
            supported = true,
            ready = isShizukuReady(),
            recommended = false,
            title = "Shizuku / ADB",
            summary = when {
                !hasPackage("moe.shizuku.privileged.api") ->
                    "需安装并启动 Shizuku。将通过 Shizuku 执行 screencap，不会在 AUTO 路径自动启用。"
                !runCatching { Shizuku.pingBinder() }.getOrDefault(false) ->
                    "已安装 Shizuku，但服务未运行。请先在 Shizuku 应用中启动。"
                runCatching { Shizuku.checkSelfPermission() }.getOrDefault(PackageManager.PERMISSION_DENIED) !=
                    PackageManager.PERMISSION_GRANTED ->
                    "Shizuku 已运行，请在首次使用时授予本应用 Shizuku 权限。"
                else ->
                    "Shizuku 可用。通过受限 shell 截图，延迟通常高于屏幕录制。"
            },
        ),
        CaptureCapability(
            backend = CaptureBackend.ROOT,
            supported = true,
            ready = false,
            recommended = false,
            title = "Root",
            summary = "最高权限实验后端。每帧 PNG 截图不一定比屏幕录制更快，且兼容风险最高。",
        ),
    )

    private fun isShizukuReady(): Boolean = runCatching {
        hasPackage("moe.shizuku.privileged.api") &&
            Shizuku.pingBinder() &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    private fun hasPackage(packageName: String): Boolean = runCatching {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(packageName, 0)
    }.isSuccess
}

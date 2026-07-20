package top.azek431.hzzs.platform.compat

import android.content.Context
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import top.azek431.hzzs.core.model.CaptureBackend
import top.azek431.hzzs.service.automation.HzzsAccessibilityService
import javax.inject.Inject
import javax.inject.Singleton


/** Version-level availability only. Permission/connection readiness is evaluated separately. */
fun CaptureBackend.isSupportedOnThisDevice(): Boolean = when (this) {
    CaptureBackend.AUTO, CaptureBackend.MEDIA_PROJECTION, CaptureBackend.ROOT -> true
    CaptureBackend.ACCESSIBILITY -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
    CaptureBackend.SHIZUKU -> false
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
            supported = false,
            ready = false,
            recommended = false,
            title = "Shizuku / ADB",
            summary = if (hasPackage("moe.shizuku.privileged.api")) {
                "已检测到 Shizuku，但当前版本未内置安全的 UserService 截图适配器。"
            } else {
                "当前版本未内置 Shizuku UserService 截图适配器。"
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

    private fun hasPackage(packageName: String): Boolean = runCatching {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(packageName, 0)
    }.isSuccess
}

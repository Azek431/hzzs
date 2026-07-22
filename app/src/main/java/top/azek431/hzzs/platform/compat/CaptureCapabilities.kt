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


/**
 * 仅表示当前系统版本是否“支持该后端”，不包含运行时权限/连接就绪。
 * 业务与 UI 应集中调用此门闩，禁止散落 API 判断。
 */
fun CaptureBackend.isSupportedOnThisDevice(): Boolean = when (this) {
    CaptureBackend.AUTO, CaptureBackend.MEDIA_PROJECTION, CaptureBackend.ROOT, CaptureBackend.SHIZUKU -> true
    CaptureBackend.ACCESSIBILITY -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
}

/**
 * 有效截图后端解析结果。
 *
 * [requested] 为配置意图（开发者强制优先）；[effective] 为实际会启动的后端。
 * 当设备 API 不支持请求后端时 fail-soft 回退，并填写 [fallbackReason] 供诊断与日志。
 */
data class CaptureBackendResolution(
    val requested: CaptureBackend,
    val effective: CaptureBackend,
    val fallbackReason: String? = null,
) {
    val fellBack: Boolean get() = requested != effective
}

/**
 * 解析运行时真正使用的截图后端。
 *
 * 规则：
 * 1. 开发者开启且 [forceCaptureBackend] 非空时，以其为请求后端；否则用 [captureBackend]；
 * 2. 请求后端在本机 [isSupported] 为真则原样使用；
 * 3. 否则 fail-soft：优先回退到仍受支持的用户 [captureBackend]（当请求来自 force 时），
 *    再回退 [CaptureBackend.MEDIA_PROJECTION]（始终受支持的低权限公开接口）。
 *
 * 安全不变量：本函数**不会**把 AUTO 升权到无障碍 / Shizuku / Root；回退目标仅限已支持路径。
 * [isSupported] 默认同 [CaptureBackend.isSupportedOnThisDevice]；单测可注入。
 */
fun resolveEffectiveCaptureBackend(
    captureBackend: CaptureBackend,
    developerEnabled: Boolean,
    forceCaptureBackend: CaptureBackend?,
    isSupported: (CaptureBackend) -> Boolean = CaptureBackend::isSupportedOnThisDevice,
): CaptureBackendResolution {
    val requested = forceCaptureBackend?.takeIf { developerEnabled } ?: captureBackend
    if (isSupported(requested)) {
        return CaptureBackendResolution(requested = requested, effective = requested)
    }
    val fallback = when {
        // force 不可用时，若用户主配置仍可用且与请求不同，优先尊重主配置。
        forceCaptureBackend != null &&
            developerEnabled &&
            captureBackend != requested &&
            isSupported(captureBackend) -> captureBackend
        isSupported(CaptureBackend.MEDIA_PROJECTION) -> CaptureBackend.MEDIA_PROJECTION
        isSupported(CaptureBackend.AUTO) -> CaptureBackend.AUTO
        else -> CaptureBackend.MEDIA_PROJECTION
    }
    val reason = when (requested) {
        CaptureBackend.ACCESSIBILITY ->
            "ACCESSIBILITY 需要 Android 11+（API 30），已回退 ${fallback.name}"
        else ->
            "${requested.name} 在本机不受支持，已回退 ${fallback.name}"
    }
    return CaptureBackendResolution(
        requested = requested,
        effective = fallback,
        fallbackReason = reason,
    )
}

/**
 * 单一后端的能力快照：支持度、就绪、是否推荐，以及设置页展示文案。
 * [recommended] 仅供 UI 提示，不强制切换。
 */
data class CaptureCapability(
    val backend: CaptureBackend,
    val supported: Boolean,
    val ready: Boolean,
    val recommended: Boolean,
    val title: String,
    val summary: String,
)

/**
 * 截图后端能力解析中心：聚合版本门闩、无障碍连接、Shizuku 安装/授权状态。
 *
 * 安全不变量：
 * - AUTO 始终标记为推荐且就绪文案声明“不自动尝试 Root/Shell”；
 * - 探测 Shizuku 仅用于展示，不会在 AUTO 路径启用；
 * - Root 的 ready 固定 false（需用户在运行路径显式探测），避免误导“已可用”。
 */
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

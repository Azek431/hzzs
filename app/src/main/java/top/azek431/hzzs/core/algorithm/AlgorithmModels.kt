package top.azek431.hzzs.core.algorithm

import top.azek431.hzzs.core.model.AlgorithmChannel
import top.azek431.hzzs.core.model.AlgorithmSelectionMode
import top.azek431.hzzs.core.model.SceneId
import top.azek431.hzzs.core.model.UpdateSourcePreference
import top.azek431.hzzs.core.update.UpdateSourceId

/** 算法包来源：内置、已安装缓存或远端目录。 */
enum class AlgorithmOrigin {
    BUILTIN,
    INSTALLED,
    REMOTE,
}

/** 算法卡上展示的兼容/更新状态。 */
enum class AlgorithmCardStatus {
    CURRENT,
    LATEST,
    UPDATABLE,
    INSTALLED,
    DOWNLOADABLE,
    INCOMPATIBLE,
    PENDING_ACTIVATION,
}

/** 官方签名校验状态。 */
enum class AlgorithmSignatureState {
    OFFICIAL,
    UNSIGNED,
    UNTRUSTED,
    UNKNOWN,
}

/** 下载来源徽章。 */
enum class AlgorithmDownloadSource {
    BUILTIN,
    GITEE,
    GITHUB,
    CACHE,
}

/**
 * 算法目录条目。
 *
 * 坐标与版本元数据仅用于 UI / 选择；真正加载仍走现有内置引擎，直到远端包激活链路接入。
 */
data class AlgorithmPackageInfo(
    val id: String,
    val name: String,
    val versionName: String,
    val versionCode: Long,
    val channel: AlgorithmChannel,
    val summary: String,
    val supportedScenes: Set<SceneId>,
    val minAppVersionCode: Long,
    val publishedAtEpochMs: Long,
    val sizeBytes: Long,
    val origin: AlgorithmOrigin,
    val signature: AlgorithmSignatureState,
    val downloadSource: AlgorithmDownloadSource,
    val releaseNotes: String = "",
    val isBuiltin: Boolean = false,
    val isInstalled: Boolean = false,
    val isCompatible: Boolean = true,
)

/** 算法列表/下载任务的页面级状态。 */
sealed class AlgorithmCatalogPhase {
    data object Idle : AlgorithmCatalogPhase()
    data object Loading : AlgorithmCatalogPhase()
    data object Empty : AlgorithmCatalogPhase()
    data class OfflineWithCache(val message: String) : AlgorithmCatalogPhase()
    data class MirrorFallback(val reason: String, val activeSource: UpdateSourceId) : AlgorithmCatalogPhase()
    data class SecurityWarning(val message: String) : AlgorithmCatalogPhase()
    data class Downloading(val algorithmId: String, val progress: Float) : AlgorithmCatalogPhase()
    data class Verifying(val algorithmId: String) : AlgorithmCatalogPhase()
    data class PendingActivation(val algorithmId: String, val message: String) : AlgorithmCatalogPhase()
    data class Error(val message: String) : AlgorithmCatalogPhase()
    data class Incompatible(val message: String) : AlgorithmCatalogPhase()
}

data class AlgorithmDownloadTask(
    val algorithmId: String,
    val progress: Float = 0f,
    val verifying: Boolean = false,
    val error: String? = null,
)

/**
 * 算法模块对外暴露的聚合状态。
 *
 * ViewModel / Compose 只读本对象；写操作走 [AlgorithmCatalogController] 的方法。
 */
data class AlgorithmCatalogState(
    val phase: AlgorithmCatalogPhase = AlgorithmCatalogPhase.Idle,
    val active: AlgorithmPackageInfo? = null,
    val pendingActivation: AlgorithmPackageInfo? = null,
    val previousRollback: AlgorithmPackageInfo? = null,
    val installed: List<AlgorithmPackageInfo> = emptyList(),
    val remote: List<AlgorithmPackageInfo> = emptyList(),
    val selectionMode: AlgorithmSelectionMode = AlgorithmSelectionMode.AUTO,
    val channel: AlgorithmChannel = AlgorithmChannel.STABLE,
    val sourcePreference: UpdateSourcePreference = UpdateSourcePreference.AUTO,
    val activeSource: UpdateSourceId? = null,
    val lastMirrorReason: String? = null,
    val lastCheckedAtEpochMs: Long? = null,
    val downloads: Map<String, AlgorithmDownloadTask> = emptyMap(),
    val analysisRunning: Boolean = false,
    val message: String? = null,
)

fun AlgorithmPackageInfo.statusAgainst(
    activeId: String?,
    pendingId: String?,
    latestCompatibleId: String?,
): AlgorithmCardStatus {
    if (!isCompatible) return AlgorithmCardStatus.INCOMPATIBLE
    if (pendingId != null && id == pendingId) return AlgorithmCardStatus.PENDING_ACTIVATION
    if (activeId != null && id == activeId) return AlgorithmCardStatus.CURRENT
    if (isInstalled && latestCompatibleId == id) return AlgorithmCardStatus.LATEST
    if (isInstalled) return AlgorithmCardStatus.INSTALLED
    if (latestCompatibleId == id) return AlgorithmCardStatus.LATEST
    if (activeId != null && versionCode > (remoteVersionOf(activeId) ?: 0L)) {
        return AlgorithmCardStatus.UPDATABLE
    }
    return AlgorithmCardStatus.DOWNLOADABLE
}

/** 辅助：在缺少 active 版本信息时退回 0。 */
private fun AlgorithmPackageInfo.remoteVersionOf(activeId: String): Long? =
    if (id == activeId) versionCode else null

fun AlgorithmCardStatus.label(): String = when (this) {
    AlgorithmCardStatus.CURRENT -> "当前"
    AlgorithmCardStatus.LATEST -> "最新"
    AlgorithmCardStatus.UPDATABLE -> "可更新"
    AlgorithmCardStatus.INSTALLED -> "已安装"
    AlgorithmCardStatus.DOWNLOADABLE -> "可下载"
    AlgorithmCardStatus.INCOMPATIBLE -> "不兼容"
    AlgorithmCardStatus.PENDING_ACTIVATION -> "待启用"
}

fun AlgorithmSignatureState.label(): String = when (this) {
    AlgorithmSignatureState.OFFICIAL -> "官方签名"
    AlgorithmSignatureState.UNSIGNED -> "未签名"
    AlgorithmSignatureState.UNTRUSTED -> "不可信"
    AlgorithmSignatureState.UNKNOWN -> "未知"
}

fun AlgorithmDownloadSource.label(): String = when (this) {
    AlgorithmDownloadSource.BUILTIN -> "内置"
    AlgorithmDownloadSource.GITEE -> "Gitee"
    AlgorithmDownloadSource.GITHUB -> "GitHub"
    AlgorithmDownloadSource.CACHE -> "本地缓存"
}

fun AlgorithmOrigin.label(): String = when (this) {
    AlgorithmOrigin.BUILTIN -> "内置"
    AlgorithmOrigin.INSTALLED -> "已安装"
    AlgorithmOrigin.REMOTE -> "远端"
}

fun formatByteSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${"%.1f".format(bytes / 1024.0)} KB"
    else -> "${"%.1f".format(bytes / (1024.0 * 1024.0))} MB"
}

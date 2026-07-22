package top.azek431.hzzs.core.algorithm

import top.azek431.hzzs.core.model.AlgorithmChannel
import top.azek431.hzzs.core.model.AlgorithmSelectionMode
import top.azek431.hzzs.core.model.SceneId
import top.azek431.hzzs.core.model.UpdateSourcePreference
import top.azek431.hzzs.core.update.UpdateSourceId

/**
 * 算法目录 / 列表 UI 模型。
 *
 * 与 `domain.vision.AlgorithmRuntimeProfile` 分工：
 * - 本文件：目录条目、卡片状态、下载任务、页面聚合状态（给设置页）
 * - domain：真正进入 Native 的声明式参数快照
 *
 * 当前远端安装链路仍以 UI 契约 + 模拟为主，真实 `.hzzsalg` 安装器后续接入。
 */

/** 算法包来源：内置、已安装缓存或远端目录。 */
enum class AlgorithmOrigin {
    BUILTIN,
    INSTALLED,
    REMOTE,
}

/**
 * 算法卡片上的兼容/更新状态。
 *
 * 由 [statusAgainst] 根据当前激活、待启用与最新兼容 ID 推导。
 */
enum class AlgorithmCardStatus {
    CURRENT,
    LATEST,
    UPDATABLE,
    INSTALLED,
    DOWNLOADABLE,
    INCOMPATIBLE,
    PENDING_ACTIVATION,
}

/**
 * 官方签名校验状态。
 *
 * [UNTRUSTED] 必须阻止下载；UI 展示对应安全警告。
 */
enum class AlgorithmSignatureState {
    OFFICIAL,
    UNSIGNED,
    UNTRUSTED,
    UNKNOWN,
}

/** 下载来源徽章（展示用，不等于信任根）。 */
enum class AlgorithmDownloadSource {
    BUILTIN,
    GITEE,
    GITHUB,
    CACHE,
}

/**
 * 算法目录条目（UI / 选择元数据）。
 *
 * 真正加载参数仍走内置引擎或后续安装器激活链路；
 * 本结构不携带可执行代码或原始 rules JSON。
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

/**
 * 算法列表 / 下载任务的页面级相位。
 *
 * Compose 根据相位切换 Loading / Empty / Offline / 进度条 / 安全警告等。
 */
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

/** 单个算法的下载进度任务。 */
data class AlgorithmDownloadTask(
    val algorithmId: String,
    val progress: Float = 0f,
    val verifying: Boolean = false,
    val error: String? = null,
)

/**
 * 算法模块对外暴露的聚合状态。
 *
 * ViewModel / Compose **只读**；写操作走 [AlgorithmCatalogController]。
 *
 * @property active 当前解析出的激活包（可能仍是内置）
 * @property pendingActivation 已下载/已选择但尚未在分析会话中生效的包
 * @property analysisRunning 分析运行中时，切换仅能 pending
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

/**
 * 根据当前激活 / 待启用 / 最新兼容 ID 推导卡片状态。
 *
 * 优先级：不兼容 > 待启用 > 当前 > 已安装最新 > 已安装 > 远端最新 > 可更新 > 可下载。
 */
fun AlgorithmPackageInfo.statusAgainst(
    activeId: String?,
    pendingId: String?,
    latestCompatibleId: String?,
    activeVersionCode: Long? = null,
): AlgorithmCardStatus {
    if (!isCompatible) return AlgorithmCardStatus.INCOMPATIBLE
    if (pendingId != null && id == pendingId) return AlgorithmCardStatus.PENDING_ACTIVATION
    if (activeId != null && id == activeId) return AlgorithmCardStatus.CURRENT
    if (isInstalled && latestCompatibleId == id) return AlgorithmCardStatus.LATEST
    if (isInstalled) return AlgorithmCardStatus.INSTALLED
    if (latestCompatibleId == id) return AlgorithmCardStatus.LATEST
    val baseline = activeVersionCode ?: 0L
    if (activeId != null && versionCode > baseline) {
        return AlgorithmCardStatus.UPDATABLE
    }
    return AlgorithmCardStatus.DOWNLOADABLE
}

/** 卡片状态中文标签。 */
fun AlgorithmCardStatus.label(): String = when (this) {
    AlgorithmCardStatus.CURRENT -> "当前"
    AlgorithmCardStatus.LATEST -> "最新"
    AlgorithmCardStatus.UPDATABLE -> "可更新"
    AlgorithmCardStatus.INSTALLED -> "已安装"
    AlgorithmCardStatus.DOWNLOADABLE -> "可下载"
    AlgorithmCardStatus.INCOMPATIBLE -> "不兼容"
    AlgorithmCardStatus.PENDING_ACTIVATION -> "待启用"
}

/** 签名状态中文标签。 */
fun AlgorithmSignatureState.label(): String = when (this) {
    AlgorithmSignatureState.OFFICIAL -> "官方签名"
    AlgorithmSignatureState.UNSIGNED -> "未签名"
    AlgorithmSignatureState.UNTRUSTED -> "不可信"
    AlgorithmSignatureState.UNKNOWN -> "未知"
}

/** 下载来源中文标签。 */
fun AlgorithmDownloadSource.label(): String = when (this) {
    AlgorithmDownloadSource.BUILTIN -> "内置"
    AlgorithmDownloadSource.GITEE -> "Gitee"
    AlgorithmDownloadSource.GITHUB -> "GitHub"
    AlgorithmDownloadSource.CACHE -> "本地缓存"
}

/** 来源中文标签。 */
fun AlgorithmOrigin.label(): String = when (this) {
    AlgorithmOrigin.BUILTIN -> "内置"
    AlgorithmOrigin.INSTALLED -> "已安装"
    AlgorithmOrigin.REMOTE -> "远端"
}

/** 将字节数格式化为 B / KB / MB 展示串。 */
fun formatByteSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${"%.1f".format(bytes / 1024.0)} KB"
    else -> "${"%.1f".format(bytes / (1024.0 * 1024.0))} MB"
}

package top.azek431.hzzs.core.algorithm.logic

import top.azek431.hzzs.core.algorithm.AlgorithmCatalogPhase
import top.azek431.hzzs.core.algorithm.AlgorithmDownloadSource
import top.azek431.hzzs.core.algorithm.AlgorithmIds
import top.azek431.hzzs.core.algorithm.AlgorithmOrigin
import top.azek431.hzzs.core.algorithm.AlgorithmPackageInfo
import top.azek431.hzzs.core.algorithm.BundledAlgorithmInstaller
import top.azek431.hzzs.core.algorithm.CatalogRemoteEntry
import top.azek431.hzzs.core.algorithm.InstalledAlgorithmStore
import top.azek431.hzzs.core.model.AlgorithmChannel
import top.azek431.hzzs.core.model.AlgorithmSelectionMode
import top.azek431.hzzs.core.model.SceneId
import top.azek431.hzzs.core.update.UpdateSourceId
import org.json.JSONObject

/**
 * 算法目录纯函数集合。
 *
 * **命名即契约：本对象所有方法均为纯函数**（输入 → 输出，无副作用、无 Android 框架依赖、
 * 无 StateFlow / 无单例状态），便于 JVM 单测直接覆盖。
 *
 * 包含：
 * - [parseCatalog]：目录 JSON → [AlgorithmPackageInfo] 列表（校验 schema、id、文件名、sha256）
 * - [resolveActive]：按 MANUAL/AUTO 模式解析当前激活包
 * - [sortInstalled] / [sortRemote]：排序比较器（给 `sortedWith` 用）
 * - [mergeInstalled]：按 id 去重合并（bundled/已安装优先）
 * - [mergeDiskInstalled]：将磁盘 [InstalledAlgorithmStore.InstalledAlgorithmRecord] 映射为 UI 模型
 * - [planUpgrades]：计算可升级包（纯计划，不触发下载）
 * - [versionToCode]：语义化版本 → major*1e6 + minor*1e3 + patch
 * - [builtinPackages]：内置算法种子
 * - [computePending]：推导「待启用」包（纯函数，收归激活协调器前先在此可测）
 * - [catalogPhaseAfter]：由远端/已装列表推导目录相位
 *
 * 协议边界（见 `docs/ALGORITHM_SYSTEM_V1.md`）：
 * - 目录仅 HTTPS，schemaVersion=1
 * - 资产路径仅限 `algorithms/packages/<safe-name>`
 * - 远端包安装仅走 HTTPS + size/sha256 + ZIP 白名单（当前暂未启用 Ed25519 签名验签）
 *
 * 安全常量（与 `tools/algorithm/common.py` 对齐）集中在本对象，作为单一真相源；
 * `AlgorithmNetworkClient` 与 [top.azek431.hzzs.core.algorithm.AlgorithmCatalogController]
 * 均消费本对象，不再各自维护正则。
 */
object AlgorithmCatalogPure {

    // ---- 安全常量（单一真相源）----

    /** 与 `tools/algorithm/common.py` SAFE_ID 对齐。 */
    val SAFE_ID = Regex("^[a-z][a-z0-9-]{1,62}[a-z0-9]$")
    val SAFE_NAME = Regex("^[A-Za-z0-9._+-]{1,160}$")
    val SAFE_SHA256 = Regex("^[a-fA-F0-9]{64}$")
    /** 仅允许 `algorithms/packages/` 下单层安全文件名。 */
    val SAFE_ASSET_PATH = Regex("^algorithms/packages/[A-Za-z0-9._+-]{1,160}$")

    // ---- 纯函数 ----

    /**
     * 解析目录 JSON。
     *
     * @param raw UTF-8 文本
     * @param channel STABLE / BETA（写入返回条目）
     * @param source 实际拉取源（Gitee / GitHub）
     * @param appVersionCode 当前应用 longVersionCode（用于 isCompatible 判定）
     */
    fun parseCatalog(
        raw: String,
        channel: AlgorithmChannel,
        source: UpdateSourceId,
        appVersionCode: Long,
    ): List<top.azek431.hzzs.core.algorithm.CatalogRemoteEntry> {
        val root = JSONObject(raw)
        require(root.optInt("schemaVersion") == 1) { "不支持的算法目录 schema" }
        val algorithms = root.optJSONArray("algorithms") ?: return emptyList()
        val downloadSource = when (source) {
            UpdateSourceId.GITEE -> AlgorithmDownloadSource.GITEE
            UpdateSourceId.GITHUB -> AlgorithmDownloadSource.GITHUB
        }
        val out = ArrayList<top.azek431.hzzs.core.algorithm.CatalogRemoteEntry>(algorithms.length())
        for (i in 0 until algorithms.length()) {
            val item = algorithms.getJSONObject(i)
            if (item.optBoolean("revoked", false)) continue
            val id = item.getString("id")
            require(SAFE_ID.matches(id)) { "非法算法 id: $id" }
            val version = item.getString("version")
            val filename = item.getString("filename")
            require(SAFE_NAME.matches(filename)) { "非法文件名: $filename" }
            // 优先 assetPath；兼容旧目录里的 tag 字段（忽略，仅用于人工阅读）
            val assetPath = item.optString("assetPath").ifBlank { "algorithms/packages/$filename" }
            require(SAFE_ASSET_PATH.matches(assetPath)) { "非法资产路径: $assetPath" }
            val size = item.getLong("size")
            val sha256 = item.getString("sha256")
            require(SAFE_SHA256.matches(sha256)) { "非法 sha256: $sha256" }
            val scenes = item.optJSONArray("supportedScenes").toSceneSet()
            val minApp = item.optLong("minimumAppVersionCode", 1L)
            val versionCode = versionToCode(version)
            val info = AlgorithmPackageInfo(
                id = id,
                name = item.optString("displayName", id),
                versionName = version,
                versionCode = versionCode,
                channel = channel,
                summary = item.optString("description").ifBlank { item.optString("changelog") },
                supportedScenes = scenes,
                minAppVersionCode = minApp,
                publishedAtEpochMs = 0L,
                sizeBytes = size,
                origin = AlgorithmOrigin.REMOTE,
                downloadSource = downloadSource,
                releaseNotes = item.optString("changelog"),
                isCompatible = appVersionCode >= minApp,
            )
            out += top.azek431.hzzs.core.algorithm.CatalogRemoteEntry(info = info, assetPath = assetPath, filename = filename, sha256 = sha256)
        }
        return out
    }

    /** 按选择模式解析当前应展示的激活包。 */
    fun resolveActive(
        installed: List<AlgorithmPackageInfo>,
        pinned: String?,
        mode: AlgorithmSelectionMode,
        scene: SceneId,
    ): AlgorithmPackageInfo? {
        val compatible = installed.filter { it.isCompatible }
        return when (mode) {
            AlgorithmSelectionMode.MANUAL -> compatible.find { it.id == pinned }
                ?: compatible.firstOrNull { it.isBuiltin && scene in it.supportedScenes }
                ?: compatible.firstOrNull()
            AlgorithmSelectionMode.AUTO -> compatible
                .filter { scene in it.supportedScenes }
                .sortedWith(
                    compareByDescending<AlgorithmPackageInfo> { it.versionCode }
                        .thenByDescending { it.publishedAtEpochMs },
                )
                .firstOrNull()
                ?: compatible.firstOrNull { it.isBuiltin }
        }
    }

    /** 当前激活包之外的最近一个非内置包（用于「回滚」按钮）。 */
    fun previousOf(
        installed: List<AlgorithmPackageInfo>,
        activeId: String?,
    ): AlgorithmPackageInfo? {
        val ordered = installed
            .filter { it.isCompatible && !it.isBuiltin }
            .sortedByDescending { it.versionCode }
        if (ordered.isEmpty()) {
            return installed.firstOrNull { it.isBuiltin && it.id != activeId }
        }
        return ordered.firstOrNull { it.id != activeId }
            ?: installed.firstOrNull { it.isBuiltin && it.id != activeId }
    }

    /**
     * 按 id 去重合并（bundled / 已安装优先）。
     *
     * 内置包始终保留；`extras` 中已安装或 bundled 的条目会覆盖同 id。
     */
    fun mergeInstalled(
        current: List<AlgorithmPackageInfo>,
        extras: List<AlgorithmPackageInfo>,
    ): List<AlgorithmPackageInfo> {
        val map = linkedMapOf<String, AlgorithmPackageInfo>()
        builtinPackages().forEach { map[it.id] = it }
        current.forEach { map[it.id] = it }
        extras.filter {
            it.isInstalled ||
                it.origin == AlgorithmOrigin.INSTALLED ||
                it.origin == AlgorithmOrigin.BUNDLED
        }.forEach {
            map[it.id] = it.copy(
                isInstalled = true,
                origin = if (it.origin == AlgorithmOrigin.BUNDLED) AlgorithmOrigin.BUNDLED else AlgorithmOrigin.INSTALLED,
            )
        }
        return map.values.toList()
    }

    /**
     * 将磁盘 [InstalledAlgorithmStore.InstalledAlgorithmRecord] 映射为 UI 模型（纯映射，无副作用）。
     *
     * 供 [top.azek431.hzzs.core.algorithm.AlgorithmCatalogController] 在
     * `mergeDiskInstalled` 中调用，把 `store.listInstalled()` 转成 [AlgorithmPackageInfo]。
     */
    fun mergeDiskInstalled(
        records: List<InstalledAlgorithmStore.InstalledAlgorithmRecord>,
    ): List<AlgorithmPackageInfo> = records.map { record ->
        val isBundled = record.originTag == BundledAlgorithmInstaller.ORIGIN_BUNDLED
        val channel = BundledAlgorithmInstaller.channelOf(record.channelName)
        val summary = buildString {
            if (!record.author.isNullOrBlank()) {
                append(record.author)
                append(" · ")
            }
            append(
                record.summary?.take(120)
                    ?: if (isBundled) "随应用分发的声明式算法包" else "已安装算法包",
            )
        }
        AlgorithmPackageInfo(
            id = record.catalogId,
            name = record.displayName,
            versionName = record.version,
            versionCode = record.versionCode,
            channel = channel,
            summary = summary,
            supportedScenes = record.supportedScenes,
            minAppVersionCode = 1,
            publishedAtEpochMs = record.installedAtEpochMs,
            sizeBytes = 0,
            origin = if (isBundled) AlgorithmOrigin.BUNDLED else AlgorithmOrigin.INSTALLED,
            downloadSource = if (isBundled) {
                AlgorithmDownloadSource.BUNDLED
            } else {
                AlgorithmDownloadSource.CACHE
            },
            releaseNotes = record.summary.orEmpty(),
            isBuiltin = false,
            isInstalled = true,
            isCompatible = true,
            author = record.author,
        )
    }

    /**
     * 计算可升级计划（纯计划，不触发下载）。
     *
     * 跳过 [AlgorithmOrigin.BUILTIN] / [AlgorithmOrigin.BUNDLED]；
     * 仅对远端同 id 更高 versionCode 且兼容的包给出可升级 id。
     */
    fun planUpgrades(
        installed: List<AlgorithmPackageInfo>,
        remote: List<AlgorithmPackageInfo>,
    ): UpgradePlan {
        val remoteById = remote.associateBy { it.id }
        val candidates = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        val failed = mutableListOf<Pair<String, String>>()
        installed.forEach { pkg ->
            when {
                pkg.isBuiltin || pkg.origin == AlgorithmOrigin.BUILTIN || pkg.origin == AlgorithmOrigin.BUNDLED ->
                    skipped.add(pkg.id)
                else -> {
                    val remoteEntry = remoteById[pkg.id]
                    when {
                        remoteEntry == null -> skipped.add(pkg.id)
                        !remoteEntry.isCompatible -> failed.add(pkg.id to "不兼容当前应用版本")
                        remoteEntry.versionCode <= pkg.versionCode -> skipped.add(pkg.id)
                        else -> candidates.add(pkg.id)
                    }
                }
            }
        }
        return UpgradePlan(candidates = candidates, skipped = skipped, failed = failed)
    }

    /**
     * 计算 pending 激活包（纯函数，收归激活协调器前先在此可测）。
     *
     * 规则：
     * - 未分析且草稿钉选与当前 active 一致 → 无 pending
     * - 分析运行中且草稿钉选仍在 pending 列表中 → 保留 pending
     * - 否则 → 无 pending
     */
    fun computePending(
        pendingFromUi: AlgorithmPackageInfo?,
        activeId: String?,
        pinnedId: String?,
        mode: AlgorithmSelectionMode,
        analysisRunning: Boolean,
    ): AlgorithmPackageInfo? {
        val pending = pendingFromUi ?: return null
        return when {
            mode == AlgorithmSelectionMode.MANUAL &&
                pinnedId == pending.id &&
                (analysisRunning || activeId != pending.id) -> pending
            else -> null
        }
    }

    /** 按 active / builtin / bundled / 场景 / version 排序已安装列表。 */
    fun sortInstalled(
        installed: List<AlgorithmPackageInfo>,
        activeId: String?,
        scene: SceneId,
    ): Comparator<AlgorithmPackageInfo> =
        compareByDescending<AlgorithmPackageInfo> { it.id == activeId }
            .thenByDescending { it.isBuiltin }
            .thenByDescending { it.origin == AlgorithmOrigin.BUNDLED }
            .thenByDescending { scene in it.supportedScenes }
            .thenByDescending { it.versionCode }

    /** 按兼容 / 场景匹配 / version / 发布时间排序远端列表。 */
    fun sortRemote(
        remote: List<AlgorithmPackageInfo>,
        scene: SceneId,
    ): Comparator<AlgorithmPackageInfo> =
        compareByDescending<AlgorithmPackageInfo> { it.isCompatible }
            .thenByDescending { scene in it.supportedScenes }
            .thenByDescending { it.versionCode }
            .thenByDescending { it.publishedAtEpochMs }

    /**
     * 语义化版本 → long versionCode。
     *
     * `1.2.3` → 1_002_003；预发布后缀（`-beta.1`）与 build metadata（`+build`）被忽略。
     * 非法时回退 0。
     */
    fun versionToCode(version: String): Long {
        val core = version.substringBefore('-').substringBefore('+')
        val parts = core.split('.')
        if (parts.size < 3) return 0L
        val major = parts[0].toLongOrNull() ?: return 0L
        val minor = parts[1].toLongOrNull() ?: return 0L
        val patch = parts[2].toLongOrNull() ?: return 0L
        return major * 1_000_000L + minor * 1_000L + patch
    }

    /** 内置算法种子（基础引擎 + Native Vision）。 */
    fun builtinPackages(): List<AlgorithmPackageInfo> {
        val baseVersionCode = versionToCode(AlgorithmIds.BUILTIN_VERSION)
        val nvVersionCode = versionToCode("1.0.0")
        return listOf(
            AlgorithmPackageInfo(
                id = AlgorithmIds.BUILTIN_CATALOG_ID,
                name = "内置算法",
                versionName = AlgorithmIds.BUILTIN_VERSION,
                versionCode = baseVersionCode,
                channel = AlgorithmChannel.STABLE,
                summary = "随应用分发的三赛季默认识别引擎（runtime ${AlgorithmIds.BUILTIN_RUNTIME_ID} v${AlgorithmIds.BUILTIN_VERSION}）。",
                supportedScenes = SceneId.entries.toSet(),
                minAppVersionCode = 1,
                publishedAtEpochMs = 0L,
                sizeBytes = 0,
                origin = AlgorithmOrigin.BUILTIN,
                downloadSource = AlgorithmDownloadSource.BUILTIN,
                isBuiltin = true,
                isInstalled = true,
                isCompatible = true,
            ),
            AlgorithmPackageInfo(
                id = AlgorithmIds.NATIVE_VISION_CATALOG_ID,
                name = "HZZS Native Vision",
                versionName = "1.0.0",
                versionCode = nvVersionCode,
                channel = AlgorithmChannel.BETA,
                summary = "海盐客厅走 SeaSaltSparseDetector（vision_v3 三槽引擎），其余赛季回退主路径。",
                supportedScenes = setOf(SceneId.SEA_SALT_LIVING_ROOM),
                minAppVersionCode = 1,
                publishedAtEpochMs = 0L,
                sizeBytes = 0,
                origin = AlgorithmOrigin.BUILTIN,
                downloadSource = AlgorithmDownloadSource.BUILTIN,
                isBuiltin = true,
                isInstalled = true,
                isCompatible = true,
                author = "hzzs",
            ),
        )
    }

    /** 由远端目录条目 + 当前 active 包推导「可回滚」候选文案（仅用于 UI）。 */
    fun catalogPhaseAfter(
        current: AlgorithmCatalogPhase,
        remoteInfos: List<AlgorithmPackageInfo>,
        installed: List<AlgorithmPackageInfo>,
        catalog: RemoteCatalogMeta?,
    ): AlgorithmCatalogPhase {
        if (current is AlgorithmCatalogPhase.Downloading ||
            current is AlgorithmCatalogPhase.Verifying ||
            current is AlgorithmCatalogPhase.Loading
        ) {
            return current
        }
        return when {
            remoteInfos.isEmpty() && installed.isEmpty() -> AlgorithmCatalogPhase.Empty
            catalog?.usedFallback == true -> AlgorithmCatalogPhase.MirrorFallback(
                reason = catalog.fallbackReason.orEmpty(),
                activeSource = catalog.activeSource,
            )
            else -> AlgorithmCatalogPhase.Idle
        }
    }

    private fun org.json.JSONArray?.toSceneSet(): Set<SceneId> {
        if (this == null) return emptySet()
        val out = linkedSetOf<SceneId>()
        for (i in 0 until length()) {
            runCatching { SceneId.valueOf(getString(i)) }.getOrNull()?.let(out::add)
        }
        return out
    }

    // ---- 数据类 ----

    /** 目录拉取后的元数据（用于相位推导）。 */
    data class RemoteCatalogMeta(
        val activeSource: UpdateSourceId,
        val usedFallback: Boolean,
        val fallbackReason: String?,
        val message: String?,
    )

    /** 升级计划（dryRun）。 */
    data class UpgradePlan(
        val candidates: List<String>,
        val skipped: List<String>,
        val failed: List<Pair<String, String>>,
    )

    /** 升级执行结果。 */
    data class UpgradeResult(
        val upgraded: List<String>,
        val queued: List<String> = emptyList(),
        val skipped: List<String>,
        val failed: List<Pair<String, String>>,
    )
}

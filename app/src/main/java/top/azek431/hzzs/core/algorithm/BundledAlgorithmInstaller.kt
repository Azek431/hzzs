package top.azek431.hzzs.core.algorithm

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONObject
import top.azek431.hzzs.core.logging.AppLog
import top.azek431.hzzs.core.model.AlgorithmChannel
import top.azek431.hzzs.core.model.SceneId
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 将 APK `assets/algorithms/<id>/` 中的声明式算法包预装到 [InstalledAlgorithmStore]。
 *
 * 与网络下载路径分离：捆绑包视为应用本体的一部分，不经 Ed25519 外装验签；
 * 远端 `.hzzsalg` 仍必须过 [AlgorithmPackVerifier] 与信任锚。
 * 仅在目标 catalog 尚未安装时写入；已安装用户包不被覆盖。
 */
@Singleton
class BundledAlgorithmInstaller @Inject constructor(
    @param:ApplicationContext private val appContext: Context,
    private val store: InstalledAlgorithmStore,
) {
    private val ran = AtomicBoolean(false)

    /**
     * 幂等：进程内只完整扫描一次；单包失败不影响其它包。
     * @return 新安装的 catalogId 列表
     */
    fun ensureBundledInstalled(): List<String> {
        if (!ran.compareAndSet(false, true)) {
            return emptyList()
        }
        val installed = mutableListOf<String>()
        val assetManager = appContext.assets
        val roots = runCatching { assetManager.list(ASSETS_ROOT).orEmpty() }.getOrDefault(emptyArray())
        for (folder in roots) {
            val base = "$ASSETS_ROOT/$folder"
            val manifestName = "$base/manifest.json"
            val rulesName = "$base/rules.json"
            val hasManifest = runCatching {
                assetManager.open(manifestName).use { true }
            }.getOrDefault(false)
            val hasRules = runCatching {
                assetManager.open(rulesName).use { true }
            }.getOrDefault(false)
            if (!hasManifest || !hasRules) {
                AppLog.w("algorithm", "bundled pack incomplete: $folder")
                continue
            }
            runCatching {
                val manifestText = assetManager.open(manifestName).use { it.readBytes().toString(Charsets.UTF_8) }
                val manifest = JSONObject(manifestText)
                val catalogId = manifest.getString("id")
                if (store.get(catalogId) != null) {
                    AppLog.d("algorithm", "bundled already installed: $catalogId")
                    return@runCatching
                }
                val version = manifest.getString("version")
                val versionCode = versionCodeFromSemver(version)
                val staging = File(appContext.cacheDir, "bundled-alg-$catalogId-${System.nanoTime()}")
                try {
                    staging.mkdirs()
                    File(staging, "manifest.json").writeText(manifestText, Charsets.UTF_8)
                    assetManager.open(rulesName).use { input ->
                        File(staging, "rules.json").outputStream().use { output -> input.copyTo(output) }
                    }
                    runCatching {
                        assetManager.open("$base/CHANGELOG.txt").use { input ->
                            File(staging, "CHANGELOG.txt").outputStream().use { output -> input.copyTo(output) }
                        }
                    }
                    val record = store.installFromExtracted(
                        extracted = staging,
                        sha256 = null,
                        versionCode = versionCode,
                        originTag = ORIGIN_BUNDLED,
                        author = manifest.optString("author").takeIf { it.isNotBlank() },
                        summary = manifest.optString("description").takeIf { it.isNotBlank() },
                        channelName = manifest.optString("channel").takeIf { it.isNotBlank() },
                    ).getOrThrow()
                    installed += record.catalogId
                    AppLog.i(
                        "algorithm",
                        "bundled installed id=${record.catalogId} v=${record.version} " +
                            "scenes=${record.supportedScenes.joinToString { it.name }}",
                    )
                } finally {
                    staging.deleteRecursively()
                }
            }.onFailure { error ->
                AppLog.w(
                    "algorithm",
                    "bundled install failed folder=$folder: ${error.message ?: error.javaClass.simpleName}",
                )
            }
        }
        return installed
    }

    companion object {
        const val ASSETS_ROOT = "algorithms"
        const val ORIGIN_BUNDLED = "bundled"

        /** major*1e6 + minor*1e3 + patch；非法时回退 1。 */
        fun versionCodeFromSemver(version: String): Long {
            val core = version.substringBefore('-').substringBefore('+')
            val parts = core.split('.')
            if (parts.size < 3) return 1L
            val major = parts[0].toLongOrNull() ?: return 1L
            val minor = parts[1].toLongOrNull() ?: return 1L
            val patch = parts[2].toLongOrNull() ?: return 1L
            return major * 1_000_000L + minor * 1_000L + patch
        }

        fun channelOf(name: String?): AlgorithmChannel =
            when (name?.lowercase()) {
                "beta" -> AlgorithmChannel.BETA
                else -> AlgorithmChannel.STABLE
            }

        fun scenesLabel(scenes: Set<SceneId>): String =
            scenes.joinToString { it.name }
    }
}

package top.azek431.hzzs.core.algorithm

/**
 * 算法包 CatalogId 与运行时 algorithmId 的统一约定。
 *
 * - Catalog / 设置 pin / 磁盘目录：可见 ID（内置 [BUILTIN_CATALOG_ID] 或 manifest.id）
 * - Native / VisionResult：runtime ID（内置 [BUILTIN_RUNTIME_ID] 或 pack.&lt;catalogId&gt;）
 */
object AlgorithmIds {
    /** 内置包在目录/设置 pin 中的可见 ID；首版语义化版本为 0.1.0。 */
    const val BUILTIN_CATALOG_ID = "builtin-hzzs-base-0.1.0"
    const val BUILTIN_RUNTIME_ID = "builtin.hzzs.base"
    /** 与 [top.azek431.hzzs.domain.vision.AlgorithmRuntimeProfile.BUILTIN_VERSION] 保持一致。 */
    const val BUILTIN_VERSION = "0.1.0"
    const val PACK_RUNTIME_PREFIX = "pack."

    fun isBuiltinCatalog(catalogId: String): Boolean =
        catalogId == BUILTIN_CATALOG_ID ||
            catalogId == BUILTIN_RUNTIME_ID ||
            catalogId.startsWith("builtin-")

    fun runtimeIdForCatalog(catalogId: String): String =
        if (isBuiltinCatalog(catalogId)) BUILTIN_RUNTIME_ID else "$PACK_RUNTIME_PREFIX$catalogId"

    fun catalogIdFromRuntime(runtimeId: String): String =
        when {
            runtimeId == BUILTIN_RUNTIME_ID -> BUILTIN_CATALOG_ID
            runtimeId.startsWith(PACK_RUNTIME_PREFIX) -> runtimeId.removePrefix(PACK_RUNTIME_PREFIX)
            else -> runtimeId
        }
}

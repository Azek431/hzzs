package top.azek431.hzzs.core.algorithm

/**
 * 算法包 CatalogId 与运行时 algorithmId 的统一约定。
 *
 * 路由规则：
 * - 内置算法通过 [ROUTING] 表显式映射 catalogId → runtimeId；
 *   [isBuiltinCatalog] 只判断是否内置，不再用 `startsWith("builtin-")` 坍缩。
 * - 外装包走 `pack.<catalogId>` 前缀。
 *
 * 内置算法家族：
 * - builtin-hzzs-base-*        → builtin.hzzs.base（三赛季通用引擎）
 * - builtin-hzzs-native-vision-* → builtin.hzzs.native_vision（HZZS Native Vision 1.0）
 */
object AlgorithmIds {
    /** 基础内置包：三赛季通用引擎。 */
    const val BUILTIN_CATALOG_ID = "builtin-hzzs-base-0.1.0"
    const val BUILTIN_RUNTIME_ID = "builtin.hzzs.base"
    /** Native Vision 内置包：海盐客厅走 SeaSaltSparseDetector。 */
    const val NATIVE_VISION_CATALOG_ID = "builtin-hzzs-native-vision-1.0.0"
    const val NATIVE_VISION_RUNTIME_ID = "builtin.hzzs.native_vision"

    const val BUILTIN_VERSION = "0.1.0"
    const val PACK_RUNTIME_PREFIX = "pack."

    /** 显式路由表：catalogId → runtimeId。内置包必须在此注册。 */
    private val ROUTING = mapOf(
        BUILTIN_CATALOG_ID to BUILTIN_RUNTIME_ID,
        NATIVE_VISION_CATALOG_ID to NATIVE_VISION_RUNTIME_ID,
    )

    fun isBuiltinCatalog(catalogId: String): Boolean = ROUTING.containsKey(catalogId)

    fun runtimeIdForCatalog(catalogId: String): String =
        ROUTING[catalogId] ?: "$PACK_RUNTIME_PREFIX$catalogId"

    fun catalogIdFromRuntime(runtimeId: String): String =
        when (runtimeId) {
            BUILTIN_RUNTIME_ID -> BUILTIN_CATALOG_ID
            NATIVE_VISION_RUNTIME_ID -> NATIVE_VISION_CATALOG_ID
            else -> runtimeId.removePrefix(PACK_RUNTIME_PREFIX)
        }
}

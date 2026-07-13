// 火崽崽助手（HZZS）功能标志管理。
//
// 所有持久化的用户偏好设置集中在此管理。
// 使用 SharedPreferences 存储，Key 集中定义在此类中。
//
// 设计原因：
// - 避免 SharedPreferences 的 key 散落在各个 Activity/Manager 中
// - 提供统一的 getter/setter，便于未来迁移到其他存储方案
// - 默认值集中定义，避免魔法数字

package top.azek431.hzzs.core.util

import android.content.Context

object FeatureFlags {

    // === SharedPreferences 文件名 ===
    private const val PREFS_NAME = "hzzs_feature_flags"

    // === 键名常量 ===
    const val KEY_DISCLAIMER_ACCEPTED = "disclaimer_accepted"
    const val KEY_AUTO_OPERATION_ENABLED = "auto_operation_enabled"
    const val KEY_AUTO_OPERATION_DELAY_MS = "auto_operation_delay_ms"
    const val KEY_LAST_VERSION_CODE = "last_version_code"
    private const val KEY_AUTO_OPERATION_SAFETY_V1 = "auto_operation_safety_v1"

    // === 默认值 ===
    const val DEFAULT_AUTO_OPERATION_DELAY_MS = 100

    // === 工具方法 ===
    private fun getPrefs(ctx: Context) = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // --- 免责声明 ---

    /** 检查用户是否已同意免责声明 */
    fun isDisclaimerAccepted(ctx: Context): Boolean =
        getPrefs(ctx).getBoolean(KEY_DISCLAIMER_ACCEPTED, false)

    /** 记录用户已同意免责声明 */
    fun setDisclaimerAccepted(ctx: Context, accepted: Boolean) {
        getPrefs(ctx).edit().putBoolean(KEY_DISCLAIMER_ACCEPTED, accepted).apply()
    }

    // --- 自动操作开关 ---

    /** 检查自动操作是否启用 */
    fun isAutoOperationEnabled(ctx: Context): Boolean {
        val prefs = getPrefs(ctx)
        if (!prefs.getBoolean(KEY_AUTO_OPERATION_SAFETY_V1, false)) {
            // 历史版本可能在没有明确操作的情况下留下开启值；升级后安全关闭一次。
            prefs.edit()
                .putBoolean(KEY_AUTO_OPERATION_ENABLED, false)
                .putBoolean(KEY_AUTO_OPERATION_SAFETY_V1, true)
                .apply()
            return false
        }
        return prefs.getBoolean(KEY_AUTO_OPERATION_ENABLED, false)
    }

    /** 设置自动操作开关 */
    fun setAutoOperationEnabled(ctx: Context, enabled: Boolean) {
        getPrefs(ctx).edit()
            .putBoolean(KEY_AUTO_OPERATION_ENABLED, enabled)
            .putBoolean(KEY_AUTO_OPERATION_SAFETY_V1, true)
            .apply()
    }

    // --- 自动操作延迟 ---

    /** 获取自动操作延迟（毫秒） */
    fun getAutoOperationDelayMs(ctx: Context): Int =
        getPrefs(ctx).getInt(KEY_AUTO_OPERATION_DELAY_MS, DEFAULT_AUTO_OPERATION_DELAY_MS)

    /** 设置自动操作延迟（毫秒） */
    fun setAutoOperationDelayMs(ctx: Context, delayMs: Int) {
        getPrefs(ctx).edit().putInt(KEY_AUTO_OPERATION_DELAY_MS, delayMs).apply()
    }
}

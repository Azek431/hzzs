package top.azek431.hzzs.runtime.capture

import android.content.Context
import android.graphics.RectF

object CapturePreferences {
    private const val PREFS = "hzzs_runtime_v2"
    private const val KEY_MODE = "capture_mode"
    private const val KEY_INITIALIZED = "first_run_optimized"
    private const val KEY_SAFETY_DEFAULTS_V1 = "safety_defaults_v1"
    private const val KEY_AUTO_ACTION = "auto_action"
    private const val KEY_DRAW = "draw_overlay"
    private const val KEY_DETAILED = "detailed_overlay"
    private const val KEY_VIEWPORT = "viewport"

    /**
     * 首次启动保存 AUTO，而不是把当时尚未授权的能力永久固定成 MediaProjection。
     * AUTO 每次运行都会按“Android 11+ 无障碍截图 → MediaProjection”动态选择；Root 永不自动启用。
     */
    fun ensureOptimizedDefaults(context: Context): CaptureMode {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        var changed = false
        if (!prefs.getBoolean(KEY_INITIALIZED, false)) {
            editor
                .putString(KEY_MODE, CaptureMode.AUTO.name)
                .putBoolean(KEY_DRAW, true)
                .putBoolean(KEY_DETAILED, true)
                .putBoolean(KEY_INITIALIZED, true)
            changed = true
        }

        // 安全迁移：历史版本曾把自动操作默认设为开启。升级后强制关闭一次，
        // 后续只有用户在设置页主动开启才会恢复，避免旧默认值继续产生触摸注入。
        if (!prefs.getBoolean(KEY_SAFETY_DEFAULTS_V1, false)) {
            editor
                .putBoolean(KEY_AUTO_ACTION, false)
                .putBoolean(KEY_SAFETY_DEFAULTS_V1, true)
            changed = true
        }

        if (changed) editor.apply()
        return mode(context)
    }

    fun mode(context: Context): CaptureMode = runCatching {
        val value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_MODE, CaptureMode.AUTO.name)
        CaptureMode.valueOf(value ?: CaptureMode.AUTO.name)
    }.getOrDefault(CaptureMode.AUTO)

    fun setMode(context: Context, mode: CaptureMode) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_MODE, mode.name).apply()

    fun autoAction(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_AUTO_ACTION, false)

    fun setAutoAction(context: Context, value: Boolean) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_AUTO_ACTION, value)
            .putBoolean(KEY_SAFETY_DEFAULTS_V1, true)
            .apply()

    fun draw(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_DRAW, true)

    fun setDraw(context: Context, value: Boolean) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_DRAW, value).apply()

    fun detailed(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_DETAILED, true)

    fun setDetailed(context: Context, value: Boolean) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_DETAILED, value).apply()

    fun getViewport(context: Context): RectF {
        val text = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_VIEWPORT, "0,0,1,1") ?: "0,0,1,1"
        val values = text.split(',').mapNotNull { it.toFloatOrNull() }
        return if (
            values.size == 4 &&
            values[0] in 0f..1f && values[1] in 0f..1f &&
            values[2] in 0f..1f && values[3] in 0f..1f &&
            values[2] > values[0] && values[3] > values[1]
        ) {
            RectF(values[0], values[1], values[2], values[3])
        } else {
            RectF(0f, 0f, 1f, 1f)
        }
    }

    fun setViewport(context: Context, rect: RectF) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_VIEWPORT, "${rect.left},${rect.top},${rect.right},${rect.bottom}")
            .apply()

    fun resetViewport(context: Context) = setViewport(context, RectF(0f, 0f, 1f, 1f))
}

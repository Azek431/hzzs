package top.azek431.hzzs.runtime.capture

import android.content.Context
import android.graphics.RectF
import top.azek431.hzzs.runtime.vision.VisionAlgorithm

object CapturePreferences {
    private const val PREFS = "hzzs_runtime_v2"
    private const val KEY_MODE = "capture_mode"
    private const val KEY_VISION_ALGORITHM = "vision_algorithm"
    private const val KEY_INITIALIZED = "first_run_optimized"
    private const val KEY_SAFETY_DEFAULTS_V1 = "safety_defaults_v1"
    private const val KEY_BAMBOO_SAFETY_V1 = "bamboo_safety_v1"
    private const val KEY_AUTO_ACTION = "auto_action"
    private const val KEY_BAMBOO_EXPERIMENTAL_AUTO = "bamboo_experimental_auto"
    private const val KEY_DRAW = "draw_overlay"
    private const val KEY_DETAILED = "detailed_overlay"
    private const val KEY_VIEWPORT = "viewport"

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
        if (!prefs.contains(KEY_VISION_ALGORITHM)) {
            editor.putString(KEY_VISION_ALGORITHM, VisionAlgorithm.DEFAULT.preferenceValue)
            changed = true
        }
        if (!prefs.getBoolean(KEY_SAFETY_DEFAULTS_V1, false)) {
            editor
                .putBoolean(KEY_AUTO_ACTION, false)
                .putBoolean(KEY_SAFETY_DEFAULTS_V1, true)
            changed = true
        }
        // 竹影书屋首次升级后必须重新手动确认实验自动操作。
        if (!prefs.getBoolean(KEY_BAMBOO_SAFETY_V1, false)) {
            editor
                .putBoolean(KEY_BAMBOO_EXPERIMENTAL_AUTO, false)
                .putBoolean(KEY_BAMBOO_SAFETY_V1, true)
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
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_MODE, mode.name)
            .apply()

    fun algorithm(context: Context): VisionAlgorithm = VisionAlgorithm.fromPreference(
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_VISION_ALGORITHM, VisionAlgorithm.DEFAULT.preferenceValue),
    )

    fun setAlgorithm(context: Context, algorithm: VisionAlgorithm) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_VISION_ALGORITHM, algorithm.preferenceValue)
            .apply()

    fun autoAction(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_AUTO_ACTION, false)

    fun setAutoAction(context: Context, value: Boolean) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_AUTO_ACTION, value)
            .putBoolean(KEY_SAFETY_DEFAULTS_V1, true)
            .apply()

    fun bambooExperimentalAutoAction(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_BAMBOO_EXPERIMENTAL_AUTO, false)

    fun setBambooExperimentalAutoAction(context: Context, value: Boolean) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_BAMBOO_EXPERIMENTAL_AUTO, value)
            .putBoolean(KEY_BAMBOO_SAFETY_V1, true)
            .apply()

    fun actionAllowedByAlgorithm(context: Context, algorithm: VisionAlgorithm = algorithm(context)): Boolean =
        algorithm.automaticActionCalibrated || bambooExperimentalAutoAction(context)

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
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_VIEWPORT, "${rect.left},${rect.top},${rect.right},${rect.bottom}")
            .apply()

    fun resetViewport(context: Context) = setViewport(context, RectF(0f, 0f, 1f, 1f))
}

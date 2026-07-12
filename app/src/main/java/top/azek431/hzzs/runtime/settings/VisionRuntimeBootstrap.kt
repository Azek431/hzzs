package top.azek431.hzzs.runtime.settings

import android.app.Activity
import android.content.Context
import android.content.Intent
import top.azek431.hzzs.runtime.capture.CapturePreferences

object VisionRuntimeBootstrap {
    private const val PREFS = "hzzs_runtime_v2"
    private const val KEY_WIZARD_SHOWN = "wizard_shown"
    fun initialize(context: Context) { CapturePreferences.ensureOptimizedDefaults(context.applicationContext) }
    fun maybeLaunchFirstRun(activity: Activity) {
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_WIZARD_SHOWN, false)) {
            prefs.edit().putBoolean(KEY_WIZARD_SHOWN, true).apply()
            activity.startActivity(Intent(activity, VisionRuntimeSettingsActivity::class.java).putExtra("first_run", true))
        }
    }
}

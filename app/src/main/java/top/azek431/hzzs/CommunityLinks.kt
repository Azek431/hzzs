package top.azek431.hzzs

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import android.widget.Toast

/**
 * HZZS 社区与开发者频道信息。
 *
 * QQ 群：HZZS 项目专属交流。
 * Telegram：Azek431 主频道，分享 HZZS 与更多项目动态。
 *
 * 当前只复制信息，不自动打开第三方应用，不联网，不收集用户数据。
 */
object CommunityLinks {

    const val HZZS_QQ_GROUP_ID = "130330601"
    const val AZEK_MAIN_TELEGRAM_CHANNEL = "@AzekMain"

    private const val TAG = "HZZS"

    fun copy(
        context: Context,
        label: String,
        value: String,
        confirmation: String,
    ) {
        val appContext = context.applicationContext

        try {
            val clipboard = appContext.getSystemService(
                ClipboardManager::class.java,
            )

            if (clipboard == null) {
                Log.w(TAG, "[Community] ClipboardManager is unavailable.")

                Toast.makeText(
                    appContext,
                    appContext.getString(R.string.community_copy_failed),
                    Toast.LENGTH_SHORT,
                ).show()

                return
            }

            clipboard.setPrimaryClip(
                ClipData.newPlainText(label, value),
            )

            Log.i(TAG, "[Community] copied $label.")

            Toast.makeText(
                appContext,
                confirmation,
                Toast.LENGTH_SHORT,
            ).show()
        } catch (error: Exception) {
            Log.e(TAG, "[Community] unable to copy $label.", error)

            Toast.makeText(
                appContext,
                appContext.getString(R.string.community_copy_failed),
                Toast.LENGTH_SHORT,
            ).show()
        }
    }
}

package top.azek431.hzzs

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast

/**
 * HZZS 社区与开发动态入口。
 *
 * QQ 群仅用于火崽崽助手项目交流、测试与反馈。
 * Telegram 为 Azek431 主频道，会同步 HZZS 与更多项目动态。
 */
object CommunityLinks {

    const val HZZS_QQ_GROUP_URL = "https://qm.qq.com/q/5T5fjwRgVq"
    const val AZEK_MAIN_TELEGRAM_URL = "https://t.me/AzekMain"

    private const val TAG = "HZZS"

    fun openLink(
        context: Context,
        label: String,
        url: String,
        fallbackMessage: String,
    ) {
        val appContext = context.applicationContext

        try {
            val intent = Intent(
                Intent.ACTION_VIEW,
                Uri.parse(url),
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            appContext.startActivity(intent)

            Log.i(TAG, "[Community] opened $label.")
        } catch (error: ActivityNotFoundException) {
            Log.w(TAG, "[Community] no app can open $label.", error)

            copyLink(
                context = appContext,
                label = label,
                url = url,
                confirmation = fallbackMessage,
            )
        } catch (error: Exception) {
            Log.e(TAG, "[Community] unable to open $label.", error)

            copyLink(
                context = appContext,
                label = label,
                url = url,
                confirmation = fallbackMessage,
            )
        }
    }

    private fun copyLink(
        context: Context,
        label: String,
        url: String,
        confirmation: String,
    ) {
        try {
            val clipboard = context.getSystemService(
                ClipboardManager::class.java,
            )

            if (clipboard == null) {
                Toast.makeText(
                    context,
                    context.getString(R.string.community_open_failed),
                    Toast.LENGTH_SHORT,
                ).show()

                return
            }

            clipboard.setPrimaryClip(
                ClipData.newPlainText(label, url),
            )

            Toast.makeText(
                context,
                confirmation,
                Toast.LENGTH_SHORT,
            ).show()

            Log.i(TAG, "[Community] copied fallback link for $label.")
        } catch (error: Exception) {
            Log.e(TAG, "[Community] unable to copy fallback link.", error)

            Toast.makeText(
                context,
                context.getString(R.string.community_open_failed),
                Toast.LENGTH_SHORT,
            ).show()
        }
    }
}

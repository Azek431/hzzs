// 火崽崽助手（HZZS）社区与开发者动态入口。
//
// 枚举类，封装外部社区链接的打开逻辑：
// 1. HZZS QQ 交流群 — 用于项目交流、测试反馈和问题讨论
// 2. Azek431 Telegram 主频道 — 同步 HZZS 及其他独立项目的开发动态

package top.azek431.hzzs.ui.community

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast
import top.azek431.hzzs.R

enum class CommunityLinks(
    val labelRes: Int,
    val url: String,
) {
    HZZS_QQ_GROUP(R.string.community_qq_label, "https://qm.qq.com/q/5T5fjwRgVq"),
    AZEK_MAIN_TELEGRAM(R.string.community_telegram_label, "https://t.me/AzekMain");

    companion object {
        private const val TAG = "HZZS"

        fun openLink(
            context: Context,
            label: String,
            url: String,
            fallbackMessage: String,
        ) {
            val appContext = context.applicationContext
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                appContext.startActivity(intent)
                Log.i(TAG, "[Community] opened $label.")
            } catch (error: ActivityNotFoundException) {
                Log.w(TAG, "[Community] no app can open $label.", error)
                copyLink(appContext, label, url, fallbackMessage)
            } catch (error: Exception) {
                Log.e(TAG, "[Community] unable to open $label.", error)
                copyLink(appContext, label, url, fallbackMessage)
            }
        }

        private fun copyLink(
            context: Context,
            label: String,
            url: String,
            confirmation: String,
        ) {
            try {
                val clipboard = context.getSystemService(ClipboardManager::class.java)
                clipboard.setPrimaryClip(ClipData.newPlainText(label, url))
                Toast.makeText(context, confirmation, Toast.LENGTH_SHORT).show()
                Log.i(TAG, "[Community] copied fallback link for $label.")
            } catch (error: Exception) {
                Log.e(TAG, "[Community] unable to copy fallback link.", error)
                Toast.makeText(
                    context,
                    context.getString(R.string.community_copy_link_failed, url),
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }
}

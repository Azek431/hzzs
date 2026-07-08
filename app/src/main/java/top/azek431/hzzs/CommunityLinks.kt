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
 * 火崽崽助手（HZZS）社区与开发者动态入口。
 *
 * 这是一个枚举类（enum class），封装了两个外部社区链接的打开逻辑：
 * 1. HZZS QQ 交流群 — 用于项目交流、测试反馈和问题讨论
 * 2. Azek431 Telegram 主频道 — 同步 HZZS 及其他独立项目的开发动态
 *
 * 设计特点：
 * - 使用 Intent.ACTION_VIEW 在系统浏览器中打开链接
 * - 如果设备上没有可用的浏览器（ActivityNotFoundException），
 *   则自动将链接复制到剪贴板，并通过 Toast 提示用户
 * - 所有操作均使用 ApplicationContext，避免内存泄漏
 * - 通过 Log 记录每次打开/复制操作，便于调试
 * - 通过 entries 集合支持数据驱动的批量绑定（如首页和社区链接的点击事件）
 *
 * 扩展方式：新增社区链接时，只需在枚举值列表中追加一项即可。
 */
enum class CommunityLinks(
    val labelRes: Int,       // 标签字符串资源 ID
    val url: String,         // 社区链接 URL
) {
    /** HZZS 官方 QQ 交流群 */
    HZZS_QQ_GROUP(R.string.community_qq_label, "https://qm.qq.com/q/5T5fjwRgVq"),

    /** Azek431 个人 Telegram 主频道 */
    AZEK_MAIN_TELEGRAM(R.string.community_telegram_label, "https://t.me/AzekMain");

    companion object {
        /** 日志标签，用于区分 HZZS 各模块的日志输出 */
        private const val TAG = "HZZS"

        /**
         * 尝试在浏览器中打开指定链接，失败时回退到复制链接到剪贴板。
         *
         * @param context 上下文（可以是 Activity 或 Application）
         * @param label 链接的标签名称，用于日志记录和剪贴板标识
         * @param url 要打开的完整 URL
         * @param fallbackMessage 当链接打开失败时，Toast 显示的提示信息
         */
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
                copyLink(appContext, label, url, fallbackMessage)
            } catch (error: Exception) {
                Log.e(TAG, "[Community] unable to open $label.", error)
                copyLink(appContext, label, url, fallbackMessage)
            }
        }

        /**
         * 将链接复制到系统剪贴板，并显示确认 Toast。
         *
         * @param context 上下文
         * @param label 链接标签
         * @param url 要复制的完整 URL
         * @param confirmation Toast 显示的确认消息
         */
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

// 火崽崽助手（HZZS）社区与开发者动态入口。
//
// 枚举类，封装外部社区链接的打开逻辑：
// 1. HZZS QQ 交流群 — 用于项目交流、测试反馈和问题讨论
// 2. Azek431 Telegram 主频道 — 同步 HZZS 及其他独立项目的开发动态
//
// 打开策略：
// - 优先尝试通过 Intent.ACTION_VIEW 打开系统浏览器/App
// - 如果找不到能处理该 URL 的应用（ActivityNotFoundException），
//   则将链接复制到剪贴板并弹出 Toast 提示
// - 如果发生其他异常（如 URI 解析失败），同样走剪贴板降级路径
//
// 设计原因：
// - QQ 群链接在部分设备上可能没有浏览器支持，需要剪贴板兜底
// - Telegram 链接通常需要 Telegram App，但也可通过浏览器打开
// - 统一使用 applicationContext 避免内存泄漏

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
    /** HZZS 官方 QQ 交流群 */
    HZZS_QQ_GROUP(R.string.community_qq_label, "https://qm.qq.com/q/5T5fjwRgVq"),
    /** Azek431 主频道（Telegram） */
    AZEK_MAIN_TELEGRAM(R.string.community_telegram_label, "https://t.me/AzekMain");

    companion object {
        private const val TAG = "HZZS"

        /**
         * 打开外部社区链接。
         *
         * 打开策略：
         * 1. 尝试用 Intent.ACTION_VIEW 打开系统浏览器或对应 App
         * 2. 如果找不到能处理 URL 的应用 → 复制到剪贴板 + Toast 提示
         * 3. 如果发生其他异常 → 同样走剪贴板降级路径
         *
         * @param context 上下文
         * @param label 链接标签名（用于日志和剪贴板）
         * @param url 要打开的 URL
         * @param fallbackMessage 剪贴板复制成功时的 Toast 提示文本
         */
        fun openLink(
            context: Context,
            label: String,
            url: String,
            fallbackMessage: String,
        ) {
            val appContext = context.applicationContext
            try {
                // 构造 ACTION_VIEW intent，添加 NEW_TASK 标志以支持非 Activity 上下文
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                appContext.startActivity(intent)
                Log.i(TAG, "[Community] opened $label.")
            } catch (error: ActivityNotFoundException) {
                // 没有 App 能打开此 URL（如未安装 QQ/Telegram），走剪贴板降级
                Log.w(TAG, "[Community] no app can open $label.", error)
                copyLink(appContext, label, url, fallbackMessage)
            } catch (error: Exception) {
                // 其他异常（URI 解析失败等），同样走剪贴板降级
                Log.e(TAG, "[Community] unable to open $label.", error)
                copyLink(appContext, label, url, fallbackMessage)
            }
        }

        /**
         * 将链接复制到剪贴板并弹出 Toast 确认。
         *
         * 当无法通过 Intent 打开 URL 时的降级方案：
         * 1. 通过 ClipboardManager 将 URL 复制到系统剪贴板
         * 2. 弹出 Toast 提示用户手动粘贴打开
         * 3. 如果复制失败，显示包含 URL 的完整错误信息
         *
         * @param context 上下文
         * @param label 链接标签名（用作剪贴板标签）
         * @param url 要复制的 URL
         * @param confirmation 复制成功时的 Toast 提示文本
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

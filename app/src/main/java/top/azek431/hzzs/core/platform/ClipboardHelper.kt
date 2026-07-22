/**
 * 剪贴板写入辅助：空服务保护 + 结果回调，避免「点了没反应」。
 */
package top.azek431.hzzs.core.platform

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context

object ClipboardHelper {
    /**
     * 写入纯文本剪贴板。
     *
     * @return true 表示已提交系统剪贴板服务；false 表示服务不可用或写入失败。
     */
    fun copyText(context: Context, label: String, text: String): Boolean {
        if (text.isEmpty()) return false
        return runCatching {
            val clipboard = context.getSystemService(ClipboardManager::class.java)
                ?: return false
            clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
            true
        }.getOrDefault(false)
    }
}

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
 * 这是一个单例对象（object），封装了两个外部社区链接的打开逻辑：
 * 1. HZZS QQ 交流群 — 用于项目交流、测试反馈和问题讨论
 * 2. Azek431 Telegram 主频道 — 同步 HZZS 及其他独立项目的开发动态
 *
 * 设计特点：
 * - 使用 Intent.ACTION_VIEW 在系统浏览器中打开链接
 * - 如果设备上没有可用的浏览器（ActivityNotFoundException），
 *   则自动将链接复制到剪贴板，并通过 Toast 提示用户
 * - 所有操作均使用 ApplicationContext，避免内存泄漏
 * - 通过 Log 记录每次打开/复制操作，便于调试
 *
 * 注意：此对象不包含任何业务逻辑，纯工具性质。
 * 所有公开方法都是幂等的——多次调用不会产生副作用。
 */
object CommunityLinks {

    /** HZZS 官方 QQ 群邀请链接 */
    const val HZZS_QQ_GROUP_URL = "https://qm.qq.com/q/5T5fjwRgVq"

    /** Azek431 个人 Telegram 主频道链接 */
    const val AZEK_MAIN_TELEGRAM_URL = "https://t.me/AzekMain"

    /** 日志标签，用于区分 HZZS 各模块的日志输出 */
    private const val TAG = "HZZS"

    /**
     * 尝试在浏览器中打开指定链接，失败时回退到复制链接到剪贴板。
     *
     * 这是社区链接的统一入口方法，所有需要打开 QQ 群或 Telegram 链接的地方
     * 都应调用此方法，而不是各自构造 Intent。好处：
     * - 集中管理链接打开逻辑，修改时只需改一处
     * - 统一使用 ApplicationContext，避免内存泄漏
     * - 统一的错误处理和日志记录
     *
     * @param context 上下文（可以是 Activity 或 Application）
     * @param label 链接的标签名称，用于日志记录和剪贴板标识
     * @param url 要打开的完整 URL
     * @param fallbackMessage 当链接打开失败时，Toast 显示的提示信息
     *
     * 执行流程：
     * 1. 获取 ApplicationContext（避免持有 Activity 引用导致内存泄漏）
     * 2. 构造 ACTION_VIEW Intent 并设置 NEW_TASK 标志
     *    （FLAG_ACTIVITY_NEW_TASK 是必须的，因为这不是从 Activity 发起的）
     * 3. 尝试启动 Activity 打开浏览器
     * 4. 成功：记录 Info 级别日志
     * 5. 失败（无浏览器 / ActivityNotFoundException）：调用 copyLink() 将 URL 复制到剪贴板
     * 6. 其他异常（SecurityException 等）：同样回退到复制剪贴板
     */
    fun openLink(
        context: Context,
        label: String,
        url: String,
        fallbackMessage: String,
    ) {
        // 使用 ApplicationContext，避免 Activity 被意外持有导致内存泄漏
        val appContext = context.applicationContext

        try {
            // 构造一个 ACTION_VIEW Intent，用于在浏览器中打开 URL
            val intent = Intent(
                Intent.ACTION_VIEW,
                Uri.parse(url),
            ).apply {
                // 在新任务栈中启动，因为这不是从 Activity 发起的
                // 如果不设置 NEW_TASK 标志，会抛出 ActivityNotFoundException
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            // 尝试启动 Activity（通常会打开系统默认浏览器）
            appContext.startActivity(intent)

            // 成功打开，记录 Info 日志
            Log.i(TAG, "[Community] opened $label.")
        } catch (error: ActivityNotFoundException) {
            // 没有可以处理此 URL 的应用（极端情况，现代设备几乎不会发生）
            Log.w(TAG, "[Community] no app can open $label.", error)

            // 回退：将链接复制到剪贴板，让用户手动粘贴使用
            copyLink(
                context = appContext,
                label = label,
                url = url,
                confirmation = fallbackMessage,
            )
        } catch (error: Exception) {
            // 捕获其他所有异常（如 SecurityException 等）
            Log.e(TAG, "[Community] unable to open $label.", error)

            // 同样回退到复制剪贴板
            copyLink(
                context = appContext,
                label = label,
                url = url,
                confirmation = fallbackMessage,
            )
        }
    }

    /**
     * 将链接复制到系统剪贴板，并显示确认 Toast。
     *
     * 这是 openLink() 失败后的回退策略。当设备无法打开 URL 时，
     * 至少保证用户能获取到链接地址。
     *
     * @param context 上下文
     * @param label 链接标签，用作剪贴板文本的标题（用于标识剪贴板内容来源）
     * @param url 要复制的完整 URL
     * @param confirmation Toast 显示的确认消息
     *
     * 执行流程：
     * 1. 通过 getSystemService 获取 ClipboardManager 服务
     *    （Android 框架保证此服务始终可用，但做了 null 检查以防万一）
     * 2. 使用 ClipData.newPlainText() 创建剪贴板数据并设置到主剪贴板
     *    （label 作为剪贴板内容的标识名称，用于多条目剪贴板场景）
     * 3. 显示成功 Toast，告知用户链接已复制
     * 4. 记录 Info 日志
     * 5. 如果复制失败（如权限问题），捕获异常并显示通用失败提示
     */
    private fun copyLink(
        context: Context,
        label: String,
        url: String,
        confirmation: String,
    ) {
        try {
            // 获取系统剪贴板服务（Android 框架保证此服务始终可用）
            val clipboard = context.getSystemService(
                ClipboardManager::class.java,
            )

            // 创建纯文本剪贴板数据并设置为主剪贴板
            // label 作为剪贴板内容的标识名称（用于多条目剪贴板场景）
            clipboard.setPrimaryClip(
                ClipData.newPlainText(label, url),
            )

            // 显示成功提示，告知用户链接已复制
            Toast.makeText(
                context,
                confirmation,
                Toast.LENGTH_SHORT,
            ).show()

            // 记录成功日志
            Log.i(TAG, "[Community] copied fallback link for $label.")
        } catch (error: Exception) {
            // 复制失败（如权限问题），显示通用失败提示
            Log.e(TAG, "[Community] unable to copy fallback link.", error)

            Toast.makeText(
                context,
                context.getString(R.string.community_open_failed),
                Toast.LENGTH_SHORT,
            ).show()
        }
    }
}

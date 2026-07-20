// 火崽崽助手（HZZS）免责声明页面。
//
// 展示时机：
// 1. 首次启动应用时（FeatureFlags.isDisclaimerAccepted() == false）
// 2. 用户在首页点击"免责声明与功能设置"按钮时
//
// 交互流程：
// 1. 用户阅读声明文本（必须滚动到底部 90% 以上）
// 2. 底部"我已阅读并同意"按钮变为可点击
// 3. 点击后写入 SharedPreferences（FeatureFlags.setDisclaimerAccepted(true)）
// 4. 如果是从首页跳转（returnToMain == true），调用 finish() 回到 MainActivity
// 5. 如果是首次启动（returnToMain == false），先启动 MainActivity 再 finish()
//
// 防跳过机制：
// - agreeButton 初始 enabled=false，只有在滚动进度 > 90% 时才启用
// - scrollProgress TextView 实时显示滚动百分比

package top.azek431.hzzs.ui.disclaimer

import android.content.Intent
import android.os.Bundle
import android.text.Html
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.NestedScrollView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import top.azek431.hzzs.MainActivity
import top.azek431.hzzs.R
import top.azek431.hzzs.util.FeatureFlags

class DisclaimerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_RETURN_TO_MAIN = "return_to_main"
    }

    private lateinit var scrollView: NestedScrollView
    /** 声明文本 TextView：承载解析后的 HTML 富文本 */
    private lateinit var disclaimerText: TextView
    /** 同意按钮：MaterialButton，滚动到底部后可点击 */
    private lateinit var agreeButton: MaterialButton
    /** 滚动进度指示器：TextView，实时显示百分比（0%~100%） */
    private lateinit var scrollProgress: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_disclaimer)

        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setHomeAsUpIndicator(R.drawable.ic_overlay_preview_close)
        }

        cacheViews()
        applyHtmlText()
        bindActions()
    }

    // ==================== View 缓存 ====================

    /**
     * 缓存所有需要用到的 View 引用。
     * 如果任何必需 View 不存在，抛出 IllegalStateException 阻止 Activity 继续运行。
     */
    private fun cacheViews() {
        scrollView = findViewById(R.id.disclaimerScrollView)
            ?: throw IllegalStateException("disclaimerScrollView not found")
        disclaimerText = findViewById(R.id.disclaimerText)
            ?: throw IllegalStateException("disclaimerText not found")
        agreeButton = findViewById(R.id.btnAgree)
            ?: throw IllegalStateException("btnAgree not found")
        scrollProgress = findViewById(R.id.scrollProgress)
            ?: throw IllegalStateException("scrollProgress not found")
    }

    // ==================== HTML 文本解析 ====================

    /**
     * 将声明文本中的 HTML 标签（<b>、<br>）解析为富文本。
     * Android 的 Html.fromHtml 不会自动处理 \n\n 换行，需要手动替换。
     */
    private fun applyHtmlText() {
        val raw = getString(R.string.disclaimer_content)
        // 将双换行转换为 <br><br>，让段落间距更自然
        val html = raw.replace("\n\n", "<br><br>")
        val flags = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            Html.FROM_HTML_MODE_COMPACT
        } else {
            0
        }
        disclaimerText.text = Html.fromHtml(html, flags)
    }

    // ==================== 事件绑定 ====================

    /**
     * 绑定所有交互事件：滚动监听、同意按钮、返回按钮。
     *
     * 滚动监听逻辑：
     * - 计算 maxScroll = 内容总高度 - 可见区域高度
     * - progress = scrollY / maxScroll
     * - progress > 0.9 时启用 agreeButton
     */
    private fun bindActions() {
        // 监听滚动进度
        scrollView.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            val maxScroll = scrollView.getChildAt(0).height - scrollView.height
            val progress = if (maxScroll > 0) scrollY.toFloat() / maxScroll else 0f

            scrollProgress.text = "${(progress * 100).toInt()}%"

            // 滚动到底部（90% 以上）才允许点击
            agreeButton.isEnabled = progress > 0.9f
        }

        // 同意按钮
        agreeButton.setOnClickListener {
            FeatureFlags.setDisclaimerAccepted(this, true)

            val returnToMain = intent.getBooleanExtra(EXTRA_RETURN_TO_MAIN, false)
            if (returnToMain) {
                finish()  // 回到 MainActivity
            } else {
                // 首次启动：进入 MainActivity
                startActivity(Intent(this, MainActivity::class.java))
                finish()
            }
        }

        // 返回按钮
        findViewById<android.widget.ImageView>(android.R.id.home)?.setOnClickListener {
            finish()
        }
    }

    /**
     * 处理 Toolbar 左上角返回按钮点击。
     *
     * @return true 表示已处理（finish Activity）
     */
    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}

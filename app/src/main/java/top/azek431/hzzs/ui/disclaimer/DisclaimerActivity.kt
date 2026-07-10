// 火崽崽助手（HZZS）免责声明页面（优化版）。
//
// 展示时机：
// 1. 首次启动应用时（FeatureFlags.isDisclaimerAccepted() == false）→ 阻塞启动
// 2. 用户在首页点击"免责声明"按钮时（returnToMain == true）
//
// 交互流程：
// 1. 用户阅读声明文本（必须滚动到底部 80% 以上）
// 2. 底部"我已阅读并同意"按钮变为可点击
// 3. 点击同意 → 写入 SharedPreferences + 进入 MainActivity（或 finish）
// 4. 点击不同意 → 确认后彻底退出应用
//
// 防跳过机制：
// - agreeButton 初始 enabled=false，只有在滚动进度 >= 80% 时才启用
// - 未启用时点击按钮会提示"请滚动阅读完整声明"
// - scrollProgress TextView + 进度条 View 实时显示滚动百分比
//
// 线程模型：
// - 所有 UI 操作在主线程执行
// - 日期生成在主线程 onCreate 中执行（轻量操作）

package top.azek431.hzzs.ui.disclaimer

import android.content.Intent
import android.os.Bundle
import android.text.Spanned
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.text.HtmlCompat
import androidx.core.widget.NestedScrollView
import com.google.android.material.button.MaterialButton
import top.azek431.hzzs.R
import top.azek431.hzzs.core.util.FeatureFlags
import java.text.SimpleDateFormat
import java.util.Locale

class DisclaimerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_RETURN_TO_MAIN = "return_to_main"

        /** 滚动阈值：80% 时启用同意按钮 */
        private const val SCROLL_THRESHOLD = 0.8f

        /** 日期格式：yyyy 年 M 月 d 日 */
        private const val DATE_FORMAT_PATTERN = "yyyy 年 M 月 d 日"
    }

    // ==================== View 引用 ====================

    /** 滚动容器 */
    private lateinit var scrollView: NestedScrollView

    /** 声明标题 TextView（品牌色大字） */
    private lateinit var disclaimerHeading: TextView

    /** 声明正文 TextView：承载解析后的 HTML 富文本 */
    private lateinit var disclaimerText: TextView

    /** 更新日期 TextView */
    private lateinit var disclaimerDate: TextView

    /** 同意按钮 */
    private lateinit var agreeButton: MaterialButton

    /** 不同意按钮 */
    private lateinit var disagreeButton: MaterialButton

    /** 进度条填充 View */
    private lateinit var progressFill: View

    /** 百分比显示 TextView */
    private lateinit var scrollProgress: TextView

    /** 上次滚动进度（用于进度条防抖） */
    private var lastProgressPercent = -1

    // ==================== 生命周期 ====================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_disclaimer)

        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            // 品牌色标题栏不需要自定义返回图标
        }

        cacheViews()
        applyDynamicDate()
        applyEnhancedHtmlText()
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
        disclaimerHeading = findViewById(R.id.disclaimerHeading)
            ?: throw IllegalStateException("disclaimerHeading not found")
        disclaimerText = findViewById(R.id.disclaimerText)
            ?: throw IllegalStateException("disclaimerText not found")
        disclaimerDate = findViewById(R.id.disclaimerDate)
            ?: throw IllegalStateException("disclaimerDate not found")
        agreeButton = findViewById(R.id.btnAgree)
            ?: throw IllegalStateException("btnAgree not found")
        disagreeButton = findViewById(R.id.btnDisagree)
            ?: throw IllegalStateException("btnDisagree not found")
        progressFill = findViewById(R.id.progressFill)
            ?: throw IllegalStateException("progressFill not found")
        scrollProgress = findViewById(R.id.scrollProgress)
            ?: throw IllegalStateException("scrollProgress not found")
    }

    // ==================== 动态日期 ====================

    /**
     * 设置声明底部的更新日期为当天日期。
     * 格式："更新日期：2026 年 7 月 9 日"
     */
    private fun applyDynamicDate() {
        val formatter = SimpleDateFormat(DATE_FORMAT_PATTERN, Locale.CHINA)
        val today = formatter.format(System.currentTimeMillis())
        disclaimerDate.text = getString(R.string.disclaimer_date_dynamic, today)
    }

    // ==================== HTML 富文本解析（增强版） ====================

    /**
     * 将声明文本中的 HTML 标签解析为富文本。
     *
     * 支持的标签：
     * - <b> 粗体、<i> 斜体、<u> 下划线
     * - <font color="#XXXXXX"> 文字颜色
     * - <li> 列表项（自动添加缩进和项目符号）
     * - <br> 强制换行
     * - <blockquote> 引用块（左边框 + 缩进）
     *
     * Android 的 Html.fromHtml 不会自动处理 \n\n 换行，
     * 需要手动替换为 <br><br>。
     */
    private fun applyEnhancedHtmlText() {
        val raw = getString(R.string.disclaimer_content)

        // 第一步：预处理 — 将双换行转为 <br><br>，单换行转为 <br>
        val processed = raw
            .replace("\n\n", "<br><br>")
            .replace("\n", "<br>")

        // 第二步：解析 HTML 为 Spanned（HtmlCompat 自动处理版本差异）
        val spanned = HtmlCompat.fromHtml(processed, HtmlCompat.FROM_HTML_MODE_COMPACT)

        // 第三步：后处理 — 为 <li> 列表项添加项目符号和缩进
        val indented = applyListItemIndent(spanned)

        // 第四步：设置标题
        disclaimerHeading.text = "火崽崽助手（HZZS）免责声明"

        // 第五步：应用到 TextView
        disclaimerText.text = indented
        disclaimerText.textSize = 15f // 15sp，比默认 14sp 稍大
    }

    /**
     * 为列表项添加项目符号和缩进。
     * 在 <li> 标签前插入 Unicode 项目符号（•）并添加缩进。
     */
    private fun applyListItemIndent(spanned: Spanned): Spanned {
        val text = spanned.toString()
        // 将 <li> 替换为带项目符号的格式
        val bulletPrefix = "\n  • "
        val result = text.replace("<li>", bulletPrefix)
        return HtmlCompat.fromHtml(result, HtmlCompat.FROM_HTML_MODE_COMPACT)
    }

    // ==================== 事件绑定 ====================

    /**
     * 绑定所有交互事件：滚动监听、同意按钮、不同意按钮、返回按钮。
     *
     * 滚动监听逻辑：
     * - 计算 maxScroll = 内容总高度 - 可见区域高度
     * - progress = scrollY / maxScroll
     * - progress >= 0.8 时启用 agreeButton
     * - 进度条 View 宽度随滚动平滑变化
     */
    private fun bindActions() {
        // 监听滚动进度
        scrollView.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            val contentHeight = scrollView.getChildAt(0).height
            val scrollRange = contentHeight - scrollView.height
            val progress = if (scrollRange > 0) scrollY.toFloat() / scrollRange else 0f

            // 更新百分比显示
            val percent = (progress * 100).toInt()
            scrollProgress.text = "$percent%"

            // 更新进度条填充宽度（防抖：只在百分比变化时更新）
            updateProgressFill(progress, percent)

            // 滚动到 80% 以上才允许点击同意
            agreeButton.isEnabled = progress >= SCROLL_THRESHOLD
        }

        // 同意按钮
        agreeButton.setOnClickListener {
            if (!agreeButton.isEnabled) {
                Toast.makeText(this, R.string.disclaimer_scroll_hint, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            onAgreeClicked()
        }

        // 不同意按钮
        disagreeButton.setOnClickListener {
            // 显示确认提示
            android.app.AlertDialog.Builder(this)
                .setTitle(R.string.disclaimer_title)
                .setMessage("您选择了不同意免责声明。退出应用后，您将无法使用火崽崽助手。")
                .setPositiveButton("确认退出") { _, _ ->
                    finishAffinity() // 关闭所有 Activity，彻底退出
                }
                .setNegativeButton("取消", null)
                .setCancelable(false)
                .show()
        }

        // 返回按钮
        findViewById<android.widget.ImageView>(android.R.id.home)?.setOnClickListener {
            finish()
        }
    }

    /**
     * 更新进度条填充宽度。
     * 防抖逻辑：只在百分比变化时更新，避免每帧重绘。
     *
     * @param progress 滚动进度比例（0.0 ~ 1.0）
     * @param percent  百分比整数（0 ~ 100）
     */
    private fun updateProgressFill(progress: Float, percent: Int) {
        val clampedProgress = progress.coerceIn(0f, 1f)

        // 防抖：只在百分比变化时更新布局
        if (percent == lastProgressPercent) return
        lastProgressPercent = percent

        // 计算填充宽度（百分比 × 父布局宽度）
        val parent = progressFill.parent as? FrameLayout ?: return
        val maxWidth = parent.width.toFloat()
        val targetWidth = maxWidth * clampedProgress

        val layoutParams = progressFill.layoutParams
        layoutParams.width = targetWidth.toInt()
        progressFill.layoutParams = layoutParams
    }

    /**
     * 处理同意按钮点击。
     * 记录用户已同意，然后根据来源决定是否回到 MainActivity。
     */
    private fun onAgreeClicked() {
        FeatureFlags.setDisclaimerAccepted(this, true)

        val returnToMain = intent.getBooleanExtra(EXTRA_RETURN_TO_MAIN, false)
        if (returnToMain) {
            finish()  // 回到 MainActivity
        } else {
            // 首次启动：通过类名字符串启动 MainActivity，避免直接 import 形成循环引用
            val intent = Intent(Intent.ACTION_MAIN).apply {
                component = android.content.ComponentName(
                    "top.azek431.hzzs",
                    "top.azek431.hzzs.MainActivity"
                )
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
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

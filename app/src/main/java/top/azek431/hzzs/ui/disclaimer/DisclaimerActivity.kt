// 火崽崽助手（HZZS）免责声明页面。
//
// 展示时机：
// 1. 首次启动应用时（FeatureFlags.isDisclaimerAccepted() == false）
// 2. 用户在首页点击"免责声明与功能设置"按钮时
//
// 交互流程：
// 1. 用户阅读声明文本（必须滚动到底部）
// 2. 底部"我已阅读并同意"按钮变为可点击
// 3. 点击后写入 SharedPreferences，跳转回 MainActivity

package top.azek431.hzzs.ui.disclaimer

import android.content.Intent
import android.os.Bundle
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
    private lateinit var agreeButton: MaterialButton
    private lateinit var scrollProgress: android.widget.TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_disclaimer)

        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setHomeAsUpIndicator(R.drawable.ic_overlay_preview_close)
        }

        cacheViews()
        bindActions()
    }

    // ==================== View 缓存 ====================

    private fun cacheViews() {
        scrollView = findViewById(R.id.disclaimerScrollView)
            ?: throw IllegalStateException("disclaimerScrollView not found")
        agreeButton = findViewById(R.id.btnAgree)
            ?: throw IllegalStateException("btnAgree not found")
        scrollProgress = findViewById(R.id.scrollProgress)
            ?: throw IllegalStateException("scrollProgress not found")
    }

    // ==================== 事件绑定 ====================

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

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}

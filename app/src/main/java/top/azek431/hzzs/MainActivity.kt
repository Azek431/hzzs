package top.azek431.hzzs

import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.enableEdgeToEdge(window)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }

        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        setContentView(R.layout.activity_main)

        applySystemBarInsets()

        findViewById<MaterialButton>(R.id.btnDevelopmentPlan).setOnClickListener {
            showDevelopmentPlan()
        }
    }

    private fun applySystemBarInsets() {
        val root = findViewById<View>(R.id.rootContainer)
        val topBar = findViewById<View>(R.id.topBarContainer)
        val scrollView = findViewById<View>(R.id.homeScrollView)

        val topBarPaddingStart = topBar.paddingStart
        val topBarPaddingTop = topBar.paddingTop
        val topBarPaddingEnd = topBar.paddingEnd

        val scrollPaddingStart = scrollView.paddingStart
        val scrollPaddingTop = scrollView.paddingTop
        val scrollPaddingEnd = scrollView.paddingEnd
        val scrollPaddingBottom = scrollView.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val safeInsets = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout(),
            )

            topBar.updatePadding(
                left = topBarPaddingStart + safeInsets.left,
                top = topBarPaddingTop + safeInsets.top,
                right = topBarPaddingEnd + safeInsets.right,
            )

            scrollView.updatePadding(
                left = scrollPaddingStart + safeInsets.left,
                top = scrollPaddingTop,
                right = scrollPaddingEnd + safeInsets.right,
                bottom = scrollPaddingBottom + safeInsets.bottom,
            )

            insets
        }

        ViewCompat.requestApplyInsets(root)
    }

    private fun showDevelopmentPlan() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.development_plan_title)
            .setMessage(R.string.development_plan_message)
            .setPositiveButton(R.string.action_close, null)
            .show()
    }
}
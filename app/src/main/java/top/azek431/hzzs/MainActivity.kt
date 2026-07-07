package top.azek431.hzzs

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.WindowManager
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

        // Light theme: dark status bar icons
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }

        setContentView(R.layout.activity_main)

        applySystemBarInsets()

        findViewById<MaterialButton>(R.id.btnDevelopmentPlan).setOnClickListener {
            showDevelopmentPlan()
        }

        findViewById<MaterialButton>(R.id.btnOverlayExecution).setOnClickListener {
            handleOverlayExecution()
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

    private fun handleOverlayExecution() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                showOverlayPermissionDialog()
            } else {
                startOverlayService()
            }
        } else {
            // Pre-API 23: OVERLAY_PERMISSION is always granted
            startOverlayService()
        }
    }

    private fun showOverlayPermissionDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.overlay_permission_required_title)
            .setMessage(R.string.overlay_permission_required_message)
            .setNegativeButton(R.string.action_close, null)
            .setPositiveButton(R.string.action_go_to_authorization) { _, _ ->
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            }
            .show()
    }

    private fun startOverlayService() {
        // Check if service is already running to prevent duplicate
        if (isOverlayServiceRunning()) {
            return
        }
        val intent = Intent(this, OverlayExecutionService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun isOverlayServiceRunning(): Boolean {
        return try {
            val token = getSystemService(WINDOW_SERVICE) as WindowManager
            token.defaultDisplay.hashCode() != 0 // Just a sanity check
            OverlayExecutionService.isInstanceAlive()
        } catch (_: Exception) {
            false
        }
    }
}

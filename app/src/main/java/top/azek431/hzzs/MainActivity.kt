package top.azek431.hzzs

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Toast
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

        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        setContentView(R.layout.activity_main)

        applySystemBarInsets()
        bindHomeActions()
        refreshOverlayButton()
    }

    override fun onResume() {
        super.onResume()
        refreshOverlayButton()
    }

    private fun bindHomeActions() {
        findViewByName("btnDevelopmentPlan")
            ?.setOnClickListener {
                showDevelopmentPlan()
            }

        findViewByName("btnOverlayExecution")
            ?.setOnClickListener {
                handleOverlayPreview()
            }
    }

    private fun handleOverlayPreview() {
        if (!hasOverlayPermission()) {
            showOverlayPermissionDialog()
            return
        }

        if (OverlayPreviewManager.isShowing()) {
            OverlayPreviewManager.hide()
            refreshOverlayButton()
            return
        }

        val opened = OverlayPreviewManager.show(this)

        refreshOverlayButton()

        if (!opened) {
            Toast.makeText(
                this,
                stringOrFallback(
                    "overlay_preview_open_failed",
                    "悬浮窗未能打开，请检查授权状态。",
                ),
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    private fun hasOverlayPermission(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
            Settings.canDrawOverlays(this)
    }

    private fun showOverlayPermissionDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(
                stringOrFallback(
                    "overlay_permission_required_title",
                    "需要悬浮窗权限",
                ),
            )
            .setMessage(
                stringOrFallback(
                    "overlay_permission_required_message",
                    "火崽崽助手需要“显示在其他应用上层”的权限，才能显示悬浮窗预览。",
                ),
            )
            .setNegativeButton(
                stringOrFallback(
                    "action_close",
                    "关闭",
                ),
                null,
            )
            .setPositiveButton(
                stringOrFallback(
                    "overlay_permission_go_to_settings",
                    "前往授权",
                ),
            ) { _, _ ->
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName"),
                )

                try {
                    startActivity(intent)
                } catch (_: Exception) {
                    Toast.makeText(
                        this,
                        "无法打开系统授权页面，请前往系统设置手动授权。",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
            .show()
    }

    private fun refreshOverlayButton() {
        val button = findViewByName("btnOverlayExecution") as? MaterialButton ?: return

        button.text = if (OverlayPreviewManager.isShowing()) {
            stringOrFallback(
                "overlay_preview_close",
                "关闭悬浮窗",
            )
        } else {
            stringOrFallback(
                "overlay_preview_open",
                "打开悬浮窗",
            )
        }
    }

    private fun showDevelopmentPlan() {
        MaterialAlertDialogBuilder(this)
            .setTitle(
                stringOrFallback(
                    "development_plan_title",
                    "开发计划",
                ),
            )
            .setMessage(
                stringOrFallback(
                    "development_plan_message",
                    "1）界面与导航\n\n2）权限与设备检查\n\n3）跑酷像素分析\n\n4）实时 HUD 与本局战报\n\n5）历史数据与校准",
                ),
            )
            .setPositiveButton(
                stringOrFallback(
                    "action_close",
                    "关闭",
                ),
                null,
            )
            .show()
    }

    private fun applySystemBarInsets() {
        val root = findViewByName("rootContainer") ?: return
        val topBar = findViewByName("topBarContainer")
        val scrollView = findViewByName("homeScrollView")

        val topBarPaddingStart = topBar?.paddingStart ?: 0
        val topBarPaddingTop = topBar?.paddingTop ?: 0
        val topBarPaddingEnd = topBar?.paddingEnd ?: 0

        val scrollPaddingStart = scrollView?.paddingStart ?: 0
        val scrollPaddingTop = scrollView?.paddingTop ?: 0
        val scrollPaddingEnd = scrollView?.paddingEnd ?: 0
        val scrollPaddingBottom = scrollView?.paddingBottom ?: 0

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val safeInsets = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout(),
            )

            topBar?.updatePadding(
                left = topBarPaddingStart + safeInsets.left,
                top = topBarPaddingTop + safeInsets.top,
                right = topBarPaddingEnd + safeInsets.right,
            )

            scrollView?.updatePadding(
                left = scrollPaddingStart + safeInsets.left,
                top = scrollPaddingTop,
                right = scrollPaddingEnd + safeInsets.right,
                bottom = scrollPaddingBottom + safeInsets.bottom,
            )

            insets
        }

        ViewCompat.requestApplyInsets(root)
    }

    private fun findViewByName(name: String): View? {
        val id = resources.getIdentifier(name, "id", packageName)

        return if (id == 0) {
            null
        } else {
            findViewById(id)
        }
    }

    private fun stringOrFallback(name: String, fallback: String): String {
        val id = resources.getIdentifier(name, "string", packageName)

        return if (id == 0) {
            fallback
        } else {
            getString(id)
        }
    }
}
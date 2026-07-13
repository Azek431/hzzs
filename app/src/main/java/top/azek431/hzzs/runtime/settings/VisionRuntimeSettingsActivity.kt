package top.azek431.hzzs.runtime.settings

import android.Manifest
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import top.azek431.hzzs.runtime.capture.CaptureCapabilityDetector
import top.azek431.hzzs.runtime.capture.CaptureMode
import top.azek431.hzzs.runtime.capture.CapturePermissionActivity
import top.azek431.hzzs.runtime.capture.CapturePreferences
import top.azek431.hzzs.runtime.capture.MediaProjectionCaptureService
import top.azek431.hzzs.features.service.AutoOperationService
import top.azek431.hzzs.runtime.vision.VisionRuntimeService

class VisionRuntimeSettingsActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private lateinit var modeSpinner: Spinner
    private lateinit var modes: List<CaptureMode>
    private var initializingSpinner = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CapturePreferences.ensureOptimizedDefaults(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 48)
        }
        val scroll = ScrollView(this).apply {
            addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        setContentView(scroll)

        root.addView(TextView(this).apply {
            text = "HZZS 视觉、权限与自动操作"
            textSize = 24f
        })
        root.addView(TextView(this).apply {
            text = "首次启动默认使用 AUTO：Android 11+ 已连接无障碍时优先无障碍截图，否则使用 MediaProjection；Root 永不自动启用。"
            textSize = 14f
        })
        status = TextView(this).also(root::addView)

        modes = CaptureMode.values().toList()
        modeSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@VisionRuntimeSettingsActivity,
                android.R.layout.simple_spinner_dropdown_item,
                modes.map(::modeLabel),
            )
            setSelection(modes.indexOf(CapturePreferences.mode(this@VisionRuntimeSettingsActivity)).coerceAtLeast(0))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    if (initializingSpinner) {
                        initializingSpinner = false
                        return
                    }
                    CapturePreferences.setMode(this@VisionRuntimeSettingsActivity, modes[position])
                    refresh()
                }
            }
        }
        root.addView(modeSpinner)

        fun button(text: String, action: () -> Unit): Button = Button(this).apply {
            this.text = text
            setOnClickListener {
                action()
                refresh()
            }
            root.addView(this)
        }

        fun toggle(text: String, initial: Boolean, action: (Boolean) -> Unit): Switch = Switch(this).apply {
            this.text = text
            isChecked = initial
            setOnCheckedChangeListener { _, value ->
                action(value)
                refresh()
            }
            root.addView(this)
        }

        toggle("启用自动操作", CapturePreferences.autoAction(this)) {
            CapturePreferences.setAutoAction(this, it)
        }
        toggle("显示持久 HUD", CapturePreferences.draw(this)) {
            CapturePreferences.setDraw(this, it)
        }
        toggle("显示详细数据", CapturePreferences.detailed(this)) {
            CapturePreferences.setDetailed(this, it)
        }

        button("使用自动最优模式") {
            CapturePreferences.setMode(this, CaptureMode.AUTO)
            modeSpinner.setSelection(modes.indexOf(CaptureMode.AUTO))
        }
        button("授予悬浮窗权限") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        button("开启 HZZS 无障碍服务") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        button("授权 MediaProjection") {
            startActivity(Intent(this, CapturePermissionActivity::class.java))
        }
        button("停止 MediaProjection 会话") {
            MediaProjectionCaptureService.stop(this)
        }
        button("检查 Root 实验能力（会调用 su）") {
            val available = top.azek431.hzzs.runtime.capture.RootFrameSource.probeAvailability()
            Toast.makeText(this, if (available) "Root 可用" else "Root 不可用或未授权", Toast.LENGTH_SHORT).show()
        }
        button("校准游戏画面区域") {
            startActivity(Intent(this, ViewportCalibrationActivity::class.java))
        }
        button("恢复全屏画面区域") {
            CapturePreferences.resetViewport(this)
        }
        button("启动视觉、HUD 与自动操作") {
            startRuntimeWithSafetyCheck()
        }
        button("停止运行") {
            VisionRuntimeService.stop(this)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            button("允许通知") {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
            }
        }
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun startRuntimeWithSafetyCheck() {
        val selected = modes[modeSpinner.selectedItemPosition]
        CapturePreferences.setMode(this, selected)
        if (selected == CaptureMode.ROOT_EXPERIMENTAL) {
            AlertDialog.Builder(this)
                .setTitle("确认启用 Root 实验截图")
                .setMessage("Root 模式会执行 su -c screencap，仅在你主动选择时使用，不参与 AUTO 回退。是否继续？")
                .setNegativeButton("取消", null)
                .setPositiveButton("继续") { _, _ -> VisionRuntimeService.start(this) }
                .show()
            return
        }
        if (CapturePreferences.autoAction(this) && !isExistingAccessibilityServiceConnected()) {
            Toast.makeText(this, "自动操作需要先开启现有 HZZS 无障碍服务", Toast.LENGTH_LONG).show()
        }
        VisionRuntimeService.start(this)
    }

    private fun isExistingAccessibilityServiceConnected(): Boolean =
        AutoOperationService.isConnected()

    private fun refresh() {
        if (!::status.isInitialized) return
        val capabilities = CaptureCapabilityDetector.detect(this)
        status.text = buildString {
            appendLine("Android API ${capabilities.androidApi}")
            appendLine("推荐：${modeLabel(capabilities.recommended)}")
            appendLine("无障碍截图支持：${capabilities.accessibilityScreenshotSupported}")
            appendLine("无障碍已连接：${capabilities.accessibilityConnected}")
            appendLine("当前前台包名：${AutoOperationService.foregroundPackageName() ?: "--"}")
            appendLine("自动操作目标允许：${AutoOperationService.isActionTargetAllowed()}")
            appendLine("MediaProjection 已就绪：${capabilities.mediaProjectionReady}")
            appendLine("Root：${capabilities.rootAvailable}")
            append("运行中：${VisionRuntimeService.isRunning()}")
        }
    }

    private fun modeLabel(mode: CaptureMode): String = when (mode) {
        CaptureMode.AUTO -> "AUTO（推荐：动态选择）"
        CaptureMode.ACCESSIBILITY -> "无障碍截图（Android 11+）"
        CaptureMode.MEDIA_PROJECTION -> "MediaProjection（Android 7+）"
        CaptureMode.ROOT_EXPERIMENTAL -> "Root 实验截图（手动选择）"
        CaptureMode.DISABLED -> "关闭截图"
    }

    private companion object {
        const val REQUEST_NOTIFICATIONS = 431
    }
}

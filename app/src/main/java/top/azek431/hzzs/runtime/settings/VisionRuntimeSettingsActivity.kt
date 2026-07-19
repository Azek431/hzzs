package top.azek431.hzzs.runtime.settings

import android.Manifest
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
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import top.azek431.hzzs.features.service.AutoOperationService
import top.azek431.hzzs.features.service.RuntimeActionQueue
import top.azek431.hzzs.runtime.capture.CaptureCapabilityDetector
import top.azek431.hzzs.runtime.capture.CaptureMode
import top.azek431.hzzs.runtime.capture.CapturePermissionActivity
import top.azek431.hzzs.runtime.capture.CapturePreferences
import top.azek431.hzzs.runtime.capture.MediaProjectionCaptureService
import top.azek431.hzzs.runtime.vision.VisionAlgorithm
import top.azek431.hzzs.runtime.vision.VisionRuntimeService

class VisionRuntimeSettingsActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private lateinit var modeSpinner: Spinner
    private lateinit var algorithmSpinner: Spinner
    private lateinit var autoActionSwitch: Switch
    private lateinit var bambooExperimentalSwitch: Switch
    private lateinit var drawSwitch: Switch
    private lateinit var detailedSwitch: Switch

    private lateinit var modes: List<CaptureMode>
    private lateinit var algorithms: List<VisionAlgorithm>
    private var initializingModeSpinner = true
    private var initializingAlgorithmSpinner = true
    private var synchronizingUi = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CapturePreferences.ensureOptimizedDefaults(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 48)
        }
        val scroll = ScrollView(this).apply {
            addView(
                root,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        setContentView(scroll)

        root.addView(TextView(this).apply {
            text = "HZZS 视觉、权限与自动操作"
            textSize = 24f
        })
        root.addView(TextView(this).apply {
            text = "AUTO 会动态选择截图后端；Root 永不自动启用。竹影书屋自动操作仍为实验功能。"
            textSize = 14f
        })
        status = TextView(this).also(root::addView)

        modes = CaptureMode.entries.toList()
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
                    if (initializingModeSpinner) {
                        initializingModeSpinner = false
                        return
                    }
                    val selected = modes[position]
                    if (selected == CapturePreferences.mode(this@VisionRuntimeSettingsActivity)) return
                    stopAndClearRuntime()
                    CapturePreferences.setMode(this@VisionRuntimeSettingsActivity, selected)
                    refresh()
                }
            }
        }
        root.addView(modeSpinner)

        root.addView(TextView(this).apply {
            text = "识别算法"
            textSize = 18f
        })
        algorithms = VisionAlgorithm.entries.toList()
        algorithmSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@VisionRuntimeSettingsActivity,
                android.R.layout.simple_spinner_dropdown_item,
                algorithms.map { it.displayName },
            )
            setSelection(
                algorithms.indexOf(CapturePreferences.algorithm(this@VisionRuntimeSettingsActivity))
                    .coerceAtLeast(0),
            )
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit

                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    if (initializingAlgorithmSpinner) {
                        initializingAlgorithmSpinner = false
                        return
                    }
                    val selected = algorithms[position]
                    if (selected == CapturePreferences.algorithm(this@VisionRuntimeSettingsActivity)) return
                    stopAndClearRuntime()
                    CapturePreferences.setAutoAction(this@VisionRuntimeSettingsActivity, false)
                    CapturePreferences.setAlgorithm(this@VisionRuntimeSettingsActivity, selected)
                    Toast.makeText(
                        this@VisionRuntimeSettingsActivity,
                        "已切换到${selected.displayName}；自动操作已关闭",
                        Toast.LENGTH_LONG,
                    ).show()
                    refresh()
                }
            }
        }
        root.addView(algorithmSpinner)

        fun button(text: String, action: () -> Unit): Button = Button(this).apply {
            this.text = text
            setOnClickListener {
                action()
                refresh()
            }
            root.addView(this)
        }

        autoActionSwitch = Switch(this).apply {
            text = "启用自动操作"
            setOnCheckedChangeListener { _, value ->
                if (synchronizingUi) return@setOnCheckedChangeListener
                if (value && !CapturePreferences.actionAllowedByAlgorithm(this@VisionRuntimeSettingsActivity)) {
                    Toast.makeText(
                        this@VisionRuntimeSettingsActivity,
                        "竹影书屋需要先单独确认实验自动操作",
                        Toast.LENGTH_LONG,
                    ).show()
                    refresh()
                    return@setOnCheckedChangeListener
                }
                CapturePreferences.setAutoAction(this@VisionRuntimeSettingsActivity, value)
                refresh()
            }
        }
        root.addView(autoActionSwitch)

        bambooExperimentalSwitch = Switch(this).apply {
            text = "允许竹影书屋实验自动操作（未完成真机校准）"
            setOnCheckedChangeListener { _, value ->
                if (synchronizingUi) return@setOnCheckedChangeListener
                if (value) {
                    AlertDialog.Builder(this@VisionRuntimeSettingsActivity)
                        .setTitle("确认实验自动操作")
                        .setMessage("竹影书屋的点击次数、触发距离和滑铲时长尚未完成完整真机校准。建议只使用 HUD。是否仍然启用？")
                        .setNegativeButton("取消") { _, _ -> refresh() }
                        .setPositiveButton("我已了解风险") { _, _ ->
                            CapturePreferences.setBambooExperimentalAutoAction(this@VisionRuntimeSettingsActivity, true)
                            refresh()
                        }
                        .show()
                } else {
                    CapturePreferences.setBambooExperimentalAutoAction(this@VisionRuntimeSettingsActivity, false)
                    CapturePreferences.setAutoAction(this@VisionRuntimeSettingsActivity, false)
                    RuntimeActionQueue.clear()
                    refresh()
                }
            }
        }
        root.addView(bambooExperimentalSwitch)

        drawSwitch = Switch(this).apply {
            text = "显示持久 HUD"
            setOnCheckedChangeListener { _, value ->
                if (!synchronizingUi) CapturePreferences.setDraw(this@VisionRuntimeSettingsActivity, value)
            }
        }
        root.addView(drawSwitch)

        detailedSwitch = Switch(this).apply {
            text = "显示详细数据"
            setOnCheckedChangeListener { _, value ->
                if (!synchronizingUi) CapturePreferences.setDetailed(this@VisionRuntimeSettingsActivity, value)
            }
        }
        root.addView(detailedSwitch)

        button("使用自动最优模式") {
            stopAndClearRuntime()
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
            stopAndClearRuntime()
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
        val selectedMode = modes[modeSpinner.selectedItemPosition]
        val algorithm = CapturePreferences.algorithm(this)
        CapturePreferences.setMode(this, selectedMode)

        if (selectedMode == CaptureMode.ROOT_EXPERIMENTAL) {
            AlertDialog.Builder(this)
                .setTitle("确认启用 Root 实验截图")
                .setMessage("Root 模式会执行 su -c screencap，仅在你主动选择时使用，不参与 AUTO 回退。是否继续？")
                .setNegativeButton("取消", null)
                .setPositiveButton("继续") { _, _ -> VisionRuntimeService.start(this) }
                .show()
            return
        }

        if (CapturePreferences.autoAction(this)) {
            if (!CapturePreferences.actionAllowedByAlgorithm(this, algorithm)) {
                CapturePreferences.setAutoAction(this, false)
                Toast.makeText(this, "当前算法未允许自动操作，已保持关闭", Toast.LENGTH_LONG).show()
                refresh()
                return
            }
            if (!AutoOperationService.isConnected()) {
                Toast.makeText(this, "自动操作需要先开启 HZZS 无障碍服务", Toast.LENGTH_LONG).show()
                return
            }
        }
        VisionRuntimeService.start(this)
    }

    private fun stopAndClearRuntime() {
        VisionRuntimeService.stop(this)
        RuntimeActionQueue.clear()
    }

    private fun refresh() {
        if (!::status.isInitialized) return
        val capabilities = CaptureCapabilityDetector.detect(this)
        val algorithm = CapturePreferences.algorithm(this)
        synchronizingUi = true
        try {
            autoActionSwitch.isEnabled = CapturePreferences.actionAllowedByAlgorithm(this, algorithm)
            autoActionSwitch.isChecked = CapturePreferences.autoAction(this) && autoActionSwitch.isEnabled
            bambooExperimentalSwitch.visibility =
                if (algorithm == VisionAlgorithm.BAMBOO_STUDY) View.VISIBLE else View.GONE
            bambooExperimentalSwitch.isChecked = CapturePreferences.bambooExperimentalAutoAction(this)
            drawSwitch.isChecked = CapturePreferences.draw(this)
            detailedSwitch.isChecked = CapturePreferences.detailed(this)
        } finally {
            synchronizingUi = false
        }

        status.text = buildString {
            appendLine("Android API ${capabilities.androidApi}")
            appendLine("推荐截图：${modeLabel(capabilities.recommended)}")
            appendLine("识别算法：${algorithm.displayName}")
            appendLine("自动操作已校准：${algorithm.automaticActionCalibrated}")
            appendLine("竹影实验操作：${CapturePreferences.bambooExperimentalAutoAction(this@VisionRuntimeSettingsActivity)}")
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

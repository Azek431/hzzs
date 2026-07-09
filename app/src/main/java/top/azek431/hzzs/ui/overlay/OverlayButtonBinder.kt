// 火崽崽助手（HZZS）悬浮窗 — 按钮绑定器。
//
// 职责：
// - 绑定循环执行/单次执行按钮的点击事件
// - 管理分析执行状态（CYCLE_RUNNING / SINGLE_PENDING / IDLE）
// - 更新状态指示器外观（文本、颜色、按钮状态）
//
// 设计原因：
// - 将 ~60 行的按钮绑定逻辑从 OverlayPreviewManager.show() 中提取
// - 状态机逻辑集中管理，便于理解和测试

package top.azek431.hzzs.ui.overlay

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.TextView
import android.widget.Toast
import top.azek431.hzzs.R

/**
 * 悬浮窗按钮绑定器。
 *
 * 负责循环执行/单次执行按钮的点击事件绑定和状态管理。
 * 构造函数接收所有必需的 View 引用，在 bind() 调用后完成绑定。
 *
 * @param statusText 状态文本 TextView
 * @param statusDot 状态指示灯 View
 * @param btnCycle 循环执行/停止按钮
 * @param btnSingle 单次执行按钮
 * @param context 上下文（用于 Toast）
 */
class OverlayButtonBinder(
    private val statusText: TextView,
    private val statusDot: View,
    private val btnCycle: TextView,
    private val btnSingle: TextView,
    private val context: android.content.Context,
) {

    companion object {
        private const val TAG = "HZZS-OverlayBtn"

        /** 单次执行后恢复空闲状态的延迟（毫秒） */
        private const val SINGLE_DELAY_MS = 400L
    }

    // ==================== 状态 ====================

    /** 当前分析执行状态 */
    private var state: AnalysisUiState = AnalysisUiState.IDLE

    /** Handler 用于单次执行后的状态恢复 */
    @Suppress("DEPRECATION")
    private val handler = Handler(Looper.getMainLooper())

    // ==================== 状态枚举 ====================

    /** 分析执行状态 */
    internal enum class AnalysisUiState {
        CYCLE_RUNNING,
        SINGLE_PENDING,
        IDLE,
    }

    // ==================== 绑定入口 ====================

    /**
     * 绑定所有按钮的点击事件。
     *
     * 绑定内容：
     * 1. 循环执行按钮：点击切换 循环执行 <-> 停止运行
     * 2. 单次执行按钮：点击执行一次分析，SINGLE_DELAY_MS 后自动恢复空闲
     *
     * @param startCycle 启动循环执行的回调
     * @param stopCycle 停止循环执行的回调
     * @param reset 重置状态的回调
     * @param startSingle 单次执行的回调（可选，默认调用 startCycle）
     */
    fun bind(
        startCycle: () -> Unit,
        stopCycle: () -> Unit,
        reset: () -> Unit,
        startSingle: () -> Unit = { startCycle() },
    ) {
        // 循环执行按钮：点击切换 循环执行 <-> 停止运行
        btnCycle.setOnClickListener {
            when (state) {
                AnalysisUiState.IDLE -> {
                    state = AnalysisUiState.CYCLE_RUNNING
                    updateStatusUI(true)
                    Log.i(TAG, "[Btn] cycle execution started.")
                    Toast.makeText(context, R.string.overlay_analysis_started, Toast.LENGTH_SHORT).show()
                    startCycle()
                }
                AnalysisUiState.CYCLE_RUNNING -> {
                    state = AnalysisUiState.IDLE
                    updateStatusUI(false)
                    Log.i(TAG, "[Btn] cycle execution stopped.")
                    Toast.makeText(context, R.string.overlay_analysis_stopped, Toast.LENGTH_SHORT).show()
                    stopCycle()
                    reset()
                }
                AnalysisUiState.SINGLE_PENDING -> {
                    // 用户在单次执行等待中点击循环按钮，取消单次并启动循环
                    handler.removeCallbacksAndMessages(null)
                    state = AnalysisUiState.CYCLE_RUNNING
                    updateStatusUI(true)
                    Log.i(TAG, "[Btn] switched from single to cycle execution.")
                    startCycle()
                }
            }
        }

        // 单次执行按钮：点击后执行一次分析，等待完成后再恢复空闲状态
        btnSingle.setOnClickListener {
            // 如果循环执行正在运行，先停止循环
            if (state == AnalysisUiState.CYCLE_RUNNING) {
                handler.removeCallbacksAndMessages(null)
                state = AnalysisUiState.IDLE
                stopCycle()
                updateStatusUI(false)
            }

            // 防止重复点击
            if (state == AnalysisUiState.SINGLE_PENDING) return@setOnClickListener

            state = AnalysisUiState.SINGLE_PENDING
            updateStatusUI(true)
            Log.i(TAG, "[Btn] single execution triggered.")
            Toast.makeText(context, R.string.overlay_single_started, Toast.LENGTH_SHORT).show()

            // 执行单次分析
            startSingle()

            // 确认单次执行完成后恢复空闲状态
            if (state == AnalysisUiState.SINGLE_PENDING) {
                state = AnalysisUiState.IDLE
                updateStatusUI(false)
                Log.i(TAG, "[Btn] single execution completed, state restored.")
            }

            // SINGLE_DELAY_MS 后自动恢复空闲状态（防止单次执行卡住）
            handler.postDelayed({
                if (state == AnalysisUiState.SINGLE_PENDING) {
                    state = AnalysisUiState.IDLE
                    updateStatusUI(false)
                    Log.i(TAG, "[Btn] single execution auto-cleared after $SINGLE_DELAY_MS ms.")
                }
            }, SINGLE_DELAY_MS)
        }
    }

    /**
     * 更新状态指示器外观。
     *
     * @param isRunning 是否正在运行
     */
    private fun updateStatusUI(isRunning: Boolean) {
        if (isRunning) {
            statusText.setText(R.string.overlay_analysis_running)
            statusText.setTextColor(context.getColor(android.R.color.holo_blue_light))
            statusDot.setBackgroundColor(context.getColor(android.R.color.holo_blue_light))
            btnCycle.setText(R.string.overlay_btn_cycle_stop)
            btnCycle.setBackgroundResource(R.drawable.bg_overlay_btn_single)
            btnSingle.isEnabled = false
            btnSingle.alpha = 0.4f
        } else {
            statusText.setText(R.string.overlay_preview_status)
            statusText.setTextColor(context.getColor(android.R.color.darker_gray))
            statusDot.setBackgroundColor(context.getColor(android.R.color.holo_blue_dark))
            btnCycle.setText(R.string.overlay_btn_cycle_start)
            btnCycle.setBackgroundResource(R.drawable.bg_overlay_btn_cycle)
            btnSingle.isEnabled = true
            btnSingle.alpha = 1f
        }
    }

    /** 获取当前状态（供外部查询） */
    internal fun getState(): AnalysisUiState = state

    /** 清理资源 */
    fun cancel() {
        handler.removeCallbacksAndMessages(null)
    }
}

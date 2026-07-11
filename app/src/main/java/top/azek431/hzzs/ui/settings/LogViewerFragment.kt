// 火崽崽助手（HZZS）综合日志查看页面。
//
// 职责：
// - 展示 app 各层内存日志（Logger 缓冲区）
// - 展示 C++ 视觉算法原始日志（CSV/JSON）
// - 支持自动刷新 / 手动刷新
// - 支持按日志级别筛选、标签筛选、关键词搜索
// - 支持导出 TXT / CSV / JSON、复制、清空
//
// 数据来源：
// 1. Logger（内存环形缓冲区）— app 各层写入，实时展示
// 2. VisionBridge.getLogCsv() / getLogJson() — C++ LogManager 导出
//
// 线程模型：
// - 自动刷新在主线程通过 Handler.postDelayed 轮询
// - 日志筛选和 UI 更新在主线程执行
// - C++ 日志读取在主线程执行（JNI 调用）

package top.azek431.hzzs.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import top.azek431.hzzs.R
import top.azek431.hzzs.core.data.native.VisionBridge
import top.azek431.hzzs.core.util.Logger
import top.azek431.hzzs.core.util.Logger.LogEntry

/**
 * 综合日志查看 Fragment。
 *
 * 展示两类日志：
 * 1. App 层内存日志（Logger 缓冲区）— 带筛选、搜索、自动刷新
 * 2. C++ 视觉算法原始日志（CSV/JSON）— 保留原有功能
 */
class LogViewerFragment : Fragment() {

    companion object {
        fun newInstance() = LogViewerFragment()
    }

    // ==================== View 引用 ====================

    private lateinit var spinnerLevel: Spinner
    private lateinit var spinnerTag: Spinner
    private lateinit var etSearch: EditText
    private lateinit var btnAutoRefresh: Button
    private lateinit var btnManualRefresh: Button
    private lateinit var btnExportTxt: Button
    private lateinit var btnExportCsv: Button
    private lateinit var btnCopyLog: Button
    private lateinit var btnClearLog: Button
    private lateinit var tvLogStats: TextView
    private lateinit var logContainer: LinearLayout
    private lateinit var cppLogTextView: TextView
    private lateinit var scrollView: ScrollView

    /** 标签筛选 Spinner 的 Adapter（需要在 refreshTagSpinner 中更新） */
    private lateinit var tagAdapter: ArrayAdapter<String>

    // ==================== 数据状态 ====================

    /** 当前显示的日志条目 */
    private var displayedLogs: List<LogEntry> = emptyList()

    /** 自动刷新状态 */
    private var autoRefreshEnabled = false

    /** 自动刷新 Handler */
    @Suppress("DEPRECATION")
    private val refreshHandler = Handler(Looper.getMainLooper())

    /** 自动刷新间隔（毫秒） */
    private val AUTO_REFRESH_INTERVAL = 1000L

    /** 日志级别数组 */
    private val LEVELS = arrayOf("全部", "VERBOSE", "DEBUG", "INFO", "WARN", "ERROR")

    /** 标签集合（动态收集） */
    private val allTags = mutableSetOf<String>()

    // ==================== Fragment 生命周期 ====================

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_log_viewer, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 初始化 View
        spinnerLevel = view.findViewById(R.id.spinnerLevel)
        spinnerTag = view.findViewById(R.id.spinnerTag)
        etSearch = view.findViewById(R.id.etSearch)
        btnAutoRefresh = view.findViewById(R.id.btnAutoRefresh)
        btnManualRefresh = view.findViewById(R.id.btnManualRefresh)
        btnExportTxt = view.findViewById(R.id.btnExportTxt)
        btnExportCsv = view.findViewById(R.id.btnExportCsv)
        btnCopyLog = view.findViewById(R.id.btnCopyLog)
        btnClearLog = view.findViewById(R.id.btnClearLog)
        tvLogStats = view.findViewById(R.id.tvLogStats)
        logContainer = view.findViewById(R.id.logContainer)
        cppLogTextView = view.findViewById(R.id.cppLogTextView)
        scrollView = view.findViewById(R.id.logScrollView)

        // 初始化级别筛选
        val levelAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, LEVELS)
        levelAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerLevel.adapter = levelAdapter

        // 初始化标签筛选（先加载已有的标签）
        refreshTagSpinner()
        val tagAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, allTags.toList())
        tagAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerTag.adapter = tagAdapter

        // 事件绑定
        bindActions()

        // 初始加载
        refreshAllLogs()

        // 加载 C++ 算法日志
        refreshCppLog()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // 清理事件，防止内存泄漏
        refreshHandler.removeCallbacksAndMessages(null)
    }

    // ==================== 事件绑定 ====================

    private fun bindActions() {
        // 自动刷新开关
        btnAutoRefresh.setOnClickListener {
            autoRefreshEnabled = !autoRefreshEnabled
            btnAutoRefresh.text = if (autoRefreshEnabled) "自动刷新: 开" else "自动刷新: 关"

            if (autoRefreshEnabled) {
                startAutoRefresh()
                Toast.makeText(context, "自动刷新已开启", Toast.LENGTH_SHORT).show()
            } else {
                stopAutoRefresh()
                Toast.makeText(context, "自动刷新已关闭", Toast.LENGTH_SHORT).show()
            }
        }

        // 手动刷新
        btnManualRefresh.setOnClickListener {
            refreshAllLogs()
            refreshCppLog()
            Toast.makeText(context, "已刷新", Toast.LENGTH_SHORT).show()
        }

        // 导出 TXT
        btnExportTxt.setOnClickListener { exportTxt() }

        // 导出 CSV（合并 app 日志 + C++ 日志）
        btnExportCsv.setOnClickListener { exportCsv() }

        // 复制
        btnCopyLog.setOnClickListener { copyLog() }

        // 清空
        btnClearLog.setOnClickListener { clearLog() }

        // 级别筛选变化
        spinnerLevel.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>, view: View?, position: Int, id: Long) {
                refreshDisplayedLogs()
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>) {}
        }

        // 标签筛选变化
        spinnerTag.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>, view: View?, position: Int, id: Long) {
                refreshDisplayedLogs()
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>) {}
        }

        // 搜索框
        etSearch.setOnEditorActionListener { _, _, _ ->
            refreshDisplayedLogs()
            true
        }
    }

    // ==================== 日志刷新 ====================

    /**
     * 刷新所有日志（全量读取 + 筛选 + 渲染）。
     *
     * 使用全量读取替代增量读取，避免环形缓冲区 wrap-around 后索引漂移问题。
     * 5000 条以内全量读取 + 筛选通常在 10ms 以内，对 1 秒自动刷新间隔无感知影响。
     */
    private fun refreshAllLogs() {
        // 收集所有标签
        refreshTagSpinner()
        refreshDisplayedLogs()
    }

    /**
     * 刷新标签 Spinner。
     */
    private fun refreshTagSpinner() {
        allTags.clear()
        val allLogs = Logger.getAll()
        for (entry in allLogs) {
            allTags.add(entry.tag)
        }
        // 也加上 C++ 视觉模块的标签
        allTags.add("Vision-Bottle")
        allTags.add("Vision-Pit")
        allTags.add("Vision-General")

        // 更新 adapter 以反映新标签
        if (::tagAdapter.isInitialized) {
            tagAdapter.clear()
            tagAdapter.addAll(allTags.toList())
            tagAdapter.notifyDataSetChanged()
        }
    }

    /**
     * 根据当前筛选条件刷新显示的日志。
     */
    private fun refreshDisplayedLogs() {
        val minLevelPos = spinnerLevel.selectedItemPosition
        val minLevel = if (minLevelPos == 0) Logger.LEVEL_VERBOSE else minLevelPos - 1

        val selectedTag = spinnerTag.selectedItem as? String
        val searchQuery = etSearch.text.toString().trim()

        val filtered = Logger.filter(
            minLevel = minLevel,
            tags = if (selectedTag == "全部" || selectedTag.isNullOrEmpty()) null else listOf(selectedTag),
            search = if (searchQuery.isEmpty()) null else searchQuery,
        )

        displayedLogs = filtered
        renderLogList(filtered)
        updateStats(filtered.size)
    }

    /**
     * 渲染日志列表到 UI。
     */
    private fun renderLogList(entries: List<LogEntry>) {
        // 清空现有内容
        logContainer.removeAllViews()

        if (entries.isEmpty()) {
            val emptyTv = TextView(requireContext()).apply {
                text = "暂无日志"
                setTextColor(android.graphics.Color.rgb(100, 100, 100))
                textSize = 12f
                setPadding(0, 16, 0, 16)
            }
            logContainer.addView(emptyTv)
            return
        }

        for (entry in entries) {
            val tv = createLogTextView(entry)
            logContainer.addView(tv)
        }

        // 自动滚动到底部
        scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
    }

    /**
     * 创建单行日志 TextView。
     */
    private fun createLogTextView(entry: LogEntry): TextView {
        return TextView(requireContext()).apply {
            // 格式化日志行：[时间] [级别] [标签] 消息
            text = buildString {
                append("[${entry.timeText}] ")
                append("[${entry.levelText}] ")
                append("[${entry.tag}] ")
                append(entry.message)
            }
            setTextColor(entry.levelColor)
            textSize = 11f
            setPadding(4, 2, 4, 2)
            setLineSpacing(2f, 1f)
        }
    }

    /**
     * 更新统计信息。
     */
    private fun updateStats(filteredCount: Int) {
        val totalCount = Logger.size
        val cppCount = VisionBridge.getLogCount()
        tvLogStats.text = "内存日志: $totalCount 条 | 筛选后: $filteredCount 条 | C++ 算法日志: $cppCount 条"
    }

    // ==================== C++ 算法日志 ====================

    /**
     * 刷新 C++ 视觉算法原始日志。
     */
    private fun refreshCppLog() {
        val csv = VisionBridge.getLogCsv()
        if (csv.isEmpty()) {
            cppLogTextView.text = "暂无 C++ 算法日志（点击'刷新'查看）"
        } else {
            cppLogTextView.text = "C++ 算法日志 CSV:\n\n$csv"
        }
    }

    // ==================== 自动刷新 ====================

    private val refreshRunnable = object : Runnable {
        override fun run() {
            if (autoRefreshEnabled) {
                refreshAllLogs()
                refreshCppLog()
                refreshHandler.postDelayed(this, AUTO_REFRESH_INTERVAL)
            }
        }
    }

    private fun startAutoRefresh() {
        refreshHandler.post(refreshRunnable)
    }

    private fun stopAutoRefresh() {
        refreshHandler.removeCallbacks(refreshRunnable)
    }

    // ==================== 导出功能 ====================

    /**
     * 导出为 TXT 文件。
     */
    private fun exportTxt() {
        val entries = displayedLogs.ifEmpty { Logger.getAll() }
        if (entries.isEmpty()) {
            Toast.makeText(context, "暂无日志可导出", Toast.LENGTH_SHORT).show()
            return
        }

        val sb = StringBuilder()
        sb.append("HZZS 综合日志导出\n")
        sb.append("导出时间: ${System.currentTimeMillis()}\n")
        sb.append("=" .repeat(60))
        sb.append("\n\n")

        for (entry in entries) {
            sb.append("[${entry.timeText}] [${entry.levelText}] [${entry.tag}] ${entry.message}\n")
        }

        val dir = requireContext().externalCacheDir
        val file = java.io.File(dir, "hzzs_log_${System.currentTimeMillis()}.txt")
        file.writeText(sb.toString())
        Toast.makeText(context, "已导出 TXT: ${file.absolutePath}", Toast.LENGTH_SHORT).show()
    }

    /**
     * 导出为 CSV 文件（合并 app 日志 + C++ 日志）。
     */
    private fun exportCsv() {
        val entries = displayedLogs.ifEmpty { Logger.getAll() }
        if (entries.isEmpty() && VisionBridge.getLogCsv().isEmpty()) {
            Toast.makeText(context, "暂无日志可导出", Toast.LENGTH_SHORT).show()
            return
        }

        val sb = StringBuilder()
        sb.append("time_ms,level,tag,message\n")

        for (entry in entries) {
            // 转义 CSV 中的逗号和引号
            val safeMessage = entry.message.replace("\"", "\"\"")
            sb.append("${entry.timestamp},${entry.level},${entry.tag},\"$safeMessage\"\n")
        }

        // 追加 C++ 日志 CSV 内容
        val cppCsv = VisionBridge.getLogCsv()
        if (cppCsv.isNotEmpty()) {
            sb.append("\n--- C++ 视觉算法日志 ---\n")
            sb.append(cppCsv)
        }

        val dir = requireContext().externalCacheDir
        val file = java.io.File(dir, "hzzs_log_${System.currentTimeMillis()}.csv")
        file.writeText(sb.toString())
        Toast.makeText(context, "已导出 CSV: ${file.absolutePath}", Toast.LENGTH_SHORT).show()
    }

    /**
     * 复制日志到剪贴板。
     */
    private fun copyLog() {
        val entries = displayedLogs.ifEmpty { Logger.getAll() }
        if (entries.isEmpty()) {
            Toast.makeText(context, "暂无日志可复制", Toast.LENGTH_SHORT).show()
            return
        }

        val sb = StringBuilder()
        for (entry in entries) {
            sb.append("[${entry.timeText}] [${entry.levelText}] [${entry.tag}] ${entry.message}\n")
        }

        val clipboard = context?.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager?
        clipboard?.setPrimaryClip(ClipData.newPlainText("HZZS Log", sb.toString()))
        Toast.makeText(context, "日志已复制到剪贴板 (${entries.size} 条)", Toast.LENGTH_SHORT).show()
    }

    /**
     * 清空日志缓冲区（内存 + C++）。
     */
    private fun clearLog() {
        Logger.clear()
        VisionBridge.clearLog()
        displayedLogs = emptyList()
        logContainer.removeAllViews()

        val emptyTv = TextView(requireContext()).apply {
            text = "日志已清空"
            setTextColor(android.graphics.Color.rgb(150, 150, 150))
            textSize = 12f
            setPadding(0, 16, 0, 16)
        }
        logContainer.addView(emptyTv)

        // 同时清空 C++ 日志显示
        cppLogTextView.text = "暂无 C++ 算法日志（点击'刷新'查看）"

        Toast.makeText(context, "日志已清空", Toast.LENGTH_SHORT).show()
    }
}

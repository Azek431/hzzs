// 火崽崽助手（HZZS）日志查看页面。
//
// 显示 C++ 视觉识别算法的运行日志，支持：
// - 滚动查看
// - 复制全部内容
// - 导出为文件（CSV/JSON）

package top.azek431.hzzs.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import top.azek431.hzzs.R
import top.azek431.hzzs.core.data.native.VisionBridge

/**
 * 日志查看 Fragment。
 */
class LogViewerFragment : Fragment() {

    companion object {
        fun newInstance() = LogViewerFragment()
    }

    private lateinit var logText: TextView
    private lateinit var scrollContainer: ScrollView

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_log_viewer, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        logText = view.findViewById(R.id.logTextView)
        scrollContainer = view.findViewById(R.id.logScrollView)

        // 刷新日志
        view.findViewById<Button>(R.id.btnRefreshLog).setOnClickListener {
            refreshLog()
        }

        // 复制日志
        view.findViewById<Button>(R.id.btnCopyLog).setOnClickListener {
            copyLog()
        }

        // 导出 CSV
        view.findViewById<Button>(R.id.btnExportCsv).setOnClickListener {
            exportCsv()
        }

        // 导出 JSON
        view.findViewById<Button>(R.id.btnExportJson).setOnClickListener {
            exportJson()
        }

        // 清空日志
        view.findViewById<Button>(R.id.btnClearLog).setOnClickListener {
            clearLog()
        }

        // 初始加载
        refreshLog()
    }

    private fun refreshLog() {
        val count = VisionBridge.getLogCount()
        logText.text = "日志条目数: $count\n\n点击'刷新'查看详细日志内容"
    }

    private fun copyLog() {
        val csv = VisionBridge.getLogCsv()
        if (csv.isEmpty()) {
            Toast.makeText(context, "暂无日志可复制", Toast.LENGTH_SHORT).show()
            return
        }
        val clipboard = context?.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager?
        clipboard?.setPrimaryClip(ClipData.newPlainText("HZZS Log", csv))
        Toast.makeText(context, "日志已复制到剪贴板", Toast.LENGTH_SHORT).show()
    }

    private fun exportCsv() {
        val csv = VisionBridge.getLogCsv()
        if (csv.isEmpty()) {
            Toast.makeText(context, "暂无日志可导出", Toast.LENGTH_SHORT).show()
            return
        }
        val dir = requireContext().externalCacheDir
        val file = java.io.File(dir, "hzzs_log_${System.currentTimeMillis()}.csv")
        file.writeText(csv)
        Toast.makeText(context, "已导出到: ${file.absolutePath}", Toast.LENGTH_SHORT).show()
    }

    private fun exportJson() {
        val json = VisionBridge.getLogJson()
        if (json == "[]") {
            Toast.makeText(context, "暂无日志可导出", Toast.LENGTH_SHORT).show()
            return
        }
        val dir = requireContext().externalCacheDir
        val file = java.io.File(dir, "hzzs_log_${System.currentTimeMillis()}.json")
        file.writeText(json)
        Toast.makeText(context, "已导出到: ${file.absolutePath}", Toast.LENGTH_SHORT).show()
    }

    private fun clearLog() {
        VisionBridge.clearLog()
        logText.text = "日志已清空"
        Toast.makeText(context, "日志已清空", Toast.LENGTH_SHORT).show()
    }
}

package top.azek431.hzzs.mcp

/**
 * MCP 工具中文短标题（设置页 / 审批弹窗 / tools/list 首行）。
 * 键必须与 [McpToolCatalog] 工具名完全一致。
 */
object McpToolLabels {
    private val titles: Map<String, String> = mapOf(
        "get_status" to "读取运行状态",
        "get_runtime_snapshot" to "读取运行态快照",
        "inspect" to "一键诊断聚合",
        "get_settings" to "读取完整设置",
        "preview_settings" to "临时预览设置",
        "save_settings" to "永久保存设置",
        "patch_settings" to "白名单局部改设置",
        "reset_preview" to "清除临时预览",
        "start_analysis" to "启动屏幕分析",
        "stop_analysis" to "停止屏幕分析",
        "navigate" to "打开应用内页面",
        "cancel_actions" to "取消在飞手势",
        "restart_analysis" to "重启屏幕分析",
        "set_overlay_visible" to "临时显示/隐藏悬浮窗",
        "set_capture_backend" to "切换截图后端",
        "set_gesture_backend" to "切换手势后端",
        "get_version" to "读取应用版本与设备信息",
        "check_update" to "检查应用更新",
        "get_metrics" to "读取运行时指标（内存/帧/uptime）",
        "run_diagnostics" to "运行诊断",
        "list_debug_frames" to "列出调试帧元数据",
        "clear_debug_frames" to "清除调试帧",
        "get_debug_frame" to "读取调试帧（base64 JPEG）",
        "capture_debug_frame" to "强制存下一帧调试帧",
        "set_scene" to "切换分析赛季",
        "set_obstacle_enabled" to "启用/禁用障碍类别",
        "set_threshold" to "设置识别阈值",
        "set_theme" to "调整应用主题",
        "set_overlay" to "调整悬浮窗样式",
        "set_developer_enabled" to "开/关开发者选项",
        "set_developer_options" to "调整开发者选项",
        "get_automation_gates" to "解释自动操作门闩",
        "set_automation_enabled" to "开/关自动操作",
        "list_algorithms" to "列出算法包",
        "get_active_algorithm" to "读取当前算法",
        "get_algorithm_pipeline" to "读取算法管线",
        "set_active_algorithm" to "钉选激活算法",
        "refresh_algorithm_catalog" to "刷新算法目录",
        "download_algorithm" to "下载安装算法包",
        "get_logs" to "读取内存日志",
        "clear_logs" to "清空内存日志",
        "export_diagnostics" to "导出脱敏诊断",
        "get_permissions" to "读取系统权限状态",
        "open_system_settings" to "打开系统设置页",
        // MCP 自管
        "get_mcp_status" to "读取 MCP 服务状态",
        "list_mcp_tools" to "列出 MCP 工具与策略",
        "save_profile" to "保存命名配置 profile",
        "load_profile" to "读取 profile（预览/保存）",
        "list_profiles" to "列出 profile 元数据",
        "delete_profile" to "删除 profile",
        "get_events" to "拉取运行时事件",
        "upgrade_algorithms" to "一键升级全部算法包",
        "get_mcp_access_log" to "读取 MCP 访问日志",
        "clear_mcp_access_log" to "清空 MCP 访问日志",
        "set_mcp_enabled" to "开/关 MCP 服务",
        "set_mcp_permission_level" to "设置 MCP 全局权限级",
        "set_mcp_auth" to "设置 MCP Bearer 鉴权",
        "set_mcp_tool_policy" to "设置单工具策略",
    )

    fun titleZh(toolName: String): String = titles[toolName] ?: toolName

    /** 客户端 / 审批用完整描述：中文标题 + 准确工具名 + 详情。 */
    fun clientDescription(tool: McpToolDescriptor): String =
        "${titleZh(tool.name)}\n工具名: ${tool.name}\n${tool.description}"

    fun approvalLabel(toolName: String): String =
        "${titleZh(toolName)}\n工具名: $toolName"
}

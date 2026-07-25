package top.azek431.hzzs.core.model

/**
 * 自动操作决策串 → 简短中文提示。
 *
 * 供运行页与悬浮窗 HUD 共用，避免两处各自维护同一份映射。
 * 决策串来自 [RuntimeStatus.lastAutomationDecision]。
 */
fun humanizeAutomationDecision(raw: String): String {
    val key = raw.substringBefore(' ').substringBefore('=')
    return when {
        raw.startsWith("skip:automation_off") -> "自动操作总开关关闭（免责声明不足时也会被校验关掉）。"
        raw.startsWith("skip:scene_conf") -> "场景置信度低于设置中的最低阈值。"
        raw.startsWith("skip:frame_age") -> "帧分析过慢，当前帧已过期。"
        raw.startsWith("skip:bamboo_experimental_off") ->
            "（已废弃）竹影实验锁不再拦截；请检查总开关与手势后端。"
        raw.startsWith("skip:action_in_flight") -> "上一动作尚未结束。"
        raw.startsWith("skip:no_player") -> "未识别到玩家参考，无法测距触发。"
        raw.startsWith("skip:no_candidate") -> "没有稳定且可动作的障碍进入触发距离。"
        raw.startsWith("skip:ledger") -> "同一目标冷却中（刚成功点过，约 1 秒内不重复）。"
        raw.startsWith("skip:no_accessibility") -> "无障碍服务未连接（当前手势后端需要无障碍）。"
        raw.startsWith("skip:no_foreground") -> "手势后端读不到前台窗口（无障碍未连/Shell dumpsys 失败）。"
        raw.startsWith("skip:package_gate") -> "已开启「仅允许指定应用」，当前前台不在列表中。"
        raw.startsWith("skip:foreground_gate") -> "前台包名无效或状态不可用。"
        raw.startsWith("plan ") -> "已选中候选，正在规划/派发手势。"
        raw.startsWith("dispatch_ok") || raw.contains("dispatch_ok") -> "手势已成功注入（Shell input 或无障碍）。"
        raw.startsWith("dispatch_fail") || raw.contains("dispatch_fail") ->
            "手势注入失败（看 detail：input 路径/exit 码/超时）。"
        raw.startsWith("dispatch_skip:package_gate") -> "派发时前台包不在允许列表。"
        raw.startsWith("dispatch_skip:foreground_stale") -> "派发时前台快照已过期。"
        raw.startsWith("dispatch_skip:foreground_recheck") -> "派发时前台窗口与规划时不一致。"
        raw.startsWith("dispatch_skip:ledger") -> "派发时账本冷却：同目标刚成功过。"
        raw.startsWith("dispatch_skip:rate_limit") -> "达到每秒动作上限。"
        raw.startsWith("dispatch_skip:empty_plan") -> "该障碍无规避动作（Avoidance.NONE）。"
        raw.startsWith("dispatch_skip:no_foreground") -> "派发时前台不可用（检查手势后端 / dumpsys）。"
        raw.startsWith("dispatch_abort") -> "派发过程中自动操作已关闭。"
        else -> "决策：$key"
    }
}

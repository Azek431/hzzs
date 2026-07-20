package top.azek431.hzzs.core.model

/** 用户可见中文文案，避免界面直接展示枚举名。 */
fun SceneId.displayName(): String = when (this) {
    SceneId.SWEET_FACTORY -> "甜甜圈"
    SceneId.BAMBOO_BOOKSTORE -> "竹影书屋"
}

fun CaptureBackend.displayName(): String = when (this) {
    CaptureBackend.AUTO -> "自动推荐"
    CaptureBackend.MEDIA_PROJECTION -> "屏幕录制"
    CaptureBackend.ACCESSIBILITY -> "无障碍截图"
    CaptureBackend.SHIZUKU -> "Shizuku"
    CaptureBackend.ROOT -> "Root"
}

fun McpPermissionLevel.displayName(): String = when (this) {
    McpPermissionLevel.READ_ONLY -> "只读"
    McpPermissionLevel.ASK_EVERY_TIME -> "每次确认"
    McpPermissionLevel.TRUSTED_SESSION -> "信任本次会话"
    McpPermissionLevel.FULL_ACCESS -> "完整访问"
}

fun detectionKindDisplayName(kindName: String): String = when (kindName) {
    "PLAYER" -> "玩家"
    "POISON_BOTTLE" -> "毒药瓶"
    "CAKE_STRUCTURE" -> "蛋糕结构"
    "HANGING_SPIKE" -> "悬挂尖刺"
    "PIT" -> "地坑"
    "PANDA_STATUE" -> "熊猫摆件"
    "BAMBOO_GAP" -> "竹林缺口"
    "HANGING_BRUSH" -> "悬挂毛笔"
    else -> kindName
}

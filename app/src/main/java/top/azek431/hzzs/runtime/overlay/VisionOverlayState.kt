package top.azek431.hzzs.runtime.overlay

import top.azek431.hzzs.runtime.vision.FrameMapping
import top.azek431.hzzs.runtime.vision.VisionFrameResult

data class VisionOverlayState(
    val result: VisionFrameResult? = null,
    val frame: FrameMapping? = null,
    val captureMode: String = "--",
    val fps: Float = 0f,
    val totalCostMs: Float = 0f,
    val status: String = "初始化",
    val lastAction: String = "WAIT",
    val detailed: Boolean = true,
)

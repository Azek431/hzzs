package top.azek431.hzzs.domain.vision

/**
 * 为已经通过 Native 与 Validator 门禁的检测框生成低点数近似显示轮廓。
 *
 * 重要语义：
 * - 本函数属于 **App 呈现层** 的数据增强，不是算法包/找色引擎的一部分。
 * - 算法只提供 [Detection.bounds] 等计算结果；此处生成的轮廓仅供 HUD 绘制。
 * - 动作、追踪、触发距离继续只读 [Detection.bounds]。
 * - 所有点都被限制在 bounds 内；每种障碍保留 left/top/right/bottom 极值。
 */
fun Detection.withApproximateDisplayContour(): Detection {
    if (kind == ObjectKind.PLAYER) return copy(displayContour = emptyList())
    return copy(displayContour = approximateDisplayContour(kind, bounds))
}

fun approximateDisplayContour(
    kind: ObjectKind,
    bounds: NormalizedRect,
): List<NormalizedPoint> {
    if (kind == ObjectKind.PLAYER) return emptyList()

    val left = bounds.left
    val top = bounds.top
    val width = bounds.width
    val height = bounds.height

    fun point(xRatio: Float, yRatio: Float): NormalizedPoint = NormalizedPoint(
        x = (left + width * xRatio.coerceIn(0f, 1f)).coerceIn(bounds.left, bounds.right),
        y = (top + height * yRatio.coerceIn(0f, 1f)).coerceIn(bounds.top, bounds.bottom),
    )

    return when (kind) {
        ObjectKind.PLAYER -> emptyList()
        ObjectKind.GREEN_BOTTLE -> listOf(
            point(.34f, 0f), point(.66f, 0f),
            point(.69f, .11f), point(.80f, .18f),
            point(.90f, .39f), point(1f, .62f),
            point(.95f, .91f), point(.78f, 1f),
            point(.22f, 1f), point(.05f, .91f),
            point(0f, .62f), point(.10f, .39f),
            point(.20f, .18f), point(.31f, .11f),
        )
        ObjectKind.CAKE_STRUCTURE,
        ObjectKind.SAND_CASTLE,
        -> listOf(
            point(.05f, 0f), point(.95f, 0f), point(1f, .08f),
            point(1f, 1f), point(0f, 1f), point(0f, .08f),
        )
        ObjectKind.HANGING_SPIKE -> listOf(
            point(.25f, 0f), point(.75f, 0f), point(1f, .18f),
            point(.79f, .31f), point(.63f, .78f), point(.50f, 1f),
            point(.37f, .78f), point(.21f, .31f), point(0f, .18f),
        )
        ObjectKind.PIT,
        ObjectKind.BAMBOO_GAP,
        ObjectKind.SEA_PIT,
        -> listOf(
            point(0f, 0f), point(1f, 0f),
            point(1f, 1f), point(0f, 1f),
        )
        ObjectKind.PANDA_STATUE -> listOf(
            point(.17f, .11f), point(.29f, 0f), point(.42f, .12f),
            point(.58f, .12f), point(.71f, 0f), point(.83f, .11f),
            point(1f, .43f), point(.93f, .84f), point(.78f, 1f),
            point(.22f, 1f), point(.07f, .84f), point(0f, .43f),
        )
        ObjectKind.HANGING_BRUSH,
        ObjectKind.HANGING_ANCHOR,
        -> listOf(
            point(.37f, 0f), point(.63f, 0f),
            point(.66f, .54f), point(1f, .70f),
            point(.66f, .86f), point(.50f, 1f),
            point(.34f, .86f), point(0f, .70f),
            point(.34f, .54f),
        )
    }
}

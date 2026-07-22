# HZZS v0.9.2 完成驱动与轮廓显示集成

本集成基于当前源码（HEAD `aed0dfe` 起）落地完成驱动取帧、HUD 临时隐身与近似显示轮廓。

## 完成驱动

运行循环不再根据 `frameRateLimit` 主动丢弃帧。上一轮分析完成后，直接等待
`FrameSource.nextFrame(lastSequence)` 返回最新新帧。MediaProjection 本身使用
`Channel.CONFLATED + acquireLatestImage()`，不会积压完整历史帧。

当 HUD 正在显示时：

1. 在主线程把已挂载 View 临时设为 `INVISIBLE`。
2. 等待一次 Choreographer 提交，不使用猜测性的固定毫秒 sleep。
3. 对 MediaProjection/AUTO 丢弃一张可能仍含旧合成层的帧。
4. 取得下一张干净帧缓冲。
5. 立即恢复旧 HUD，再在后台分析已独立持有的像素缓冲。

Accessibility / Root / Shizuku 不做额外排空，避免昂贵的重复截图。

## 近似轮廓

`Detection.bounds` 仍是动作与跟踪的唯一几何真相源。新增的
`displayContour` 只供 HUD 使用。类别专属模板的所有点均位于 bounds 内，且保留
left/top/right/bottom 四个极值。

HUD 使用 `clipPath` 把填充与描边裁剪在轮廓内部，避免描边向轮廓外扩散。

## 像素轮廓边界

当前 C++ `hzzs::Detection` 与 JNI `NativeVision.Detection` 仍只返回矩形 bounds，
所以本集成不声称 PIXEL 已经迁移。近似轮廓在 Tracker 完成平滑之后生成，
保证轮廓与最终显示 bounds 同步；后续像素轮廓也必须在同一阶段完成坐标映射。

## 赛季范围

当前源码只定义 SWEET_FACTORY 与 BAMBOO_BOOKSTORE，及 7 种障碍。本集成不擅自
加入海盐客厅枚举，避免破坏 C++ Kind、JNI 位掩码、设置迁移和算法包 schema。

## 相对原 ZIP 补丁的加固

- `hideInternal` 移除 View 前先恢复 `VISIBLE`，避免下次 `addView` 继承隐身状态。
- `resumeAfterCapture` 校验 View 身份，避免会话切换后恢复已卸载视图。
- 轮廓单测改用项目既有 JUnit4，避免 `kotlin.test` 依赖缺失。

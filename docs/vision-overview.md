# 视觉识别架构总览

HZZS 的视觉识别模块提供了一套完整的画面分析管线，包括截图采集、颜色检测、结果可视化和自动操作触发。

## 架构分层

```
┌─────────────────────────────────────────────────────┐
│  调度层  VisionDetectionController                    │
│  - startLoop() / stopLoop() / runOnce()              │
│  - 截图 → 分析 → 操作 → 绘制 完整生命周期             │
├─────────────────────────────────────────────────────┤
│  截图层  ScreenshotCapture                           │
│  - AccessibilityService.takeScreenshot()             │
│  - API 31+，支持 HardwareBuffer / ByteBuffer         │
├─────────────────────────────────────────────────────┤
│  算法层  Detectors                                    │
│  ├── GreenBottleLineDetector  (绿瓶 RGB 检测)        │
│  └── PitLineDetector              (坑位地面断裂检测)  │
├─────────────────────────────────────────────────────┤
│  绘制层  VisionDebugOverlayView                       │
│  - 扫描线 / 检测结果 / 置信度 / 耗时面板              │
├─────────────────────────────────────────────────────┤
│  操作层  AutoActionQueue + GestureInjector            │
│  - 检测结果 → 自动操作指令 → 手势注入                  │
└─────────────────────────────────────────────────────┘
```

## 数据流

```
ScreenshotCapture.takeScreenshot()
    → VisionFrame(pixels, w, h, density)
    → GreenBottleLineDetector.detect()
    → GreenBottleDetection(leftX, rightX, centerX, confidence, costMs)
    → PitLineDetector.detect()
    → PitDetection(left, right, center, width, confidence, costMs)
    → VisionDetectionController.generateActions()
    → ActionTrigger(JUMP/TAP/SLIDE/DOWN)
    → AutoActionQueue.enqueue()
    → GestureInjector.inject()
    → dispatchGesture()
```

## 设计原则

1. **截图层只负责截图** — 不分析、不绘制
2. **算法层只负责识别** — 每个 Detector 独立实现，互不耦合
3. **调度层只负责生命周期** — 循环/单次/停止、截图→分析→操作→绘制
4. **绘制层只负责画结果** — 纯函数式扩展函数
5. **配置层只负责参数** — VisionSettingsKeys 管理所有参数

## 线程模型

- 循环执行在独立后台线程运行
- 截图、分析、操作触发均在后台线程
- 绘制回调和自动操作触发在主线程
- 检测器无状态，可安全在任意线程调用

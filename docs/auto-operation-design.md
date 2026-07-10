# 自动操作架构设计

## 概述

HZZS 的自动操作模块通过图像识别结果触发触摸事件注入，实现自动跳跃、自动点击等辅助功能。

## 架构

```
VisionDetectionController
    │
    ▼
generateActions() ──→ ActionTrigger[JUMP/TAP/SLIDE/DOUBLE_JUMP]
    │
    ▼
AutoActionQueue.enqueue(QueuedAction)
    │
    ▼
AutoOperationService (AccessibilityService)
    │ 定时调度（operationDelayMs）
    ▼
GestureInjector.inject()
    │ 归一化坐标 → 像素坐标
    ▼
dispatchGesture(GestureDescription)
    │ 10ms 短触摸
    ▼
游戏画面
```

## 输入注入方案

### 已实现：AccessibilityService + dispatchGesture

| 特性 | 说明 |
| --- | --- |
| 原理 | 通过 AccessibilityService.dispatchGesture() 注入触摸事件 |
| 优势 | 官方 API，无需 root，兼容性最好 |
| 劣势 | 需要无障碍权限，部分游戏可能拦截 |
| 适用 | 所有 API 24+ 设备 |

### 规划中方案

| 方案 | 难度 | 推荐度 | 说明 |
| --- | --- | --- | --- |
| Shizuku + ADB | 高 | ⭐⭐⭐⭐ | 需要安装 Shizuku，通过 ADB 注入 |
| MediaProjection + Root | 极高 | ⭐⭐⭐ | 需要 root 权限 |
| 虚拟屏幕 | 极高 | ⭐ | 需要系统签名 |

## 防误触机制

1. **置信度阈值** — 检测置信度低于阈值时不触发操作
2. **连续确认** — 可配置连续 N 帧一致才触发
3. **灵敏度调节** — 通过 antiMissSensitivity 参数控制

## 手动确认模式

半自动模式下：
- 每次操作前在悬浮窗显示确认提示
- 用户可选择确认/跳过本次操作
- 默认关闭

## 响应延迟

- 从识别到动作触发的总延迟控制在 0~300ms
- 通过 VisionSettingsKeys.KEY_LOOP_INTERVAL_MS 调节
- 默认 300ms（可通过设置界面调整为 20~2000ms）

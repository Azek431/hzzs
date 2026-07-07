# HZZS Native C++ Core

这里是 HZZS 的 C++ 算法基础层。

## 后续代码放置位置

| 内容 | 推荐目录 |
| --- | --- |
| 跑酷姿态、障碍 ETA、糖果追踪、轨迹预测算法 | `src/analysis/` |
| 对外数据结构、算法接口、公共几何结构 | `include/hzzs/analysis/` |
| Kotlin 与 C++ 的 JNI 通信 | `src/jni/` |
| 后续图像处理、颜色检测、模板匹配、OpenCV 封装 | `src/vision/` 与 `include/hzzs/vision/` |
| 后续性能工具、帧时间统计、调试辅助 | `src/util/` 与 `include/hzzs/util/` |

## 当前已实现的算法基础

- 逻辑坐标矩形 `RectF`
- 玩家姿态状态机：跑步、上跳、滞空、下落、下滑
- 基于 ETA 与置信度的跳跃 / 下滑提示
- 两帧连续确认，降低单帧抖动误提示
- Native 自检入口，验证基础状态机与提示引擎

当前没有接入屏幕采集、图像识别、OpenCV、游戏控制或自动操作。

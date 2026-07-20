# C++ 视觉引擎

视觉引擎接收 RGBA 像素、视口和场景参数，输出归一化检测框。甜甜圈与竹影书屋共享坐标体系，但使用独立颜色与几何检测器。

库名：`hzzs_vision`（`System.loadLibrary("hzzs_vision")`）。

场景序号与 Kotlin `SceneId.ordinal` 一致：

| scene | 赛季 |
| --- | --- |
| 0 | 甜甜圈 `SWEET_FACTORY` |
| 1 | 竹影书屋 `BAMBOO_BOOKSTORE` |

## 设计原则

- 输入最大边 4096，像素总数不超过 8,388,608。
- 工作路径以 stride / workWidth 控制采样密度，检测结果映射回视口比例。
- 障碍类别通过位掩码过滤，关闭的类别不执行对应检测分支。
- 固定玩家比例模式跳过玩家检测；检测一次与连续检测由 Kotlin 运行时控制。
- 所有面积、乘法、缓冲索引和 JNI 数组长度在使用前校验。
- Native 不持有 Java 数组或 Bitmap 生命周期之外的内存。
- `reset()` 当前可为空操作；跨帧状态在 Kotlin tracker / runtime。

## 与历史 main

- `legacy_main/vision2` 与 `legacy_main/vision_bamboo` 为历史 main 检测核心（无旧 JNI 类名）。
- `vision_engine.cpp` 优先调用上述引擎，再映射到统一 `Detection` / 位掩码协议；失败时回退 `sweet_factory.cpp` / `bamboo_bookstore.cpp`。
- 历史 `main` 的 analysis 状态机与模拟 HUD 路径不再编译。

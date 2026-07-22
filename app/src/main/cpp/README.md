# C++ 算法引擎

算法引擎接收 RGBA 像素、视口和**赛季参数**，输出归一化检测框。  
**一套引擎 + 三份赛季参数**（甜品 / 竹影 / 海盐）；玩法不变时优先改参数而非换引擎。

库名：`hzzs_vision`（`System.loadLibrary("hzzs_vision")`）。

场景序号与 Kotlin `SceneId.ordinal` 一致：

| scene | 赛季 |
| --- | --- |
| 0 | 甜品工厂 `SWEET_FACTORY` |
| 1 | 竹影书屋 `BAMBOO_BOOKSTORE` |
| 2 | 海盐客厅 `SEA_SALT_LIVING_ROOM` |

## 设计原则

- 输入最大边 4096，像素总数不超过 8,388,608。
- 工作路径以 stride / workWidth 控制采样密度，检测结果映射回视口比例。
- 障碍类别通过位掩码过滤，关闭的类别不执行对应检测分支。
- 固定玩家比例模式跳过玩家检测；检测一次与连续检测由 Kotlin 运行时控制。
- 所有面积、乘法、缓冲索引和 JNI 数组长度在使用前校验。
- Native 不持有 Java 数组或 Bitmap 生命周期之外的内存。
- 算法参数经 `AlgorithmRuntime` 不可变快照注入；`configureAlgorithm` 与 `analyze` 串行，失败保留旧配置或由 Kotlin 回退 `builtin.hzzs.base`。
- `reset()` 不强制回退算法；跨帧状态在 Kotlin tracker / runtime。算法回退使用 `configure_builtin` / `configureAlgorithm(builtin)`。

## 路径

- 甜品 / 竹影：`legacy_main/vision2` 与 `legacy_main/vision_bamboo` 为主路径；过弱时回退 `sweet_factory.cpp` / `bamboo_bookstore.cpp`。
- 海盐：`sea_salt_living_room.cpp` 参数驱动主路径（沙堡 / 船锚 / 海坑）。
- `vision_engine.cpp` 统一调度并映射 `Detection` / 位掩码；尺寸窗后过滤读 profile。
- 宽 cake 输出 `PIT`，窄 cake 输出 `CAKE_STRUCTURE`；竹隙优先 `BAMBOO_GAP`；海坑优先 `SEA_PIT`。
- 宿主机脚本 `tools/vision/build_host.sh`、`build_host.ps1` 与本目录 `CMakeLists.txt` 源文件列表保持一致。

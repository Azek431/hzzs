# C++ 修改指南

1. 新增障碍时同时更新 `vision_types.h`、场景检测器、JNI 编码、Kotlin `ObstacleKind` 和数据集报告。
2. 不使用固定屏幕像素作为业务阈值；阈值以工作图尺寸、玩家宽度或视口比例表达。
3. 热路径避免分配：复用工作缓冲、组件队列和结果容器。
4. 所有缩放和坐标转换明确写出源坐标系与目标坐标系。
5. 修改后运行宿主机测试、ASan/UBSan、代表帧与 444 张数据集回归。
6. 没有人工真值时只报告稳定性和速度，不宣称精确率或召回率。
7. **算法诊断（默认关闭）**：`Result` 的 `timing` / `filtered_out` / `multicolor_diag` 由 `analyze_with_profile` 在各阶段边界采样，经 JNI 回传 Kotlin；默认全 0/空，仅 `developer.enableStageTiming` / `enableMulticolorDiagnostic` / `enableFilterTrace` 开启时有效。多点找色诊断只记录命中/拒绝原因与阈值，不记录模板 RGB 资产。

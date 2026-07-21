# 统一重构进度

分支：`chatgpt/rewrite-v0.2.0-unified`（相对历史 `main` 的整仓统一架构）。  
首发版本目标：**v0.1.0** / `versionCode = 1`（尚未正式打 tag 发布）。

## 已实现

- 单一 `app` Android 模块与职责分包（Compose + Hilt）。
- Android 7+ 低权限 MediaProjection 默认路径；`AUTO` 不升权。
- Material 3 首次引导、主题包、悬浮窗样式、设置预览和开发者入口。
- MCP 四级权限、回环监听、随机令牌和应用内语义操作。
- 两赛季障碍类别过滤、比例坐标和三种玩家基准模式。
- **默认赛季：竹影书屋**。
- C++ 视觉入口、输入边界、JNI 失败隔离与宿主机测试脚手架。
- 项目级静态门禁、Native sanitizer 与数据集评估工具。
- 双源签名更新库与发布脚本（`tools/release/*`）。

## 进行中 / 对齐历史 main

历史 `main` 曾包含更完整的 `vision2` / `vision_bamboo` 检测与 `VisionActionPlanner` 阈值。统一重构以清晰架构为先，下列项按优先级推进：

| 优先级 | 项 | 状态 |
| --- | --- | --- |
| P0 | 从 main 移植 vision2/bamboo 检测核心并映射统一协议 | 已落地（含启发式回退） |
| P0 | 重写 arm 门控 + main 动作距离 / 双跳 / 竹影实验锁 | 已落地 |
| P0 | PIT / GAP 单语义输出，避免双写双动作 | 已落地 |
| P0 | 动作任务 join/CAS、帧龄门控、retryLimit | 已落地 |
| P1 | SettingsEditSession debounce/flush/ignore 走 session | 已落地 |
| P1 | requireSessionArm 生效并暴露设置项 | 已落地 |
| P1 | MCP 配置指纹变化才重启；overlay 签名补全；runtime 侧跳过 show | 已落地 |
| P1 | 高级截图后端帧池复用 | 已落地 |
| P1 | 应用内更新检查 / 下载 / 安装 UI | 已落地 |
| P1 | 文档、CHANGELOG、AGENTS 与代码一致 | 进行中 |
| P2 | Shizuku screencap 适配器（非 AUTO） | 已落地（需真机授权验证） |
| P2 | 悬浮窗未变跳过重绘 / Tracker 上限 / MCP 启停 | 已落地 |
| P2 | 设备矩阵与厂商 ROM 报告 | 未完成 |
| P2 | 数据集人工真值与召回评估 | 未完成 |

## 必须在设备或完整 Android SDK 环境验证

- `clean testDebugUnitTest lintDebug assembleDebug`
- API 24 至目标版本模拟器启动与权限状态
- 中国厂商 ROM 的后台、悬浮窗和前台服务行为
- Root 截图性能与稳定性
- Shizuku 在真实设备上的授权与帧率
- 与历史 main 在同一批截图上的检测 / 动作差异对照

## 明确未宣称

- 444 张（或任意数量）数据集在缺少独立人工真值时，**不能**证明 99% 准确率或全机型覆盖。
- 未发布的更新索引上，应用内“检查更新”失败是预期行为。

## 版本叙事

| 名称 | 含义 |
|---|---|
| 分支名 `rewrite-v0.2.0-unified` | 工程内部重构代号，**不是**已发布的 0.2.0 |
| 产品首发版本 | **0.1.0** / code **1** |
| `main` 历史线 | 多模块 Views + 叠层视觉运行时；本分支以其为算法供体与行为对照基线 |

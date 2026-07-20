# 变更日志

所有值得关注的变更都将记录在此文件中。

格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)。

版本策略：项目**尚未正式发布**。首个对外版本目标为 **v0.1.0**（`versionCode = 1`）。  
当前开发线在分支 `chatgpt/rewrite-v0.2.0-unified` 上完成统一重构，合并发布前版本号仍记为 0.1.0。

## [Unreleased]

### 新增

- 单一 `app` Gradle 模块与 Compose + Hilt 产品壳（引导 / 首页 / 运行 / 设置 / 关于）。
- DataStore 配置 schema v5：主题、悬浮窗、视口、双赛季、自动操作、MCP、开发者与更新项。
- 设置页临时预览与“保存并应用”；权限型配置（截图后端、MCP、自动操作、开发者、更新）不在预览阶段启动。
- MediaProjection 默认截图路径；`CaptureBackend.AUTO` 仅委托低权限录屏，不探测 Root / Shizuku / 无障碍。
- 可选无障碍截图（API 30+）与 Root 截图（超时与尺寸限制）；Shizuku 入口保留。
- C++ 双赛季视觉引擎（甜甜圈 / 竹影书屋）、位掩码类别过滤、比例坐标与 JNI 失败隔离。
- 跨帧 `MultiObjectTracker`、悬浮窗三档样式、会话 arm 自动操作门控与 `GestureArbiter`。
- 本地 MCP（loopback、随机 Bearer、四级权限、语义工具）。
- `.hzzstheme` 声明式主题包；关于页赞赏图保存与开发者手势解锁。
- Gitee 优先双源签名更新库、差分补丁与发布工作流工具。
- 项目静态门禁、Native ASan/UBSan、宿主机数据集评估脚本与 JVM 单元测试。

### 变更

- 默认赛季改为 **竹影书屋**（与历史 main 线默认一致）。
- 默认 `versionCode` 固定为 **1**，`versionName` 为 **0.1.0**。
- 文档体系收敛为 `README` / `CLAUDE` / `AGENTS` / `docs/{ARCHITECTURE,SECURITY,TESTING,PROGRESS}`。

### 安全

- 自动操作默认关闭；导入与迁移不得静默开启；需当前免责声明版本。
- MCP 仅回环监听；写操作默认每次确认。
- 主题包不执行脚本、不加载远程资源。
- 截图帧、MCP 令牌与 DataStore 配置不进入系统云备份。

### 修复 / 对齐

- 接入历史 main 的 vision2 / bamboo 检测核心，并映射到统一归一化 Detection 协议。
- 自动操作加入 main 风格触发距离、双跳时序、下滑 TTL 与竹影实验锁。
- 设置页补齐更新检查 / 下载 / 安装与忽略版本。
- Shizuku 截图后端：`screencap -p`（用户显式选择，AUTO 仍不升权）。

### 已知限制

- 正式 GitHub/Gitee Release 与签名更新索引尚未发布。
- 数据集缺少独立人工真值，不得宣称准确率指标。
- 厂商 ROM、Root、Shizuku 与真实游戏链路需真机验证。
- `Shizuku.newProcess` 依赖设备端 Shizuku 版本与授权状态。

## [0.1.0] — 未发布

首发目标版本。在 tag `v0.1.0` 正式发布前，请以 `[Unreleased]` 与 `docs/PROGRESS.md` 为准。

### 计划纳入首发说明的能力摘要

- 本地画面分析与可配置悬浮窗
- 双赛季障碍识别与受控自动操作
- 低权限默认截图与可选高级后端
- 本地 MCP 与双源更新工具链

---

历史说明：仓库早期骨架（Views、模拟 HUD、多 Gradle 模块）已由统一重构分支取代；不再单独保留 2025-12 旧条目中与现状冲突的“未接入 MediaProjection”等描述。

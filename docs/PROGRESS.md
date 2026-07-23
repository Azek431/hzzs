# 统一重构进度

当前主干：`main`（整仓统一架构已落地；历史重构代号曾用 `chatgpt/rewrite-v0.2.0-unified`）。  
首发版本目标：**v0.1.0** / `versionCode = 1`（尚未正式打 tag 发布）。

## 已实现

- 单一 `app` Android 模块与职责分包（Compose + Hilt）。
- Android 7+ 低权限 MediaProjection 默认路径；`AUTO` 不升权。
- Material 3 首次引导、主题包、悬浮窗样式、设置即时落盘和开发者入口。
- MCP 四级权限、IPv4 `127.0.0.1` 回环监听、可选 Bearer（`requireAuth`）和应用内语义操作；Streamable HTTP 握手与会话表、keep-alive、严格工具 schema、连接背压；同机 RikkaHub 导入 JSON。
- 三赛季障碍类别过滤、比例坐标和三种玩家基准模式（甜品 / 竹影 / 海盐）。
- 默认赛季见源码 `AppConfig.DEFAULT_SELECTED_SCENE`（进度文档不重复写死赛季名）。
- C++ 算法引擎入口、海盐参数路径、输入边界、JNI 失败隔离与宿主机测试脚手架。
- **JNI 三赛季闸门对齐**：`jni_bridge` analyze 入口与 `kSceneCount` 一致（修复海盐 `invalid scene` 真机连续失败）。
- 项目级静态门禁、Native sanitizer 与数据集评估 / 批跑工具。
- 双源签名更新库与发布脚本（`tools/release/*`）。
- 官方算法包系统 v1 工具链（`tools/algorithm/*`、`algorithm-packs/*`、`algorithm-release.yml`）；真实算法目录尚未对外发布。
- **算法引擎 + 赛季参数**：内置 `builtin.hzzs.base` **0.1.0**（Catalog `builtin-hzzs-base-0.1.0`）；`POISON_BOTTLE`→`GREEN_BOTTLE`；海盐 `SAND_CASTLE`/`HANGING_ANCHOR`/`SEA_PIT`。

## 进行中 / 对齐历史 main

历史 `main` 曾包含更完整的 `vision2` / `vision_bamboo` 检测与 `VisionActionPlanner` 阈值。统一重构以清晰架构为先，下列项按优先级推进：

| 优先级 | 项 | 状态 |
| --- | --- | --- |
| P0 | 从 main 移植 vision2/bamboo 检测核心并映射统一协议 | 已落地（含启发式回退） |
| P0 | 三赛季协议 + 海盐参数路径接入引擎调度 | 已落地（统一扫描引擎完整 1:1 研究版仍可深化） |
| P0 | 重写 arm 门控 + main 动作距离 / 双跳 / 竹影实验锁 | 已落地（海盐沿用竹影距离档） |
| P0 | PIT / GAP 单语义输出，避免双写双动作 | 已落地 |
| P0 | 动作任务 join/CAS、帧龄门控、retryLimit | 已落地 |
| P1 | 设置即时落盘（方案 C）+ 离开刷盘 + 首页分组/搜索 | 已落地（危险项仍确认；导入/MCP harden 不变） |
| P1 | requireSessionArm 移除（不再每次会话手动解锁，启用后直接规划手势） | 已变更 |
| P1 | MCP 配置指纹变化才重启；overlay 签名补全；runtime 侧跳过 show | 已落地 |
| P1 | 高级截图后端帧池复用 | 已落地 |
| P1 | 应用内更新检查 / 下载 / 安装 UI | 已落地 |
| P1 | 设置首页 + 分类子页重构（算法选择 / 网络更新） | 已落地（UI + 目录/下载/验签安装路径；远端 release-index 目录尚未发布时为空态） |
| P1 | 文档、CHANGELOG、AGENTS 与代码一致 | 进行中（设置即时落盘、无会话 arm、MCP requireAuth 已对齐） |
| P1 | 声明式算法运行时（AlgorithmRuntimeProfile / 安全切换） | 已落地（外装包需信任锚公钥 + release-index 目录；主路径颜色谓词仍部分硬编码） |
| P1 | 算法包闭环 M0 | 已落地：APK 安装前 verifyPackage、忽略版本不整稿保存、更新源偏好、UI/文档诚实 |
| P1 | 算法包闭环 M1 | 已落地：rules v2 双段 tools/示例包、`AlgorithmRulesParser`、统一 `AlgorithmIds` |
| P1 | 算法包闭环 M2–网络 | 已落地：`AlgorithmNetworkClient` HTTPS 目录/下载、`AlgorithmPackVerifier` Ed25519、Catalog 接真链路；**信任锚公钥列表默认为空（fail-closed）** |
| P1 | 算法包闭环 M4 | 已落地：启动 `algorithm.autoCheck` 刷新目录；`autoDownload` 在有信任锚时尝试下载最新 |
| P2 | 主路径参数化 | 部分：M3A confidence/floor；主路径尺寸窗**后过滤**；颜色谓词核心仍硬编码（legacy） |
| P2 | Shizuku screencap 适配器（非 AUTO） | 已落地（需真机授权验证） |
| P2 | 悬浮窗未变跳过重绘 / Tracker 上限 / MCP 启停 | 已落地 |
| P2 | 完成驱动取帧 + HUD 临时隐身 + 近似显示轮廓 | 已落地（非 C++ 像素轮廓；动作仍只读 bounds） |
| P2 | 系统权限引导 + 悬浮窗双层绘制 | 已落地：`SystemCapabilityAccess`；设置/引导/运行页权限入口；`overlayBlockReason`；双 Window（穿透框 + 可拖 HUD） |
| P2 | UI/动效深化：Motion Policy、导航转场、令牌断点、文案起步 | 进行中（Motion/引导步骤/设置分类壳/引导文案/颜色对比工具/赞赏 Dialog 已落地；设置子页全文案、HUD 字号、Roborazzi 未做） |
| P2 | 开发者设置补齐 + AppLog + 诊断导出 + 日志查看器 + 算法流程页 | 已落地（会话阶段可视化 + 最近一帧摘要；无文件日志；无 C++ 热路径逐步日志） |
| P1 | 设置页拆分为独立分类：MCP 服务（普通用户可访问）与开发者选项分离；连接引导、状态卡片、一键复制信息 | 已落地 |
| P1 | MCP 协议与安全加固：initialize/initialized、Mcp-Session-Id、TRUSTED_SESSION 内存会话、并发上限、严格 inputSchema、错误码分类、停止拒绝挂起审批 | 已落地 |
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
- 设置页算法「检查/下载」在远端 `release-index` 目录与信任锚未发布时，**不会**改变识别结果；分析默认内置 `builtin.hzzs.base` **0.1.0**。
- 算法包 engine 参数在主路径全参数化完成前，对常态检测收益有限（主要影响启发式回退与部分 floor）。

## 版本叙事

| 名称 | 含义 |
| --- | --- |
| 历史重构代号 `rewrite-v0.2.0-unified` | 工程内部代号，**不是**已发布的 0.2.0；当前工作在 `main` |
| 产品首发版本 | **0.1.0** / code **1** |
| 更早历史线 | 多模块 Views + 叠层视觉运行时；现 `main` 以其为算法供体与行为对照基线 |

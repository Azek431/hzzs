# 变更日志

所有值得关注的变更都将记录在此文件中。

格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)。

版本策略：项目**尚未正式发布**。首个对外版本目标为 **v0.1.0**（`versionCode = 1`）。  
当前开发线在分支 `chatgpt/rewrite-v0.2.0-unified` 上完成统一重构，合并发布前版本号仍记为 0.1.0。

## [Unreleased]

### 新增

- **算法网络与验签安装**：`AlgorithmNetworkClient`（HTTPS 目录/资产）、`AlgorithmPackVerifier`（ZIP 白名单 + Ed25519，BouncyCastle）、`AlgorithmTrustAnchors`（内置信任锚，默认空=拒绝外装）。
- **算法包 rules schema v2（双段）**：`userThresholds` + `engineParams`；tools 兼容 v1；示例包 `official-bamboo-baseline` 升 v2；Kotlin `AlgorithmRulesParser` 合成双场景 profile 并填洞。
- **算法安装/激活骨架**：`InstalledAlgorithmStore`（filesDir 落盘）、`AlgorithmActivationCoordinator`（save/start 解析 pin/AUTO）；统一 `AlgorithmIds`。
- **主路径尺寸后过滤 + M3A**：profile 尺寸窗剔除越界检测；甜甜圈 scene_confidence 质量度量；竹影 player floor / workWidth 校验。
- **开发者设置补齐与诊断日志**：设置「MCP 与开发者」对齐关于页能力（强制截图后端、调试帧刷新/清除、日志级别、Native 自检、诊断导出）；`AppLog` 内存 ring buffer + 脱敏；`DiagnosticsExporter` 导出版本/机型/配置摘要/最近日志（不含 Bearer 与调试帧像素）；`DeveloperConfig.logLevel` 持久化。
- **Motion Policy 与导航转场**：`HzzsMotionPolicy` 统一消费 `animationScale` / `reduceMotion` 与系统 animator 倍率；一级导航 fade-through、设置分类 shared-axis X、引导步骤 AnimatedContent；减少动效时即时终态。设计 token 增加断点与 `contentMaxWidth`；`HzzsScrollPage` 宽屏限宽；设置保存栏窄屏纵向动作区。
- **文案资源化深化**：一级导航、通用动作、MCP 审批、设置分类/首页、引导全流程、关于赞赏对话框、首页与运行控制台迁入 `strings.xml`。
- **颜色对比工具**：`HzzsColorContrast`（WCAG 相对亮度/对比度/合成）与 JVM 单测；外观自定义色输入显示与白底对比提示。
- **无障碍小改进**：`PageHeader` / `HzzsSection` 标题 heading 语义；赞赏改为标准 Dialog + 显式「保存到相册」。
- **完成驱动取帧与 HUD 近似轮廓**：分析循环按上一轮完成拉取最新帧；MediaProjection/AUTO 在 HUD 显示时临时隐身并排空可能含旧合成层的一帧；`displayContour` 仅供 HUD 绘制，动作与 Tracker 仍只读 `bounds`。
- **UI 工具专业风重设计（第一期）**：Design System 2.0（中性表面、状态语义色、统一页面积木）；首页/运行控制台信息架构重排；设置首页选中高亮与预览说明；引导文案压缩；关于页对齐令牌。

- 单一 `app` Gradle 模块与 Compose + Hilt 产品壳（引导 / 首页 / 运行 / 设置 / 关于）。
- DataStore 配置 schema v6：主题、悬浮窗、视口、双赛季、自动操作、MCP、开发者、更新与算法选择项。
- 设置页临时预览与“保存并应用”；权限型配置（截图后端、MCP、自动操作、开发者、更新、算法）不在预览阶段启动。
- MediaProjection 默认截图路径；`CaptureBackend.AUTO` 仅委托低权限录屏，不探测 Root / Shizuku / 无障碍。
- 可选无障碍截图（API 30+）与 Root 截图（超时与尺寸限制）；Shizuku 入口保留。
- C++ 双赛季视觉引擎（甜甜圈 / 竹影书屋）、位掩码类别过滤、比例坐标与 JNI 失败隔离。
- 跨帧 `MultiObjectTracker`、悬浮窗三档样式、会话 arm 自动操作门控与 `GestureArbiter`。
- 本地 MCP（loopback、随机 Bearer、四级权限、语义工具）。
- `.hzzstheme` 声明式主题包；关于页赞赏图保存与开发者手势解锁。
- Gitee 优先双源签名更新库、差分补丁与发布工作流工具。
- 项目静态门禁、Native ASan/UBSan、宿主机数据集评估脚本与 JVM 单元测试。
- 官方算法包工具链（`.hzzsalg`）：校验、可重复构建、独立 Ed25519 签名、目录生成、GitHub/Gitee 同步与 dry-run 发布；示例包 `official-bamboo-baseline`。
- **声明式算法运行时（CC-1）**：`AlgorithmRuntimeProfile` / `ActiveAlgorithmProvider`、JNI `configureAlgorithm`、generation 安全切换、内置 `builtin.hzzs.v1` 回退；`VisionResult` 诊断字段。算法包不得控制手势或安全门禁。

### 变更

- 开发者页面对 `frameRateLimit` 明确标注「保留字段、完成驱动下暂不消费」；诊断摘要与设置/关于页共用完整导出。
- **构建性能**：`gradle.properties` 按 12GiB / 4 线程画像收紧 Daemon 堆与 worker；开启增量 kapt/Kotlin 与 Configuration Cache 并行；`gradle.local.properties` 支持本机缩 ABI；CMake 增加 `-fno-rtti` / 段回收与 Release `-O3`；关闭 Jetifier/无用 buildFeatures。
- 默认赛季集中到 `AppConfig.DEFAULT_SELECTED_SCENE`；代理/产品文档改为引用该常量，不再写死赛季名。
- 运行时不再按 `developer.frameRateLimit` / 默认 60 FPS 主动丢帧；吞吐由完成驱动 + 源端 CONFLATED 决定（开发者配置字段仍保留，暂不消费）。
- 默认赛季改为 **竹影书屋**（与历史 main 线默认一致）。
- 默认 `versionCode` 固定为 **1**，`versionName` 为 **0.1.0**。
- 文档体系收敛为 `README` / `CLAUDE` / `AGENTS` / `docs/{ARCHITECTURE,SECURITY,TESTING,PROGRESS}`。
- Release 签名解析增强：兼容 `ANDROID_KEYSTORE_*` 与历史 `AZEK431_RELEASE_*`，并支持 gitignore 的 `keystore.properties`；恢复 README 本机构建说明与 `keystore.properties.example`；minSdk 24 启用 V2/V3 签名。
- 新增本机交互脚本 `tools/release/build_signed_release.ps1`（输入签名信息后可选写入 properties 并 assembleRelease）。
- **设置界面完整重构**：设置首页 + 分类子页面（外观、算法与识别、截图、悬浮窗、自动操作、网络与更新、MCP/开发者）；宽屏双栏；共享 draft 会话，仅离开设置模块时保存/丢弃。
- 配置 schema **v6**：新增 `AlgorithmConfig`（自动/手动选择、通道、自动检查/下载）与 `UpdateConfig.sourcePreference`（自动 / 优先 Gitee / 优先 GitHub）。
- 算法与识别页：主卡展示当前算法/版本/模式/场景/来源/更新状态；最新与已安装列表；Loading/Empty/Offline/镜像回退/安全警告/下载/校验/待启用/错误/不兼容状态。

### 安全

- 自动操作默认关闭；导入与迁移不得静默开启；需当前免责声明版本。
- MCP 仅回环监听；写操作默认每次确认。
- 主题包不执行脚本、不加载远程资源。
- 截图帧、MCP 令牌与 DataStore 配置不进入系统云备份。
- 诊断导出与 `AppLog` 对 Bearer / token / secret 等做脱敏；复制 MCP 连接信息仅经显式按钮进剪贴板，不写入日志。

### 修复 / 对齐

- VS Code 启动任务误用正式包名 `top.azek431.hzzs`：Debug 实际包名为 `top.azek431.hzzs.debug`（`applicationIdSuffix=.debug`），导致 monkey「No activities found」。任务与 `.vscode/scripts/*` 已改为 Debug 包 + `am start`，并单独提供 Release 启动/卸载任务。
- VS Code tasks 深化：共享 `hzzs-common.ps1`、任务分组、默认 `CMAKE_BUILD_PARALLEL_LEVEL=2`、质量门禁/强停/清数据任务、JDWP 双入口（完整安装 vs 仅转发）。
- 设置模块新增统一离开守卫：底部导航、宽屏侧栏、系统返回、顶部返回与 MCP 路由都必须经过未保存确认；Composition 重建仅清除预览，不再静默丢弃草稿。

- 接入历史 main 的 vision2 / bamboo 检测核心，并映射到统一归一化 Detection 协议。
- 自动操作加入 main 风格触发距离、双跳时序、下滑 TTL 与竹影实验锁。
- 设置页补齐更新检查 / 下载 / 安装与忽略版本。
- Shizuku 截图后端：`screencap -p`（用户显式选择，AUTO 仍不升权；需真机授权验证）。
- 宽 cake / 竹隙不再双写 PIT，避免同一障碍触发两次动作。
- 动作执行移出帧循环，增加帧龄门控、空间去重与 `retryLimit`。
- 设置页接入 `SettingsEditSession`；高级截图后端复用帧池；悬浮窗内容未变时跳过重绘。
- 停止分析时 join 动作任务；`actionInFlight` CAS 防并发；忽略更新走 session；MCP 配置指纹变化才重启；`requireSessionArm` 生效并暴露到设置。
- 设置连续修改改为整份草稿 flush（`SettingsEditSession.replace`），避免 debounce 丢字段。
- 运行页按 `requireSessionArm` 切换手动解锁 / 自动窗口模式文案。
- MCP 停止走 `ACTION_STOP` 并允许同进程再次启动。
- 修复宿主机 `build_host.sh` / `run_native_sanitizers.sh` 未同步 `legacy_main` 源与 include，导致 CI 找不到 `HzzsVisionCore.h`。

### 已知限制

- 正式 GitHub/Gitee Release 与签名更新索引尚未发布。
- 数据集缺少独立人工真值，不得宣称准确率指标。
- 厂商 ROM、Root、Shizuku 与真实游戏链路需真机验证。
- `Shizuku.newProcess` 依赖设备端 Shizuku 版本与授权状态。
- `AlgorithmTrustAnchors.officialPublicKeyDerB64` 默认为空：算法包下载安装会安全拒绝，直至发布官方公钥写入客户端。
- legacy 主路径颜色谓词仍硬编码；尺寸仅后过滤，非扫描几何全参数化。

## [0.1.0] — 未发布

首发目标版本。在 tag `v0.1.0` 正式发布前，请以 `[Unreleased]` 与 `docs/PROGRESS.md` 为准。

### 计划纳入首发说明的能力摘要

- 本地画面分析与可配置悬浮窗
- 双赛季障碍识别与受控自动操作
- 低权限默认截图与可选高级后端
- 本地 MCP 与双源更新工具链

---

历史说明：仓库早期骨架（Views、模拟 HUD、多 Gradle 模块）已由统一重构分支取代；不再单独保留 2025-12 旧条目中与现状冲突的“未接入 MediaProjection”等描述。

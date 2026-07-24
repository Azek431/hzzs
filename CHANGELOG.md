# 变更日志

所有值得关注的变更都将记录在此文件中。

格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)。

版本策略：项目**尚未正式发布**。首个对外版本目标为 **v0.1.0**（`versionCode = 1`）。  
统一架构已在 `main` 落地；历史重构代号 `chatgpt/rewrite-v0.2.0-unified` 仅作对照，发布前版本号仍记为 0.1.0。

## [Unreleased]

### 新增

- **手势注入后端可切换**：`GestureBackend`（AUTO / 无障碍 / Shizuku input / Root input）与截图后端正交。AUTO 优先无障碍，条件使用已授权 Shizuku，永不升 Root。设置「自动操作」可选后端；Shell 路径用 dumpsys 前台门控；`input` 完成语义弱于无障碍回执。配置 schema 7；外部摄入禁止升手势风险序。

### 变更

- **算法 push 自动发布（GitHub）**：`algorithm-release.yml` 在 `main` 上变更 `algorithm-packs/**` 时自动签 `.hzzsalg` 并写入 `release-index`；默认仅 GitHub 镜像（可选 Gitee）。`publish_algorithm_release.py` 支持 `--mirrors github`。手机保持 `algorithm.autoCheck` 即可检查/下载（需配置算法签名 Secrets）。
- **触发距离运行时自调**：`AutomationConfig.autoAdjustTriggerDistance` 默认开启。分析中若有可行动障碍却因距离略远 `no_candidate`，按 `nearGap` 缓升玩家宽度倍数（冷却与单步上限）；规划成功且间隙偏近时向滑条基线缓降；约 4s 节流写回配置。设置「自动操作」可关；**不**静默开自动操作。
- **首次引导 5 步重构**：欢迎（产品+隐私合并）→ 赛季 → 截图（与设置同源 `CaptureCapability`，仅 AUTO/录屏/无障碍）→ 权限（可稍后）→ 完成（外观预览 + 折叠高级自动操作）。对齐 Design System 2.0（HeroCard / SectionCard / Callout / RadioCard）；步骤点指示；自动操作默认关，折叠区开启仍走倒计时+免责声明。
- **设置草稿预览 + 显式保存**：开关/选项写入进程内 preview（可即时预览主题/悬浮窗等），顶栏右上角「保存并应用」才落盘；分类间切换保留草稿；离开设置或切走主导航时若有未保存更改则弹窗（保存并离开 / 丢弃 / 取消）。手动开启自动操作仍走风险倒计时+免责声明；导入/迁移/MCP 外部摄入仍不得静默开启自动操作。首页分组/搜索保持。
- **MCP 连接展示多地址场景**：设置页可切换「同机回环 / 电脑 ADB 转发 / 自定义主机」与客户端导入方言（RikkaHub `streamable_http` / Claude Code `http`）；复制 URL/JSON 随之变化。服务仍只绑定 `127.0.0.1`，**不**开放局域网监听；ADB 场景附带可复制 `adb forward` 命令。
- **捆绑算法按版本升级**：`BundledAlgorithmInstaller` 在 assets 的 `versionCode` 高于已装 **bundled**（或旧数据无 origin）时覆盖落盘，无需清应用数据；**不**覆盖 `originTag=network` 的外装包。网络安装写入 `originTag=network`。开发改参须升高 `manifest.version` 并装入 APK；仅 push GitHub 源树不会让已装机自动变参（网络热更仍走 `release-index`）。
- **MCP 工具面扩展**：新增运行态快照、`patch_settings` 白名单局部改参、赛季/障碍/阈值、主题/悬浮窗、开发者开关与选项、自动操作门闩解释与启停、算法列表/激活/刷新/下载、日志查询与脱敏诊断导出、系统权限查询与设置跳转；资源 URI 同步扩容。HIGH_RISK 工具在 TRUSTED_SESSION 下拒绝。
- **开发者入口收敛**：关于页与设置共用 `DeveloperSettingsScreen`（不再维护第二套开发者 UI）。解锁后设置首页才显示「开发者选项」分类；页内开关仅可关闭并隐藏入口，再次开启仍需关于页连点版本号。
- **开发者设置体验重整**：顶部状态 Hero（日志/强制后端/调试帧/网格）；开关仅可关且关前确认；快捷排查（日志/算法流程/诊断导出）前置；日志级别改 FilterChip；`frameRateLimit` 折叠为保留字段；进页自动刷新调试帧计数；文案收口到 strings。
- **MCP 默认免鉴权 + 持久 Token**：`requireAuth` 默认 `false`（同机 RikkaHub 免填 Header）；开启鉴权时使用配置中持久化的 `authToken`，**不**在每次服务启动轮换，仅设置页「轮换 Token」主动更换。外部摄入不得静默关鉴权或改写令牌；Bearer 前缀比较大小写不敏感。
- **算法运行轨迹日志**：新增 `AlgorithmRuntimeTrace`（最近 32 帧 ring，无像素）。帧循环写入识别摘要 / 检测明细 / Tracker 稳定帧 / 自动操作决策与 dispatch 结果；AppLog 标签 `algo.frame` / `algo.det` / `algo.track` / `algo.decision`，仅在开发者开启且 `logLevel≤DEBUG` 时输出，并按「状态变化或每 12 帧」节流。诊断导出附带算法 pipeline 快照与最近帧轨迹。
- **APK 捆绑声明式算法**：`assets/algorithms/*` 经 `BundledAlgorithmInstaller` 预装到 `InstalledAlgorithmStore`（不经外装 Ed25519）。同 id 的 **bundled** 在 assets 更高 version 时可升级覆盖；网络外装见「捆绑算法按版本升级」。首版捆绑 `official-bamboo-baseline` 与 `sea-salt-living-room-v1`（酱油）。
- **官方算法信任锚**：`AlgorithmTrustAnchors.officialPublicKeyDerB64` 写入 `hzzs-algorithm-official-1` 公钥（DER base64）；公钥副本在 `algorithm-packs/official-public-keys/`。私钥仍仅 CI/本机。
- **算法库 / 检测参数拆页**：设置「算法库」专注内置·捆绑·远端切换与通道；「检测参数」单独配置赛季/障碍/玩家基准/workWidth/置信度/稳定帧；自动操作页暴露三赛季触发距离与节流。卡片列表可直接「使用此版本」。
- **设置即时保存（方案 C）**：去掉模块级共享草稿与底部「保存并应用」栏；开关/选项经短防抖直接落盘。离开设置或切走主导航时刷盘。手动开启自动操作仍走风险倒计时+免责声明；导入/迁移/MCP 外部摄入仍不得静默开启自动操作。首页改为分组（显示 / 采集与识别 / 安全与自动化 / 网络与扩展 / 高级）+ 搜索 + 紧凑分类行。

### 修复

- **海盐触发距离默认与校验**：`seaSaltTriggerDistancePlayerWidths` 默认 **5.0**（FIXED 玩家宽约 0.05 时约 0.25 屏宽，对齐酱油较远点击）；`validated`/滑条上限 **0.5–8**，避免旧 1.4 被钳在 4 内仍偏近导致「框已稳、no_candidate」。诊断 skip 文案补 `pw` / `nearGap` / `sc`。
- **算法库选包对齐赛季**：手动选用仅支持单一赛季的包（如酱油海盐）时自动切到该赛季；卡片标注「不含当前赛季」并 Snackbar 提示。
- **VS Code 真机/JDWP 任务**：PowerShell `Continue=Stop` 下原生 `adb` 的 stderr 不再误杀任务；无设备/unauthorized/offline 打印排查提示；安装/logcat/转发统一走脚本闸门；JDWP 启动后轮询 PID；诊断导出默认 `local-diagnostics/device`；脚本 UTF-8 BOM 兼容 Windows PowerShell 5.1；避免 StrictMode 下盲读 `0`。
- **海盐场景置信度与自动操作门闩**：FIXED_RATIO 不再按「玩家失败」压低 `sceneConfidence`；有障碍/找色命中时至少抬到 0.85/0.88，避免稳定检出 `SEA_PIT` 却因 `0.68<0.82` 永不派发手势。
- **海盐多点找色对齐酱油脚本**：按 AutoJS 源（设计 1272×2772、颜色 `0xAARRGGBB`）重录大/小断崖、矮/高沙丘、船锚五条模板；搜索带默认 0.438–0.881、阈值 10；船锚动作为 `SLIDE`（向下滑）；bounds 用点集包络而非专用绘制层；不移植「复活」UI 点击。
- **算法 analysisRunning 同步**：`VisionRuntimeController` start/stop/异常路径经 `AlgorithmCatalogController.setAnalysisRunning`，避免分析中下载半热激活与 UI pending 文案错位。
- **无信任锚时下载按钮降级**：算法卡 `canDownloadRemote` + 状态 `trustAnchorsConfigured`；远端不可装时禁用「下载/更新」并给出说明。
- **网络页算法文案**：去掉「演示 / 安装器未接入」过时表述；Wi‑Fi 策略说明覆盖算法包大文件下载。
- **host 测试脚本执行权限**：`run_host_tests` / `evaluate_dataset` / `batch_recognize` 经 `host_build.py` 用 `bash`/`powershell` 调用构建脚本，不再直接 exec 可能无 `+x` 的 `build_host.sh`（GitHub Actions 上 `PermissionError`）。
- **算法目录兼容与路径安全**：`AlgorithmNetworkClient` 使用本应用真实 `versionCode` 计算 `isCompatible`；校验算法 `id` / `sha256` 格式；staging 文件名仅用 SAFE_ID。
- **算法发布目录合并**：`publish_algorithm_release` 合并远端同通道已有条目，同 `(id,version)` 哈希不可变；workflow 去掉无效 `--draft`，按通道串行防覆盖。
- **多点找色热路径**：修正 ARGB 通道提取；头/实现四参数签名对齐；`multicolor_detector` 进入 CMake/host；JNI/Native 校验 `searchRegion*` 与 `multicolorThreshold`；海盐 `append_multicolor_detections` 真正合并结果。
- **PRESS / 双击手势**：`PRESS`/`SWIPE_UP` 取 `Detection.bounds` 中心；`DOUBLE_JUMP` 写入并消费 `doublePressDelayMs`；仲裁超时覆盖双击间隔；速率配额计两次按压。
- **配置/主题字段保全**：`ConfigJson`/`validated` 纳入 `seaSaltTriggerDistancePlayerWidths`；主题包编解码保留 `clickThrough`/`snapToEdge`/`lockPosition`。
- **host 协议范围**：scene 覆盖 0..2，kind ≤10，Avoidance ≤5。

### 变更

- **设置草稿预览 + 显式保存**：开关/选项写入进程内 preview（可即时预览主题/悬浮窗等），顶栏右上角「保存并应用」才落盘；分类间切换保留草稿；离开设置或切走主导航时若有未保存更改则弹窗（保存并离开 / 丢弃 / 取消）。手动开启自动操作仍走风险倒计时+免责声明；导入/迁移/MCP 外部摄入仍不得静默开启自动操作。首页分组/搜索保持。
- **MCP 对接 RikkaHub / 免填请求头**：对照 [RikkaHub](https://github.com/rikkahub/rikkahub)（Streamable HTTP + 可选 `headers` / 导入 JSON）与 [MCP 2025-06-18 传输](https://modelcontextprotocol.io/specification/2025-06-18/basic/transports)——`initialize` 后会话立即就绪；`requireAuth` 默认可关（现默认 false）；设置页一键「复制 URL / 复制 RikkaHub 导入 JSON / 复制 Token」；同机推荐 Streamable HTTP 而非 SSE。
- **MCP 同机连通性**：强制绑定 IPv4 `127.0.0.1`（避免 `getLoopbackAddress()` 的 `::1` 与客户端 `127.0.0.1` 不通）；HTTP keep-alive 多请求；路径 `/mcp/` 归一；Origin 接受字面量 `null`；OPTIONS 预检；GET `/mcp` 仍 405 表示无 SSE。
- **MCP Streamable HTTP 重构与安全加固**：拆分 `mcp` 包为传输 / 协议 / 会话 / 工具目录 / 动作仲裁 / UI 桥；`Mcp-Session-Id`、通知 HTTP 202、连接并发上限、严格 inputSchema、错误码分类、停止拒绝挂起审批。JVM 契约测试 `McpProtocolTest`；门禁扫描整个 `mcp/` 包。

- **MCP 设置页拆分**：旧「MCP 与开发者」合并页面拆为独立「MCP 服务」分类（普通用户可访问）和「开发者选项」分类（需解锁）。MCP 页面包含运行状态 Hero 卡片、一键复制连接信息按钮、四级权限选择、连接引导说明。开发者选项（调试帧、Native Benchmark、日志级别等）剥离至独立页。旧 `McpDeveloperSettingsScreen.kt` 已删除。

- **本机构建加速**：`gradlew` / `gradlew.bat` 在未设置时默认 `CMAKE_BUILD_PARALLEL_LEVEL=2`，并默认 `-Dorg.gradle.configuration-cache=true`，避免用户级 `gradle.properties` 关闭配置缓存导致每次 install/assemble 全量配置；可用 `HZZS_ALLOW_USER_CC_OVERRIDE=1` 退回用户设置。文档同步说明内存争用与 CC 覆盖。

- **文档与内置算法默认值对齐**：进度/架构/安全/算法规范与源码统一为 `main`、三赛季 `SceneId`、`builtin.hzzs.base` 0.1.0，以及无会话 arm 的自动操作门控；C++ 默认 profile 字符串与诊断字段同步清理旧 ID。

### 修复

- **VS Code 真机/JDWP 任务**：PowerShell `Continue=Stop` 下原生 `adb` 的 stderr 不再误杀任务；无设备/unauthorized/offline 打印排查提示；安装/logcat/转发统一走脚本闸门；JDWP 启动后轮询 PID；诊断导出默认 `local-diagnostics/device`；脚本 UTF-8 BOM 兼容 Windows PowerShell 5.1；避免 StrictMode 下盲读 `0`。
- **前台服务 type（targetSdk 37）**：`MediaProjectionCaptureService` / `McpForegroundService` 在 API 34+ 调用带 `FOREGROUND_SERVICE_TYPE_*` 的 `startForeground`，避免 `MissingForegroundServiceTypeException`。
- **录屏服务 `stopping` 闩**：stop 后同一 Service 实例再次 START 会重置 `stopping`，避免快速重开捕获永久空转；`fail/idle` 同步排空帧通道。
- **MCP/外部配置摄入 harden**：`hardenedForExternalIngest` 禁止静默开自动操作、自提 MCP 权限级、静默开开发者/升权截图后端；MCP `preview_settings`/`save_settings` 相对 baseline 收敛。
- **自动操作会话解锁移除**：`requireSessionArm` / `armAutomation` / `disarmAutomation` 已删除；设置页「启用自动操作」后，运行中识别达标直接规划手势，不再需要手动解锁。保留免责声明版本检查与风险确认对话框。MCP `arm_automation` / `disarm_automation` 工具同步移除。`RuntimeStatus.automationArmed` 字段移除。

- **disarm fail-closed**：`cancelActions()` 取消在飞 `actionJob`；配置变更含 enabled/disclaimer 时取消动作。
- **帧循环异常清 `analysisRunning`**：`runLoop` finally / start 失败路径与 `stop` 对称，避免算法切换永久 pending。
- **帧龄门控**：完成驱动下将 `MAX_FRAME_AGE_MS` 从 120ms 放宽到 1000ms，避免分析耗时导致自动操作系统性不触发。
- **内置算法首版号对齐 0.1.0**：`AlgorithmIds` / `AlgorithmRuntimeProfile` / C++ `make_builtin_profile` 的内置版本由错误的 `2.0.0` 改为 **`0.1.0`**，Catalog ID 为 `builtin-hzzs-base-0.1.0`（runtime 仍为 `builtin.hzzs.base`）；旧 pin 以 `builtin-` 前缀仍识别为内置。
- **诊断时间戳时区**：`DiagnosticsExporter` 的 `generatedAt` 与附带日志时间改为**设备本地时区 + 真实偏移**（`yyyy-MM-dd HH:mm:ss.SSSXXX`），不再用 UTC 格式却标假 `Z`；算法设置「最近检查」同步同一格式。
- **算法目录检查体验**：远端 `algorithms/{channel}.json` 404/双源失败时给出中文原因（目录尚未发布可继续用内置）；设置页说明与空态不再暗示「安装器未接入」；自动/手动选择文案与 ActivationCoordinator 行为一致。
- **海盐赛季 JNI `invalid scene`**：`jni_bridge` 分析入口仍按双赛季拒绝 `scene > 1`，真机选 `SEA_SALT_LIVING_ROOM`（ordinal=2）会连续 5 帧失败并停分析；已与 `kSceneCount=3` / `vision_engine` 对齐，并补 native 边界回归。
- **截图后端 fail-soft（Android 10 等）**：开发者「强制无障碍」或用户选择无障碍时，若本机 API&lt;30，不再硬启动 `AccessibilityFrameSource` 立即失败；`resolveEffectiveCaptureBackend` 回退到可用的用户主配置 / MediaProjection，诊断摘要增加 `capture.requested/effective/fallbackReason`，开发者页标注本机不可用。

### 新增

- **海盐客厅多点找色算法包**：移植酱油 AutoJS Pro 脚本为 C++ 多点找色检测器（`multicolor_detector.h/.cpp`）；扩展 `Avoidance.PRESS` / `SWIPE_UP`；算法包 `sea-salt-living-room-v1` beta 发布（作者：酱油）；`AutomationConfig.seaSaltTriggerDistancePlayerWidths` + 手势 `doublePressDelayMs` 支持双击模式。
- **系统权限引导与悬浮窗双层绘制**：设置/引导/运行页可查看并跳转系统悬浮窗与无障碍设置；`RuntimeStatus.overlayBlockReason` 区分权限/关闭/加窗失败；`OverlayController` 双 Window（穿透检测框 + 可拖 HUD 同时存在）；缺权限时分析不中断并给出可行动提示。
- **三赛季算法引擎（海盐客厅）**：`SceneId.SEA_SALT_LIVING_ROOM`；障碍 `SAND_CASTLE` / `HANGING_ANCHOR` / `SEA_PIT`；C++ `sea_salt_living_room.cpp` 参数驱动路径；默认赛季改为海盐；内置算法 `builtin.hzzs.base` **0.1.0** 覆盖三赛季参数。
- **海盐召回修复**：宿主批跑 kind 掩码 `0xFF`→`0x7FF`（原先静默关掉海盐三类）；海盐专用尺寸后过滤；玩家失败仍用固定框继续扫；海坑改为「地面线下方非木地板列 + 暗/水色」；船锚每帧最多一个并提高金属门槛。
- **竹影主路径性能**：`workWidth` 降采样 + 缩放时一次 ARGB→RGB；引擎侧 ROI/步进、减弱形态学、移除不进动作协议的收藏品/能量球全图扫描。
- **识别批跑工具**：`tools/vision/batch_recognize.py` + Windows `build_host.ps1`；输出按赛季分子目录（耗时与叠加图，不宣称准确率）。
- **算法网络与验签安装**：`AlgorithmNetworkClient`（HTTPS 目录/资产）、`AlgorithmPackVerifier`（ZIP 白名单 + Ed25519，BouncyCastle）、`AlgorithmTrustAnchors`（内置信任锚；列表空时拒绝外装，现已含 official-1 公钥）。**包体与目录均在 `release-index` 分支**（`algorithms/packages/`），**不再依赖** GitHub/Gitee Release tag。
- **算法包 rules schema v2（双段）**：`userThresholds` + `engineParams`；tools 兼容 v1；示例包 `official-bamboo-baseline` 升 v2；Kotlin `AlgorithmRulesParser` 合成双场景 profile 并填洞。
- **算法安装/激活骨架**：`InstalledAlgorithmStore`（filesDir 落盘）、`AlgorithmActivationCoordinator`（save/start 解析 pin/AUTO）；统一 `AlgorithmIds`。
- **主路径尺寸后过滤 + M3A**：profile 尺寸窗剔除越界检测；甜甜圈 scene_confidence 质量度量；竹影 player floor / workWidth 校验。
- **开发者设置补齐与诊断日志**：设置「MCP 与开发者」对齐关于页能力（强制截图后端、调试帧刷新/清除、日志级别、Native 自检、诊断导出）；`AppLog` 内存 ring buffer + 脱敏；`DiagnosticsExporter` 导出版本/机型/配置摘要/算法激活/运行态/最近日志（不含 Bearer 与调试帧像素）；`DeveloperConfig.logLevel` 持久化；复制诊断经 `ClipboardHelper` 并有 Snackbar/Toast 反馈；算法激活/配置/帧循环安全点写入 `AppLog(tag=algorithm)`。
- **应用内运行日志查看器**：设置/关于开发者入口进入 `LogViewerScreen`；支持级别/标签/关键字筛选、新在前、自动滚动、复制/分享/清空；`AppLog.query`/`revision` 驱动刷新；视觉 start/stop 写入详细会话日志。
- **算法执行流程可视化**：`AlgorithmPipelineTrace` 追踪 resolve→profile→validate→activate→native→ready→最近一帧；设置/关于开发者入口进入 `AlgorithmPipelineScreen` 直观查看阶段状态与最近分析摘要（类别直方图/置信度/耗时/错误）；会话级 `algorithm` 日志字段更全。
- **Motion Policy 与导航转场**：`HzzsMotionPolicy` 统一消费 `animationScale` / `reduceMotion` 与系统 animator 倍率；一级导航 fade-through、设置分类 shared-axis X、引导步骤 AnimatedContent；减少动效时即时终态。设计 token 增加断点与 `contentMaxWidth`；`HzzsScrollPage` 宽屏限宽；设置保存栏窄屏纵向动作区。
- **文案资源化深化**：一级导航、通用动作、MCP 审批、设置分类/首页、引导全流程、关于赞赏对话框、首页与运行控制台迁入 `strings.xml`。
- **颜色对比工具**：`HzzsColorContrast`（WCAG 相对亮度/对比度/合成）与 JVM 单测；外观自定义色输入显示与白底对比提示。
- **无障碍小改进**：`PageHeader` / `HzzsSection` 标题 heading 语义；赞赏改为标准 Dialog + 显式「保存到相册」。
- **完成驱动取帧与 HUD 近似轮廓**：分析循环按上一轮完成拉取最新帧；MediaProjection/AUTO 在 HUD 显示时临时隐身并排空可能含旧合成层的一帧；`displayContour` 仅供 HUD 绘制，动作与 Tracker 仍只读 `bounds`。
- **UI 工具专业风重设计（第一期）**：Design System 2.0（中性表面、状态语义色、统一页面积木）；首页/运行控制台信息架构重排；设置首页选中高亮与预览说明；引导文案压缩；关于页对齐令牌。

- 单一 `app` Gradle 模块与 Compose + Hilt 产品壳（引导 / 首页 / 运行 / 设置 / 关于）。
- DataStore 配置 schema v6：主题、悬浮窗、视口、双赛季、自动操作、MCP、开发者、更新与算法选择项。
- 设置页即时落盘（短防抖）；危险项（如手动开自动操作）先确认再写；导入/MCP 外部摄入仍 harden。
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

- **设置草稿预览 + 显式保存**：开关/选项写入进程内 preview（可即时预览主题/悬浮窗等），顶栏右上角「保存并应用」才落盘；分类间切换保留草稿；离开设置或切走主导航时若有未保存更改则弹窗（保存并离开 / 丢弃 / 取消）。手动开启自动操作仍走风险倒计时+免责声明；导入/迁移/MCP 外部摄入仍不得静默开启自动操作。首页分组/搜索保持。
- **悬浮窗默认样式**：首装 / 缺字段回退由「极简」改为 **调试 HUD**（`OverlayStyle.DEBUG_HUD`）；已保存配置与主题包内显式 `style` 不变。
- 协作文档：`CLAUDE.md` / `AGENTS.md` 增加代理记忆与经验流程；改完须同步 `README.md`（**保留 Star History**）与 `CLAUDE.md`；新增 `docs/AGENT_EXPERIENCE.md` 短条摘录。
- 开发者页面对 `frameRateLimit` 明确标注「保留字段、完成驱动下暂不消费」；诊断摘要与设置/关于页共用完整导出。
- **构建性能**：Hilt 从 legacy-kapt 迁至 **KSP**（去掉 stub 双编译）；`gradle.properties` 按低内存开发机 + IDE 共存再收紧 Daemon/Kotlin 堆与 `workers.max=2`；`tools/dev/repair_gradle_kotlin_cache.ps1` 修复 IC 损坏；`gradle.local.properties` 支持本机缩 ABI；CMake 增加 `-fno-rtti` / 段回收与 Release `-O3`；关闭 Jetifier/无用 buildFeatures。
- 默认赛季集中到 `AppConfig.DEFAULT_SELECTED_SCENE`；代理/产品文档改为引用该常量，不再写死赛季名。
- 运行时不再按 `developer.frameRateLimit` / 默认 60 FPS 主动丢帧；吞吐由完成驱动 + 源端 CONFLATED 决定（开发者配置字段仍保留，暂不消费）。
- 默认赛季改为 **海盐客厅**（`AppConfig.DEFAULT_SELECTED_SCENE`，产品默认永远指向当前最新赛季）。
- 障碍枚举与研究版对齐：`POISON_BOTTLE` → `GREEN_BOTTLE`；新增海盐三类障碍。
- 内置算法 ID：`builtin.hzzs.v1` → `builtin.hzzs.base`（首版语义化版本 **0.1.0**）。
- 默认 `versionCode` 固定为 **1**，`versionName` 为 **0.1.0**。
- 文档体系收敛为 `README` / `CLAUDE` / `AGENTS` / `docs/{ARCHITECTURE,SECURITY,TESTING,PROGRESS}`。
- Release 签名解析增强：兼容 `ANDROID_KEYSTORE_*` 与历史 `AZEK431_RELEASE_*`，并支持 gitignore 的 `keystore.properties`；恢复 README 本机构建说明与 `keystore.properties.example`；minSdk 24 启用 V2/V3 签名。
- 新增本机交互脚本 `tools/release/build_signed_release.ps1`（输入签名信息后可选写入 properties 并 assembleRelease）。
- **设置界面完整重构**：设置首页 + 分类子页面（外观、算法与识别、截图、悬浮窗、自动操作、网络与更新、MCP/开发者）；宽屏双栏；共享 draft 会话，仅离开设置模块时保存/丢弃。
- 配置 schema **v6**：新增 `AlgorithmConfig`（自动/手动选择、通道、自动检查/下载）与 `UpdateConfig.sourcePreference`（自动 / 优先 Gitee / 优先 GitHub）。
- 算法与识别页：主卡展示当前算法/版本/模式/场景/来源/更新状态；最新与已安装列表；Loading/Empty/Offline/镜像回退/安全警告/下载/校验/待启用/错误/不兼容状态。

### 安全

- 自动操作默认关闭；导入与迁移不得静默开启；需当前免责声明版本。
- **MCP/外部配置摄入**：`hardenedForExternalIngest` 相对 baseline 收敛 automation/MCP/developer/captureBackend；禁止自提 `FULL_ACCESS` 与静默开自动操作。
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
- 远端 `release-index/algorithms/{channel}.json` 若未发布，检查更新仍为空态；内置引擎与 APK 捆绑包可切换，远端更新需目录上线。
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

# HZZS 架构

## 单模块、强分层

HZZS 使用单一 `app` Gradle 模块，减少跨模块追踪成本。包级依赖方向：

```text
feature → data / service → domain / core
platform 仅通过接口向运行时暴露能力
```

| 包 | 职责 |
|---|---|
| `core` | 稳定模型、DataStore、主题、设计系统、更新库、`logging` 诊断门面 |
| `domain` | 与 Android 无关的视觉与手势规则（可 JVM 测试） |
| `data/vision` | 帧循环所有者、JNI 适配、追踪、调试帧 |
| `feature` | Compose 界面；不直接 Root / Shell / JNI / WindowManager |
| `service` | 截图后端、悬浮窗、无障碍手势 |
| `platform/compat` | 版本与能力探测；系统悬浮窗/无障碍设置跳转（`SystemCapabilityAccess`） |
| `mcp` | 回环 MCP 与权限仲裁 |
| `nativevision` | JNI 加载边界（失败不崩进程） |

## 运行时

1. `FrameSourceFactory` 根据已保存后端创建截图源。
2. `VisionRuntimeController` **完成驱动**拉取最新帧（不再按固定 FPS 主动丢帧），校验视口与配置并调用引擎。
3. `NativeVisionEngine` 经 `NativeVision` JNI 调用 C++，再映射为领域模型。
4. `VisionResultValidator` 应用类别过滤、置信度与坐标不变量。
5. `MultiObjectTracker` 做跨帧稳定；稳定帧序号按已分析帧计数，避免 CONFLATED/排空导致跳跃。
6. Tracker 之后可为障碍附加仅 HUD 使用的 `displayContour`（近似模板，非 C++ 像素轮廓）；动作仍只读 `bounds`。
7. 结果进入 `OverlayController` 双层持久 Canvas 悬浮窗（穿透全屏检测框 + 可拖 HUD）；`SYSTEM_ALERT_WINDOW` 未授予时 fail-closed 并写入 `RuntimeStatus.overlayBlockReason`，分析循环不中断。若 HUD 已显示，取帧前临时 `INVISIBLE` 并等待一次显示提交，MediaProjection/AUTO 再排空可能含旧合成层的一帧后恢复。自动操作只有通过全部门控后才进入 `GestureArbiter`。

帧循环是 native 引擎与 tracker 的**唯一所有者**。配置收集器只替换不可变快照。`generation` 令牌防止已停止会话的陈旧帧写回 UI。详见 `docs/vision/V09_COMPLETION_DRIVEN_CONTOURS.md`。

## 截图

| 后端 | 行为 |
|---|---|
| AUTO | **仅** MediaProjection，永不升权 |
| MEDIA_PROJECTION | VirtualDisplay + ImageReader + 帧池租约 |
| ACCESSIBILITY | API 30+ `takeScreenshot`，有频率限制 |
| SHIZUKU | 用户显式选择；经 Shizuku 执行 `screencap -p`（需安装/授权） |
| ROOT | 受限 `screencap` |

解析入口：`resolveEffectiveCaptureBackend`（开发者 `forceCaptureBackend` 优先）。本机 API 不支持请求后端时 **fail-soft** 回退到可用的用户主配置或 MediaProjection，写入诊断 `capture.requested/effective/fallbackReason`；**不会**把 AUTO 升权到无障碍 / Shizuku / Root。

`CapturedFrame` 拥有像素租约，分析结束后必须 `close()`。不得跨帧保存底层缓冲引用。

## 配置

DataStore 存储 schema **v6**。`SettingsRepository` 以已保存配置为真相源，仍保留进程内 preview 层（引导/外部预览可用）。

- 设置 UI 为首页 + 分类子页，共享同一 `SettingsViewModel`；控件改动短防抖后 **即时落盘**。
- 危险项（如手动开自动操作）由子页确认后再写；导入/MCP 外部摄入经 `hardenedForExternalIngest`。
- `SettingsExitCoordinator` 在离开设置（Bottom Bar / Navigation Rail / 返回 / MCP 路由）前调用 `flushNow` 刷盘。
- 首页分组（显示 / 采集与识别 / 安全与自动化 / 网络与扩展 / 高级）+ 搜索 + 紧凑分类行。
- `AlgorithmCatalogController` 以 StateFlow 暴露算法目录、下载进度与镜像状态；网络刷新/下载为即时任务。
- 开发者选项：设置「开发者选项」分类入口（关于页 7 连点解锁），与关于页字段对齐；`DeveloperConfig.logLevel` 持久化。`frameRateLimit` 仍校验但完成驱动取帧下**不消费**。
- `core/logging`：`AppLog`（Logcat + 内存 ring buffer，`query`/`revision` 供查看器）与 `DiagnosticsExporter`（脱敏诊断文本）；设置/关于提供 `LogViewerScreen` 浏览级别/标签/关键字筛选日志；`AlgorithmPipelineTrace` + `AlgorithmPipelineScreen` 展示算法激活阶段与最近一帧摘要；`AlgorithmRuntimeTrace` 保留最近分析帧 ring（无像素，标签 `algo.frame`/`algo.det`/`algo.track`/`algo.decision`）；落盘配置时同步日志策略。

默认 `selectedScene` 取 `AppConfig.DEFAULT_SELECTED_SCENE`（唯一产品默认，文档不写死赛季名）。`SceneId` 枚举序：`SWEET_FACTORY = 0`、`BAMBOO_BOOKSTORE = 1`、`SEA_SALT_LIVING_ROOM = 2`，与 C++ `scene` / `kSceneCount = 3` 一致。

## 设计系统与动效

- 主题令牌：`HzzsTheme` 提供 `LocalHzzsDimensions` / `LocalHzzsStatusColors` / `LocalHzzsMotion`。
- 窗口断点集中在 `HzzsBreakpoints`（一级导航 720dp、设置双栏 840dp），布局读当前窗口宽度。
- **Motion Policy**：综合 `ThemeConfig.animationScale`、`reduceMotion` 与系统 `animator_duration_scale`；一级导航用 fade-through，设置分类与引导步骤用短 shared-axis X。减少动效或系统倍率为 0 时转场为 None。
- **颜色**：`HzzsColorContrast` 提供 WCAG 对比度与 ARGB 规范化，供外观/HUD 共用；动画时长不得用于业务超时、帧龄或手势 TTL。主题仍支持 Dynamic / 多预设 / 自定义种子（未收紧为固定品牌）。

## 坐标与线程

- 视觉结果使用视口归一化坐标 `[0, 1]`，只有平台绘制和手势分发层转换为像素。
- WindowManager、View 与 Accessibility 回调必须在主线程协调。
- C++ 输入缓冲只在 JNI 调用期间借用，Native 不得持有 Java 数组地址。

## 自动操作

默认关闭。运行路径：

```text
自动操作门控（enabled + 免责声明版本 + 运行中 + 无障碍前台窗口）
  → 帧龄校验（默认 ≤1000ms，含分析耗时；过旧帧不动作）
  → 独立 actionJob 规划/执行（不阻塞取帧）
  → GestureArbiter（串行、TTL、回执、retryLimit）
  → HzzsAccessibilityService.dispatchGesture
```

门控包括：设置开关、免责声明版本、视觉运行中、无障碍前台包白名单、窗口类名、场景置信度、帧时效、竹影实验锁、空间去重与动作速率。自动操作启用后不再要求会话级 arm，在分析运行中识别达标直接规划手势；`cancelActions()` 会取消在飞动作；MCP/外部 JSON 经 `hardenedForExternalIngest` 不得静默开启自动操作或自提 MCP 权限。

## MCP

仅绑定 IPv4 `127.0.0.1`（不用 `getLoopbackAddress()` 的 `::1`，避免与客户端 `127.0.0.1` 不通）。默认每次启动生成随机 Bearer（恒时比较）；用户可关 `requireAuth` 以便同机客户端免填 Header（仍仅 loopback）。Origin 允许空 / 字面量 `null` / 本机回环。传输为 Streamable HTTP（`POST /mcp`，keep-alive 多请求；GET `/mcp`→405 无 SSE），协议版本协商 `2025-06-18`（兼容 `2025-03-26` / `2024-11-05`）。`initialize` 后会话即就绪（仍接受幂等 `notifications/initialized`）。

包内拆分：

| 组件 | 职责 |
| --- | --- |
| `McpForegroundService` | loopback 监听、generation 启停、并发连接上限 |
| `McpSessionManager` | 内存会话 / `Mcp-Session-Id`；服务重启清空 |
| `McpProtocol` | initialize / initialized / 通知 202 / 错误码分类 |
| `McpToolCatalog` | 描述驱动工具与严格 JSON Schema |
| `McpActionRegistry` | 四级权限仲裁与语义动作 |
| `McpUiBridge` | 审批对话框与导航；停止时拒绝挂起审批 |

权限：只读、每次确认、会话信任（绑定当前内存会话，不持久化）、完整访问。设置页提供 URL / RikkaHub 导入 JSON / Token 一键复制。`tools/call` 须完成握手；`tools/list` 可无会话探测。API 34+ 前台服务须带 `SPECIAL_USE` type。`preview_settings`/`save_settings` 相对已保存 baseline 做权限型字段收敛（含不得静默关 `requireAuth`）。GET `/mcp` 返回 405（不提供 SSE 推送流）。

## C++ 视觉

目录 `app/src/main/cpp/`，库名 `hzzs_vision`：

- **主路径**：`legacy_main/vision2`（甜甜圈）与 `legacy_main/vision_bamboo`（竹影书屋），经 `vision_engine.cpp` 映射为统一 `Detection` / 位掩码协议。
- **回退路径**：`sweet_factory.cpp` / `bamboo_bookstore.cpp`，仅在主路径场景置信度过低且检测过少时启用。
- 共享几何与连通域：`scene_geometry.h` / `color_components.h`
- 算法运行时快照：`algorithm_runtime.{h,cpp}`（`AlgorithmRuntimeProfile` 不可变 generation）
- JNI 边界：`jni_bridge.cpp`（校验、视口裁剪、结果编码、`configureAlgorithm`）

跨帧状态在 Kotlin tracker / runtime，不在 C++ 状态机中。

### 算法运行时（CC-1）

第一版算法包是**声明式视觉参数**，不是任意代码。不得动态加载 `.so` / Dex / Jar / APK / Python / JavaScript / Shell。

```text
ActiveAlgorithmProvider.activate(profile)
  → AlgorithmProfileValidator（finite / 范围 / 白名单 / schema）
  → NativeVision.configureAlgorithm(profile)   // 安全切换点，与 analyze 串行
  → AlgorithmRuntime 替换不可变快照，generation++
  → 清空 MultiObjectTracker / 动作去重 / 稳定帧
analyze(frame) 只读当前 generation 对应快照
失败 → 保留旧配置或回退 builtin.hzzs.base（0.1.0），NativeVision 保持可用
```

网络算法配置**不能**控制手势、点击、Root、包名白名单或安全门禁。  
结果诊断字段：`activeAlgorithmId` / `activeAlgorithmVersion` / `algorithmGeneration` / `usingBuiltinFallback` / `algorithmLoadError`。

每帧禁止：读文件、解析 JSON、分配大型规则对象。

## 更新

`UpdateRepository`：Gitee 优先、GitHub 校验、清单签名、APK / 差分哈希与证书绑定。应用内 UI 负责触发检查与安装跳转。

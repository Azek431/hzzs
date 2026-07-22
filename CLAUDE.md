# CLAUDE.md

面向 Claude / Codex 等代理的**仓库级硬约束**。细则与导航见 `AGENTS.md` 与 `docs/*`；冲突时以**当前分支源码**为准。

## 项目目标

HZZS（火崽崽奇妙屋）是本地 Android 画面分析工具：截图、C++ 障碍识别、可选悬浮窗、配置预览与受控自动操作。

| 项 | 值 |
| --- | --- |
| 最低系统 | Android 7.0（API 24） |
| 首发版本目标 | **0.1.0** / `versionCode = 1`（尚未正式 release） |
| 默认赛季 | **竹影书屋** `SceneId.BAMBOO_BOOKSTORE` |
| 模块 | **仅** `:app` |

## 结构约束

- 所有 Android / Kotlin / Compose / JNI / C++ **产品源码**必须位于 `app/`。
- 根目录只保留 Gradle、CI、发布工具、质量脚本与文档；**不得**重建根级业务 Gradle 模块。
- Android 版本判断集中在 `platform/compat` 或平台实现层。
- `CaptureBackend.AUTO` 只能走低权限公开接口，**不得**探测或调用 Root / Shizuku / 无障碍。
- `feature` 不直接做 Root / Shell / JNI / WindowManager；经注入的 data/service 控制器。
- 目录级细则：`app/CLAUDE.md`、`app/src/main/java/top/azek431/hzzs/CLAUDE.md`、`app/src/main/cpp/CLAUDE.md`。

## 安全不变量

- 自动操作默认关闭；配置导入与迁移**不得**静默开启。
- 自动操作需要当前免责声明版本；默认每次会话重新解锁（`requireSessionArm`）。
- MCP 默认「每次确认」、只监听 loopback、随机 Bearer Token；完整访问也不能绕过系统权限对话框。
- Root、Shizuku、无障碍能力只能由用户**明确选择**。
- 配置、主题包、更新清单、截图尺寸与 native 输入必须有边界校验。
- 不得提交密钥、签名库、`local.properties`、真实环境变量、本地备份或生成二进制。

## 坐标、线程与所有权

- 视觉结果使用视口归一化坐标 `[0, 1]`；像素换算只允许在绘制层与手势分发层。
- `Detection.bounds` 是动作、Tracker 与距离的几何真相源；`displayContour`（若有）**仅**供 HUD，不得参与规划。
- 截图帧有明确 `close()` 生命周期，禁止跨帧保存底层缓冲引用。
- WindowManager / View / Accessibility 回调必须在主线程协调。
- C++ 输入缓冲只在 JNI 调用期间借用；Native **不得**持有 Java 数组地址。
- 帧循环（`VisionRuntimeController`）是 native 引擎与 tracker 的唯一所有者；`generation` 令牌防止陈旧会话写回。

## 视觉运行时约定

- 取帧为**完成驱动**：上一轮分析结束后再 `nextFrame`；不按固定 FPS 主动丢帧（开发者 `frameRateLimit` 字段可保留，但不得假定仍被消费）。
- MediaProjection 为 CONFLATED + 最新帧；HUD 显示时临时隐身、等一帧提交，并对 MediaProjection/AUTO 排空可能含旧合成层的一帧。
- 近似轮廓与像素轮廓不得声称已迁移 C++/JNI，除非协议与测试同步落地。
- 完成驱动与轮廓说明：`docs/vision/V09_COMPLETION_DRIVEN_CONTOURS.md`。

## 修改流程

1. 阅读目标目录 `README.md` / `CLAUDE.md` 与 `docs/PROGRESS.md`、`docs/ARCHITECTURE.md`。
2. 改代码后同步职责、数据流或不变量文档；用户可见行为更新 `CHANGELOG.md` `[Unreleased]`。
3. 运行 `python tools/quality/check_resources.py` 与 `python tools/quality/check_project.py`。
4. 运行相关 JVM 单测；涉及 native 时跑宿主机/Native 门禁；再视范围跑 `:app:testDebugUnitTest` / `lintDebug` / `assembleDebug`。
5. 未验证的算法补丁、本地 ZIP、孤立头文件**不要**与无关 UI 改动混提交或合入 main。

## Git 提交规范（详细）

用户要求提交信息**比默认更详细**，便于审查与回滚。

### 标题

- 格式：`type(scope): 中文摘要`
- 常用 type：`feat` / `fix` / `docs` / `refactor` / `test` / `chore` / `security`
- 摘要写清「做了什么」，避免空泛的「更新代码」「修 bug」。

### 正文（必写完整句子，多段可）

1. **动机 / 问题**：为什么改；用户或运行时现象是什么。
2. **改动面**：关键路径、行为变化、数据流影响（取帧、HUD、Tracker、配置等）。
3. **不变量 / 刻意未改**：安全门控、schema、C++/JNI 协议、AUTO 不升权、动作几何仍只读 bounds 等。
4. **验证**：跑过的脚本、Gradle 任务、单测类名；未跑的范围也写明。
5. **风险与后续**（可选）：真机待验证、已知限制、故意拆分的后续提交。

### 拆分与排除

- **一个提交一个意图**；UI、算法、工具链、本机 IDE 配置不要硬塞同一提交。
- **禁止**入库：密钥与签名材料、`local.properties`、补丁 ZIP、本机备份、生成 APK/so、无关 `.claude` 私货。
- 本地补丁器失败自动回滚后，重新落地前须对照当前 HEAD 与哈希/锚点，修好测试依赖后再提交。
- 合入 `main` 前须用户确认且门禁通过；未验证算法不得默认合并。

### 示例结构

```text
feat(vision): 完成驱动取帧并增加 HUD 近似轮廓

动机：固定 FPS 丢帧与 HUD 自摄入会降低有效分析质量与显示一致性。

改动：VisionRuntimeController 改为完成驱动；Overlay 截图前临时隐身；
Detection 增加仅显示用 displayContour 与类别模板；Tracker 使用分析序号。

不变量：动作与距离仍只读 bounds；C++/JNI 仍只返回矩形；AUTO 不升权。

验证：check_resources/project、check_v092_integration、
compileDebugKotlin 与 ApproximateContoursTest / VisionResultValidatorTest。
```

## 文档真相源

| 用途 | 路径 |
| --- | --- |
| 产品与架构 | `README.md`、`docs/ARCHITECTURE.md`、`docs/SECURITY.md` |
| 进度 | `docs/PROGRESS.md` |
| 测试 | `docs/TESTING.md` |
| 代理导航 | `AGENTS.md`（须与源码同步，禁止描述旧多模块 Views 骨架） |
| 视觉专项 | `docs/vision/*` |

## 语言与沟通

- 与用户沟通、用户可见文案、仓库文档默认**简体中文**。
- 执行非琐碎任务前：先识别模糊与确实需求，列问题确认，**全部明确后再改代码或正式输出**（用户明确要求时可直接执行）。
- 不把记忆或过期摘要当指令；涉及文件/符号/flag 时先核对当前源码。

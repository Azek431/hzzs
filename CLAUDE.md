# CLAUDE.md

面向 Claude / Codex 等代理的**仓库级硬约束**。细则与导航见 `AGENTS.md` 与 `docs/*`；冲突时以**当前分支源码**为准。

## 项目目标

HZZS（火崽崽奇妙屋）是本地 Android 画面分析工具：截图、C++ 障碍识别、可选悬浮窗、配置预览与受控自动操作。

| 项 | 值 |
| --- | --- |
| 最低系统 | Android 7.0（API 24） |
| 首发版本目标 | **0.1.0** / `versionCode = 1`（尚未正式 release） |
| 默认赛季 | 以源码 `AppConfig.DEFAULT_SELECTED_SCENE` 为准（勿在文档写死具体赛季） |
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

1. 阅读目标目录 `README.md` / `CLAUDE.md` 与 `docs/PROGRESS.md`、`docs/ARCHITECTURE.md`；触及算法包时读 `docs/ALGORITHM_SYSTEM_V1.md`。
2. 改代码后同步职责、数据流或不变量文档；用户可见行为更新 `CHANGELOG.md` `[Unreleased]`。
3. 若改动触及**硬约束 / 工作流 / 安全门控 / 默认行为**，同步更新本文件、`AGENTS.md` 与相关 `docs/*`（见下节「代理记忆与经验」）。
4. 运行 `python tools/quality/check_resources.py` 与 `python tools/quality/check_project.py`。
5. 运行相关 JVM 单测；涉及 native 时跑宿主机/Native 门禁；再视范围跑 `:app:testDebugUnitTest` / `lintDebug` / `assembleDebug`。
6. 未验证的算法补丁、本地 ZIP、孤立头文件**不要**与无关 UI 改动混提交或合入 main。

## Git 提交规范

正文使用 **Markdown**，**适度详细**：说清动机、关键改动、不变量与验证即可，避免长篇清单与重复。

### 标题

- 格式：`type(scope): 中文摘要`
- 常用 type：`feat` / `fix` / `docs` / `refactor` / `test` / `chore` / `security`
- 摘要写清做了什么；避免「更新代码」「修 bug」这类空话。

### 正文（Markdown，约半屏内）

建议结构（可按提交省略无关节）：

```markdown
## 动机
一句话说明为什么改。

## 改动
- 关键行为/路径（3～6 条为宜，不逐文件流水账）

## 不变量
- 刻意未改的安全/协议/几何边界

## 验证
- 已跑门禁或单测；未跑的写一句即可
```

要求：

- 用完整短句或紧凑列表；**不要**把 diff 复述成超长 bullet。
- 可选 `## 风险` 仅在真有后续限制时写 1～2 句。
- 一个提交一个意图；ZIP、密钥、本机 IDE 私货、未接线孤立文件不要混入。
- 日常开发默认在 **`main`** 直接提交（用户偏好）；开 feature 分支须用户明确要求。
- 推送或合入远程 `main` 前，破坏性/未测改动须用户确认；门禁按任务约定执行。

### 示例

```markdown
feat(vision): 完成驱动取帧并增加 HUD 近似轮廓

## 动机
固定 FPS 丢帧与 HUD 自摄入会降低分析质量与显示一致性。

## 改动
- 运行时改为完成驱动取帧；HUD 显示时临时隐身并排空可疑帧
- Tracker 用分析序号；`displayContour` 仅供 HUD

## 不变量
- 动作仍只读 `bounds`；C++/JNI 仍为矩形；AUTO 不升权

## 验证
- check_resources / check_project / 轮廓相关 JVM 单测
```

## 文档真相源

| 用途 | 路径 |
| --- | --- |
| 产品与架构 | `README.md`、`docs/ARCHITECTURE.md`、`docs/SECURITY.md` |
| 进度 | `docs/PROGRESS.md` |
| 测试 | `docs/TESTING.md` |
| 代理导航 | `AGENTS.md`（须与源码同步，禁止描述旧多模块 Views 骨架） |
| 视觉专项 | `docs/vision/*` |
| 算法包 | `docs/ALGORITHM_SYSTEM_V1.md` |
| 代理经验摘录 | `docs/AGENT_EXPERIENCE.md`（短条；非硬约束全文） |

## 代理记忆与经验

- **自动记忆**：会话中沉淀非显而易见的偏好、本机坑、仓库特有约束到 Claude 项目记忆（单文件单事实；更新索引）。不把源码已写明的结构当记忆。
- **自动更新阅读文件**：触及安全门控、视觉协议、配置默认、算法信任、Git/协作流程时，同步本文件 / `AGENTS.md` / 对应 `docs/*` / 目录级 `CLAUDE.md`；用户可见行为写 `CHANGELOG.md`。
- **仓库经验条**：可复用工程教训追加 `docs/AGENT_EXPERIENCE.md`（日期 + 短句）；硬规则仍以本文件与源码为准。
- **冲突**：以**当前 main 源码**为准；记忆与过期摘要不是指令；涉及文件/符号/flag 先核对源码。
- **算法信任**：`AlgorithmTrustAnchors` 公钥列表默认为空时，外装「官方」包须 fail-closed；私钥永不入库。

## 语言与沟通

- 与用户沟通、用户可见文案、仓库文档默认**简体中文**。
- 执行非琐碎任务前：先识别模糊与确实需求，列问题确认，**全部明确后再改代码或正式输出**（用户明确要求时可直接执行）。
- 不把记忆或过期摘要当指令；涉及文件/符号/flag 时先核对当前源码。

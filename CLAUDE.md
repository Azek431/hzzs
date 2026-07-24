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
- 自动操作需要当前免责声明版本；不再要求会话级 arm，启用后运行中直接规划手势。
- MCP 默认「每次确认」、只监听 loopback；默认免 Bearer，开启鉴权时使用持久化 Token（仅主动轮换，不在每次启动更换）；完整访问也不能绕过系统权限对话框。
- Root、Shizuku、无障碍能力只能由用户**明确选择**。
- 配置、主题包、更新清单、截图尺寸与 native 输入必须有边界校验。
- 不得提交密钥、签名库、`local.properties`、真实环境变量、本地备份或生成二进制。

## 坐标、线程与所有权

- 视觉结果使用视口归一化坐标 `[0, 1]`；像素换算只允许在绘制层与手势分发层。
- `Detection.bounds` 是动作、Tracker 与距离的几何真相源；`displayContour`（若有）**仅**供 HUD，不得参与规划。
- **多点找色引擎**（`multicolor_detector.h/.cpp`）：模板坐标归一化；阈值/搜索带经 `SceneAlgorithmParams.multicolorThreshold` 与 `searchRegionTopRatio/BottomRatio`；不在帧路径解析 JSON；**不**加找色专用绘制。
- 截图帧有明确 `close()` 生命周期，禁止跨帧保存底层缓冲引用。
- WindowManager / View / Accessibility 回调必须在主线程协调。
- C++ 输入缓冲只在 JNI 调用期间借用；Native **不得**持有 Java 数组地址。
- 帧循环（`VisionRuntimeController`）是 native 引擎与 tracker 的唯一所有者；`generation` 令牌防止陈旧会话写回。

## 视觉运行时约定

- 取帧为**完成驱动**：上一轮分析结束后再 `nextFrame`；不按固定 FPS 主动丢帧（开发者 `frameRateLimit` 字段可保留，但不得假定仍被消费）。
- MediaProjection 为 CONFLATED + 最新帧；HUD 显示时临时隐身、等一帧提交，并对 MediaProjection/AUTO 排空可能含旧合成层的一帧。
- 近似轮廓与像素轮廓不得声称已迁移 C++/JNI，除非协议与测试同步落地。
- 完成驱动与轮廓说明：`docs/vision/V09_COMPLETION_DRIVEN_CONTOURS.md`。

## 多点找色检测（CC-2）

- 算法包 `sea-salt-living-room-v1`（作者：酱油，beta 通道）含海盐多点找色模板。
- `find_multi_color_patterns()` 扫描 `MultiColorPattern[]` 输出 `Detection`；不控制手势或权限。
- 模板在 `sea_salt_living_room.cpp`（设计 1272×2772、AutoJS `0xAARRGGBB`），经 `append_multicolor_detections` 合并；颜色谓词路径仍作辅检。
- 默认搜索带约 top0.438/bottom0.881、阈值 10（可经 rules 覆盖）；禁止帧路径解析 JSON。
- **不**加找色专用绘制（仅通用 `Detection.bounds`）；不移植脚本「复活」UI 点击。

## Core Philosophy · 编程核心哲学（硬约束）

工程态度与「安全不变量」同级。编程版八荣八耻（装入 Claude Code 的核心哲学与补充）：

1. 以暗猜接口为耻，以认真查阅为荣
2. 以模糊执行为耻，以寻求确认为荣
3. 以盲想业务为耻，以人类确认为荣
4. 以创造接口为耻，以复用现有为荣
5. 以跳过验证为耻，以主动测试为荣
6. 以破坏架构为耻，以遵循规范为荣
7. 以假装理解为耻，以诚实无知为菜
8. 以盲目修改为耻，以谨慎重构为荣

落地：**先查、先问、复用、验证、守架构**；不确定就坦然说不懂并查证（「为菜」= 诚实无知，不装懂）。改前检索符号与调用链；范围不清先确认；优先复用 compat/注入边界；改完跑门禁/单测并诚实写明未跑项；大改对齐架构；最小必要 diff。用户级全文见 `~/.claude/CLAUDE.md`。

## 修改流程

1. 阅读目标目录 `README.md` / `CLAUDE.md` 与 `docs/PROGRESS.md`、`docs/ARCHITECTURE.md`；触及算法包时读 `docs/ALGORITHM_SYSTEM_V1.md`。
2. 改代码后同步职责、数据流或不变量文档；用户可见行为更新 `CHANGELOG.md` `[Unreleased]`。
3. 若改动触及**硬约束 / 工作流 / 安全门控 / 默认行为 / 对外能力**，**同一任务内**同步更新本文件、**根 `README.md`**、`AGENTS.md` 与相关 `docs/*`（见下节）；表述宜短，可随源码迭代**持续润色**，禁止只改代码留文档。
4. **README / 关键文档保全**：
   - **硬禁区**：不得删除、改写或替换 `## Star History` 整节及其 `<picture>` / `star-history.com` / `api.star-history.com` 图链与 `sealed_token`；文档表链接插在 Star History **之前**。
   - **其它关键信息也不得无故删除**（除非用户明确要求）：顶部 badges、免责声明、当前版本表、截图后端表、MCP 安全边界、构建与 Release 签名说明、仓库 GitHub/Gitee 链接、许可证；`CLAUDE.md`/`AGENTS.md`/`docs/*` 中的安全不变量与门禁命令列表同理。更新策略为**在原结构上增补或修正过期句**，禁止整文件重写或清空无关章节；改完 diff 自检上述区块仍在。
5. 运行 `python tools/quality/check_resources.py` 与 `python tools/quality/check_project.py`。
6. 运行相关 JVM 单测；涉及 native 时跑宿主机/Native 门禁；再视范围跑 `:app:testDebugUnitTest` / `lintDebug` / `assembleDebug`。
7. 未验证的算法补丁、本地 ZIP、孤立头文件**不要**与无关 UI 改动混提交或合入 main。

### 本机构建注意（非产品行为）

- Hilt 使用 **KSP**（`com.google.devtools.ksp` + `ksp(libs.hilt.compiler)`），**不要**再引入 `legacy-kapt` / `kapt`。
- 日常真机 Debug：`gradle.local.properties` 的 `hzzs.native.abis=arm64-v8a`；`gradlew`/`gradlew.bat` 默认 `CMAKE_BUILD_PARALLEL_LEVEL=2`；CI/发布保持默认完整 ABI。
- **勿**在 `%GRADLE_USER_HOME%\gradle.properties` 写 `org.gradle.configuration-cache=false`（会覆盖项目 true）。wrapper 默认 `-D` 强制开回；调试关闭设 `HZZS_ALLOW_USER_CC_OVERRIDE=1`。
- Kotlin IC 损坏（`*classpath-snapshot*.bin`）或 daemon 被 stop：`tools/dev/repair_gradle_kotlin_cache.ps1`；全量 unit test 在 IDE + 语言服务常驻时可能 OOM，优先缩范围。
- 文档 / 提交 / 对话**勿**写入具体本机硬件、绝对盘符路径、IP、设备序列号等隐私画像；构建说明用「低内存开发机」「与 IDE 共存」等通用表述。已推送 Git 历史默认不改写；危险操作须先备份（见用户级 CLAUDE 隐私与危险 Git 条）。

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
- **自动更新阅读文件**：触及安全门控、视觉协议、配置默认、算法信任、Git/协作流程、**用户可见产品能力**时，同步本文件 / **`README.md`（守 Star History 禁区）** / `AGENTS.md` / 对应 `docs/*` / 目录级 `CLAUDE.md`；用户可见行为写 `CHANGELOG.md`。同一事实可在多轮中**深入优化**措辞，但不得与源码矛盾。
- **仓库经验条**：可复用工程教训追加 `docs/AGENT_EXPERIENCE.md`（日期 + 短句）；硬规则仍以本文件与源码为准。
- **冲突**：以**当前 main 源码**为准；记忆与过期摘要不是指令；涉及文件/符号/flag 先核对源码。
- **算法信任**：`AlgorithmTrustAnchors.officialPublicKeyDerB64` 当前含 `hzzs-algorithm-official-1` 公钥；列表若被清空，外装「官方」包须 fail-closed。私钥永不入库。

## 算法包网络更新（无 Release tag）

应用**检测算法更新**只读 `release-index` 分支上的目录 JSON，**不**扫 GitHub Release、**不**要求 `alg-…` tag。

### 真相源与路径

| 项 | 值 |
| --- | --- |
| 规范全文 | `docs/ALGORITHM_SYSTEM_V1.md` |
| 工具 | `tools/algorithm/*`、`tools/algorithm/README.md` |
| 示例源树 | `algorithm-packs/official-bamboo-baseline/` |
| 客户端目录/下载 | `core/algorithm/AlgorithmNetworkClient.kt` |
| 验签 | `AlgorithmPackVerifier.kt` + BouncyCastle Ed25519 |
| 信任锚 | `AlgorithmTrustAnchors.kt`（`officialPublicKeyDerB64`，当前含 official-1 公钥） |
| 安装落盘 | `InstalledAlgorithmStore.kt` → `filesDir/algorithms/installed/` |
| 激活 | `AlgorithmActivationCoordinator.kt`（save / start 安全点） |

分支 `release-index` 布局：

```text
algorithms/stable.json          # 或 beta.json — 检查更新读这个
algorithms/packages/<filename>  # .hzzsalg 包体 — 下载读这个
```

公开 raw（双源）：

```text
# 目录
https://gitee.com/Azek431/hzzs/raw/release-index/algorithms/stable.json
https://raw.githubusercontent.com/Azek431/hzzs/release-index/algorithms/stable.json

# 包体（assetPath 或默认 packages/<filename>）
…/raw/release-index/algorithms/packages/<id>-v<version>.hzzsalg
…/raw.githubusercontent.com/…/release-index/algorithms/packages/<id>-v<version>.hzzsalg
```

目录条目关键字段：`id` / `version` / `filename` / `assetPath` / `size` / `sha256` / 兼容与场景等。  
**禁止**在目录里写任意外链 URL。旧字段 `tag` 若仍存在，客户端**忽略**，一律走 packages 路径。

### 安全硬规则（代理必须遵守）

1. **私钥永不入库、不进对话日志、不进 commit**：仅 CI Secret `ALGORITHM_SIGNING_PRIVATE_KEY_B64` 或本机用户提供的安全路径；与 APK keystore **分离**。
2. **公钥**可进仓库：写入 `AlgorithmTrustAnchors.officialPublicKeyDerB64`（DER SubjectPublicKeyInfo 的 base64）。列表为空时下载安装必须 fail-closed。
3. 包内仅声明式 JSON/文本；禁止 `.so`/Dex/脚本/模型权重；验签失败不得安装。
4. 算法包**不得**控制手势、Root、包白名单、automation、截图升权。
5. 发布顺序：**先** packages 资产并双侧 raw 校验，**最后**才更新 `algorithms/{channel}.json`（避免「检测到了却下不下来」）。
6. 默认 `publish_algorithm_release.py` 为 **dry-run**；真正上传须用户明确要求并带 `--execute` 与有效 token。

### 客户端检测与下载逻辑（代理改代码时勿回退）

```text
设置通道 STABLE|BETA + sourcePreference
  → HTTPS 拉 algorithms/{channel}.json（Gitee 优先可回退 GitHub）
  → 列表展示；点下载（或 autoDownload）
  → raw 下 packages 资产；校验 size + sha256
  → AlgorithmPackVerifier（ZIP 白名单 + Ed25519 信任锚）
  → InstalledAlgorithmStore 落盘
  → ActivationCoordinator：未分析且 AUTO 可立即 configure；否则 pending，save/下次 start
```

- 目录检查：小 JSON，**不**受「仅 Wi‑Fi 下大文件」限制。  
- 包下载：遵守 `UpdateConfig.wifiOnly`。  
- `algorithm.autoCheck`：启动时可刷新目录；`autoDownload`：仅在有信任锚时尝试下最新兼容包。

### 版本号与通道（算法包语义化版本）

算法包版本写在 **`algorithm-packs/<id>/manifest.json` 的 `version`**，与 **App 的 `0.1.0` / versionCode 相互独立**。  
格式：`MAJOR.MINOR.PATCH`（可加预发布后缀，如 `0.2.0-beta.1`，仅建议上 **beta** 通道）。

| 变更类型 | 版本怎么加 | 典型场景 |
| --- | --- | --- |
| **首版** | 固定从 **`0.1.0`** 起（产品约定；官方示例包同此） | 首次对外可检测/可安装的算法包 |
| **补丁** | `PATCH +1`（`x.y.z` → `x.y.(z+1)`） | 修阈值/文案/小回归、兼容修复、changelog 勘误 |
| **次要** | `MINOR +1`，`PATCH` 归零（`x.y.z` → `x.(y+1).0`） | 完整一波识别改进、新场景参数段、行为可感知的一轮交付 |
| **主要** | `MAJOR +1`，`MINOR/PATCH` 归零 | 破坏性 schema、不兼容旧引擎 API、强制抬 `minimumAppVersionCode` / `engineApiVersion` |

**硬规则（代理必须遵守）：**

1. **禁止「改完就先 bump」**。顺序永远是：改内容 → **验证全过** → 再改 `manifest.version`（及 `CHANGELOG.txt`）→ 再打包/发布。  
2. **验证未通过不得升高版本号、不得 `--execute` 上传**。  
3. 用户未指定时：默认按上表推断；有歧义先问（补丁 vs 次要）。  
4. **通道**（与版本独立，用户可选）：  
   - **`beta`（测试）**：实验、预发布后缀、未充分真机验证 → `algorithms/beta.json`  
   - **`stable`（稳定）**：验证通过且用户确认可给大众 → `algorithms/stable.json`  
5. App 设置已有 **算法通道 STABLE / BETA**（`AlgorithmConfig.channel`）；用户自选订阅。代理 `--channel` 须与用户意图一致，**不得**把未验证包写进 stable。  
6. 同一 `(id, version)` 不可重复；修正已发布错误应 **升 PATCH 再发**，禁止静默改同版本包体哈希。

### 发布前验证门禁（通过后才允许 bump + 发布）

| 级别 | 命令 / 动作 | 何时必须 |
| --- | --- | --- |
| L0 包结构 | `python tools/algorithm/validate_algorithm_pack.py --source <源树>`（或等价） | 任何 pack 改动 |
| L1 工具链单测 | `python -m unittest discover -s tools/algorithm/tests -v` | 任何 pack/tools 改动 |
| L2 项目门禁 | `python tools/quality/check_resources.py` 与 `check_project.py` | 默认真发前；仅改 packs 也建议 |
| L3 引擎相关 | 动 `engineParams` 或识别行为时：相关 JVM 单测；有条件 native/代表帧/批跑 | 识别行为变更 |
| L4 真机 | 装包激活无崩溃、无明显误动作（与用户确认） | 上 **stable** 前强烈建议 |

失败时修问题并**保持原 version**，不得为过门禁跳过断言。

### 代理如何帮用户「发布算法更新」（推荐流程）

用户说「发算法包 / 更新算法目录 / 让应用能检查到 / 修完发一版 / **全部算法都发布**」时执行下列步骤；**不得**再创建 `alg-…` Release tag（产品 B：无 tag）。

#### 常驻约定（用户偏好 · 代理必须记住）

1. **改了算法包内容且任务目标是让用户/手机拿到新包时**：门禁通过后**自动**升 `manifest.version`（+ `CHANGELOG.txt`），再走发布脚本；**禁止**只改 rules 不 bump、也禁止只 bump 不上传（用户明确只要本地改除外）。  
2. **用户说「发布 / 全部发布 / 你发布」**：视为授权对本仓库 `algorithm-packs/*`（排除 `official-public-keys`）做 **`--execute` 真上传**（仍须先 L0–L3；缺密钥/token 时**停下说明缺什么**，不得编造已发布）。  
3. **多包**：默认**每个**可发布源树各跑一遍 `publish_algorithm_release.py`；通道优先用该包 `manifest.channel`（缺省：实验/未验证 → beta，用户说正式/稳定 → stable）。  
4. **首次目录为空**（`release-index` 404）：仍可按上表首发 `0.1.0`（无需为「能检查到」强行 +PATCH，除非该 version 已在远端占用）。  
5. **密钥与 token**：只从环境变量或用户给出的**本机路径**读取；**禁止**要求用户把私钥/token 贴进聊天；**禁止**写入仓库或 commit。  
6. 发布成功后：用匿名 raw 抽查目录/包体；同步相关 docs/`CHANGELOG` 若用户可见；**记得 git commit** 源树 version/changelog 变更（与 [[remember-to-git-commit]] 一致）。  
7. 同步 **assets 捆绑树**（`app/src/main/assets/algorithms/<id>/`）与 `algorithm-packs/<id>/` 内容一致，避免 APK 出厂种子与已发布包分叉（bundled 仍不覆盖已装同 id，正式升级靠网络目录）。

#### 0. 确认前置

- [ ] 真上传：本机是否有可用 `GH_TOKEN`/`GITHUB_TOKEN`、`GITEE_TOKEN`、算法签名私钥（路径或 `ALGORITHM_SIGNING_PRIVATE_KEY_B64`）；**禁止**用户把私钥贴进聊天  
- [ ] 通道：各包 `manifest.channel` 或用户指定；未说清时实验默认 **beta**；明确「正式/稳定」才 stable  
- [ ] 版本意图：内容未变且远端尚无该 version → 可发当前 version；内容已变 → 门禁后 **bump**（补丁/次要/主要见上表）  
- [ ] 源树：用户说「全部」→ 枚举 `algorithm-packs/*/` 中含 `manifest.json`+`rules.json` 的目录；否则用用户点名的源树  
- [ ] `AlgorithmTrustAnchors` 是否已配置；若空须告知「目录可更新，手机仍装不上外装包」  
- [ ] 缺密钥/token：**立即停止 `--execute`**，列出缺失项与设置方式，不假装已发布  

#### 1. 改包内容（若需要）— **先不改 version**

- 改 `rules.json`（schema v2：`userThresholds` + `engineParams`）等  
- **不要**先改 `manifest.version`（等门禁过后再 bump）

#### 2. 跑验证门禁（L0–L3；stable 确认 L4）

- 全过 → 步骤 3；失败 → 修复重跑，**不 bump、不上传**

#### 3. 验证通过后：递增版本 + changelog（内容有变时）

- 按上表改 `manifest.json` 的 `version`（及 assets 镜像树同 version）  
- 更新 `CHANGELOG.txt`（禁止准确率吹嘘）  
- 再跑 L0 确认版本合法  
- 内容未变且作**目录首发**：可保持现有 version  

#### 4. 本地 dry-run 发布

```powershell
python tools/algorithm/publish_algorithm_release.py `
  --source algorithm-packs/official-bamboo-baseline `
  --work-dir build/algorithm-release `
  --channel stable `
  --private-key <用户本机密钥路径> `
  --key-id hzzs-algorithm-official-1
```

多包时对每个源树重复；通道与该包一致。  
期望：`would upload algorithms/packages/…`、`would publish catalog … algorithms/{channel}.json`；**不得**出现 create GitHub release / `alg-` tag。

#### 5. 真发布（用户已说发布 / 常驻约定第 2 条 + 门禁已过 + 密钥齐）

```powershell
# 在本机 shell 预先设置（勿贴进聊天记录）：
# $env:GH_TOKEN / $env:GITHUB_TOKEN
# $env:GITEE_TOKEN
# $env:ALGORITHM_SIGNING_PRIVATE_KEY_B64  或  --private-key 路径
# $env:ALGORITHM_SIGNING_KEY_ID = "hzzs-algorithm-official-1"

python tools/algorithm/publish_algorithm_release.py `
  --source algorithm-packs/official-bamboo-baseline `
  --work-dir build/algorithm-release/bamboo `
  --channel stable `
  --key-id hzzs-algorithm-official-1 `
  --execute

python tools/algorithm/publish_algorithm_release.py `
  --source algorithm-packs/sea-salt-living-room-v1 `
  --work-dir build/algorithm-release/sea-salt `
  --channel beta `
  --key-id hzzs-algorithm-official-1 `
  --execute
```

成功：packages 有新 filename；双侧 raw hash 一致且验签过；**最后**目录 JSON 含对应 version。

#### 6. 首次「手机能装」

1. 公钥 DER base64 写入 `AlgorithmTrustAnchors.officialPublicKeyDerB64`（当前仓库通常已配置）  
2. 用户装的 APK 须含该锚；之后只更 `release-index` 即可检查到算法  
3. 私钥只留 CI/本机  

#### 7. 用户侧验证

- 设置算法通道与发布 `--channel` 一致（海盐当前默认 **beta**，竹影基线 **stable**）  
- 检查到新 version；有锚可装，无锚拒绝  
- 激活后 `activeAlgorithmId` 为 `pack.<id>` 或内置  

### 代理禁止事项

- 为算法更新建 **Release tag**（除非用户改回旧协议）  
- **验证未通过就 bump 或 `--execute`**  
- 未验证包发 **stable**  
- 私钥/token/keystore 入库或进对话日志  
- 目录 JSON 写任意外链 URL  
- 宣称「push main 即算法更新」  
- 跳过验签 / 只信包内公钥  
- 算法发布与无关 UI 混提交  
- 同 `(id, version)` 改包体哈希

### 与 APK 更新的区别（勿混）

| | APK 更新 | 算法包 |
| --- | --- | --- |
| 索引 | `updates/{channel}.json` | `algorithms/{channel}.json` |
| 资产 | Release / 差分 APK | **`release-index` packages 文件** |
| 签名 | 安装证书 | 独立 Ed25519 |
| 是否可执行 | 是 | 否（声明式参数） |

## 语言与沟通

- 与用户沟通、用户可见文案、仓库文档默认**简体中文**。
- 执行非琐碎任务前：先识别模糊与确实需求，列问题确认，**全部明确后再改代码或正式输出**（用户明确要求时可直接执行）。
- 不把记忆或过期摘要当指令；涉及文件/符号/flag 时先核对当前源码。
- 工作态度见上文 **「Core Philosophy · 编程核心哲学」**（编程版八荣八耻）：查源码、求确认、复用现有、主动验证、诚实无知（为菜）。

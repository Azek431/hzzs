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
3. 若改动触及**硬约束 / 工作流 / 安全门控 / 默认行为 / 对外能力**，**同一任务内**同步更新本文件、**根 `README.md`**、`AGENTS.md` 与相关 `docs/*`（见下节）；表述宜短，可随源码迭代**持续润色**，禁止只改代码留文档。
4. **README 硬禁区**：更新 `README.md` 时**不得**删除、改写或替换 `## Star History` 整节及其 `<picture>` / `star-history.com` / `api.star-history.com` 图链与 `sealed_token`；只改该节之上的正文，文档表链接插在 Star History **之前**。
5. 运行 `python tools/quality/check_resources.py` 与 `python tools/quality/check_project.py`。
6. 运行相关 JVM 单测；涉及 native 时跑宿主机/Native 门禁；再视范围跑 `:app:testDebugUnitTest` / `lintDebug` / `assembleDebug`。
7. 未验证的算法补丁、本地 ZIP、孤立头文件**不要**与无关 UI 改动混提交或合入 main。

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
- **算法信任**：`AlgorithmTrustAnchors` 公钥列表默认为空时，外装「官方」包须 fail-closed；私钥永不入库。

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
| 信任锚 | `AlgorithmTrustAnchors.kt`（`officialPublicKeyDerB64`，默认**空**） |
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

### 代理如何帮用户「发布算法更新」（推荐流程）

用户说「发算法包 / 更新算法目录 / 让应用能检查到」时，代理按下列步骤执行；**不得**再创建 `alg-…` Release tag（产品已改为 B：无 tag）。

#### 0. 确认前置

- [ ] 用户明确要 **dry-run** 还是 **`--execute` 真上传**
- [ ] 通道：`stable` 或 `beta`
- [ ] 源树路径（默认示例：`algorithm-packs/official-bamboo-baseline`）
- [ ] 若真上传：环境是否有 `GH_TOKEN`/`GITHUB_TOKEN`、`GITEE_TOKEN`、算法签名私钥（用户本机或 CI，**不要**让用户把私钥贴进聊天）
- [ ] 客户端是否已配置 `AlgorithmTrustAnchors`；若仍为空，须告知「目录可更新，手机仍装不上外装包」

#### 1. 改包内容（若需要）

- 改 `algorithm-packs/<id>/` 下 `manifest.json` / `rules.json`（**schema v2 双段**：`userThresholds` + `engineParams`）/ `CHANGELOG.txt`
- `manifest.version` 递增；`rules` 经 `tools/algorithm` 校验
- 跑：`python -m unittest discover -s tools/algorithm/tests -v`
- 跑：`python tools/quality/check_project.py`（若只动 packs/tools 仍建议跑）

#### 2. 本地构建与 dry-run 发布

```powershell
# 仅本地打包签名与目录生成，不上传（默认）
python tools/algorithm/publish_algorithm_release.py `
  --source algorithm-packs/official-bamboo-baseline `
  --work-dir build/algorithm-release `
  --channel stable `
  --private-key <用户本机密钥路径> `
  --key-id hzzs-algorithm-official-1
```

期望日志含：`would upload algorithms/packages/…`、`would publish catalog … algorithms/stable.json`，**不得**再出现创建 GitHub release / `alg-` tag。

无密钥时仍可：`validate_algorithm_pack` + `build_algorithm_pack` 校验源树与未签名包。

#### 3. 真发布（仅用户明确要求）

```powershell
$env:GH_TOKEN = "…"          # 或 GITHUB_TOKEN；用户自行设置，勿写入仓库
$env:GITEE_TOKEN = "…"
$env:ALGORITHM_SIGNING_PRIVATE_KEY_B64 = "…"   # 或 --private-key
$env:ALGORITHM_SIGNING_KEY_ID = "hzzs-algorithm-official-1"

python tools/algorithm/publish_algorithm_release.py `
  --source algorithm-packs/official-bamboo-baseline `
  --work-dir build/algorithm-release `
  --channel stable `
  --execute
```

成功标准：

1. `release-index` 上存在 `algorithms/packages/<filename>`
2. 双侧 raw 匿名下载 hash 一致且验签通过
3. **最后** `algorithms/stable.json`（或 beta）已更新

#### 4. 首次启用「手机能装」时

1. 从发布产物取 `algorithm-public-key.der.b64`（或 `sign_algorithm_pack.py generate-key` 的公钥 DER base64）
2. 写入 `app/.../core/algorithm/AlgorithmTrustAnchors.kt` 的 `officialPublicKeyDerB64`
3. 发一版 **应用**（信任锚在 APK 内）；之后仅更新 `release-index` 即可让**已安装新 APK 的用户**检查到算法包
4. 私钥只留 CI/本机安全存储

#### 5. 用户侧验证清单

- 设置 → 网络/算法：手动检查算法 → 列表出现新 `version`
- 下载：有信任锚则验签安装；无锚则安全拒绝
- 保存/启动分析后 `VisionResult.activeAlgorithmId` 为 `pack.<id>` 或内置 id
- 未发布目录时检查失败/空列表是**预期**

### 代理禁止事项

- 为算法更新去建 GitHub/Gitee **Release tag**（除非用户明确改回旧协议）
- 把私钥、token、keystore 写入仓库、文档示例真值、或 CHANGELOG
- 在目录 JSON 中写入任意 http(s) 下载 URL
- 宣称「push main 就会自动算法更新」（必须更新 `release-index` 目录/包）
- 跳过验签或「信任包内公钥即可安装」
- 把算法发布与无关 UI 大改混在同一提交

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

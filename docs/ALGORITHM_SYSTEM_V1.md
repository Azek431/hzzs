# 算法包系统 v1

本文描述 HZZS **官方算法包**（`.hzzsalg`）的格式、校验边界、签名、目录与发布顺序。  
第一版只发布官方包；应用内安装器可后续接入，但**不得**因本工具链而绕过安全边界。

## 目标

- 将可分发的场景参数与规则从 APK 发布周期中解耦。
- 使用**独立于 APK keystore** 的算法签名密钥。
- 双源（Gitee 优先目录，GitHub/Gitee 资产镜像）与可重复构建。
- 目录永远最后发布；资产默认不可变。

## 包格式 `.hzzsalg`

确定性 ZIP（固定条目排序、固定时间戳 `1980-01-01 00:00:00`、DEFLATE level 9）。

| 文件 | 必需 | 说明 |
|---|---|---|
| `manifest.json` | 是 | 包身份与兼容性元数据 |
| `rules.json` | 是 | 场景阈值与障碍开关（声明式） |
| `CHANGELOG.txt` | 是 | 纯文本变更说明 |
| `signature.json` | 签名后 | Ed25519 签名信封 |

### 白名单与限制

- 仅允许上表文件名（根目录，无嵌套路径）。
- 最多 16 个条目。
- 单文件 ≤ 256 KiB，未压缩合计 ≤ 1 MiB，压缩包 ≤ 1 MiB。
- 拒绝：符号链接、目录条目、路径穿越（`..`）、绝对路径、反斜杠、重复路径。
- 拒绝可执行扩展名（`.exe` `.so` `.js` `.py` `.apk` 等）。
- JSON 深度、键数量与字符串长度有上限。

### `manifest.json` 字段

| 字段 | 说明 |
|---|---|
| `schemaVersion` | 固定 `1` |
| `id` | 小写 kebab-id，如 `official-bamboo-baseline` |
| `version` | 语义化版本，如 `1.0.0` |
| `displayName` | 展示名 |
| `description` | 描述（禁止准确率吹嘘） |
| `engineId` | 默认 `native-vision` |
| `engineApiVersion` | 固定 `1` |
| `minimumAppVersionCode` | 最低应用 `versionCode` |
| `supportedScenes` | 如 `BAMBOO_BOOKSTORE` |
| `releaseDate` | `YYYY-MM-DD` |
| `author` | 作者 |
| `revoked` | 是否撤销 |
| `channel` | 可选 `stable` / `beta` |

### `rules.json`

**现状（schema v1 / 工具链）**：按 `supportedScenes` 提供 `thresholds`（对齐用户侧 `VisionThresholds`）与可选 `disabledObstacles`。  
**目标（schema v2，设计中）**：双段 `userThresholds` + `engineParams`——前者映射 `SceneConfig`，后者映射 `AlgorithmRuntimeProfile` / C++ 快照；v1 包仅当 user 段，engine 用 builtin 填洞。

第一版不包含脚本、模型权重或远程 URL。  
**应用内安装器尚未接入**：设置页目录/下载为 UI 演示，不会落盘 `.hzzsalg` 或调用 `configureAlgorithm`。

### `signature.json`

```json
{
  "schemaVersion": 1,
  "keyId": "hzzs-algorithm-official-1",
  "signatureAlgorithm": "Ed25519",
  "publicKeyDerB64": "...",
  "signedPayload": "{...canonical json...}",
  "signature": "base64"
}
```

`signedPayload` 绑定：`algorithmId`、`version`、各文件 `name/size/sha256`、`keyId`、算法名。  
客户端应内置信任公钥；包内公钥仅作调试对照，生产校验以内置信任锚为准。

## Release 标签

```text
alg-<algorithmId>-v<version>
```

示例：`alg-official-bamboo-baseline-v1.0.0`  
附件名：`official-bamboo-baseline-v1.0.0.hzzsalg`

## 算法目录

发布在 `release-index` 分支：

```text
algorithms/stable.json
algorithms/beta.json
```

公开读取（与更新索引同分支策略）：

- Gitee：`https://gitee.com/Azek431/hzzs/raw/release-index/algorithms/stable.json`
- GitHub：`https://raw.githubusercontent.com/Azek431/hzzs/release-index/algorithms/stable.json`

### 目录字段

| 字段 | 说明 |
|---|---|
| `schemaVersion` | `1` |
| `generatedAt` | UTC ISO-8601 |
| `channel` | `stable` / `beta` |
| `keyId` | 签名密钥 ID |
| `algorithms[]` | 算法条目 |
| `catalogSignature` | 对 `signedPayload` 的 Ed25519 签名 |
| `signedPayload` | 规范化 JSON 载荷 |

每个 `algorithms[]` 条目：

| 字段 | 说明 |
|---|---|
| `id` / `version` / `tag` / `filename` | 身份与资产名 |
| `size` / `sha256` | 完整性 |
| `engineId` / `engineApiVersion` | 引擎兼容 |
| `minimumAppVersionCode` | 应用兼容 |
| `supportedScenes` | 场景 |
| `description` / `changelog` / `releaseDate` | 说明 |
| `revoked` | 撤销 |
| `displayName` / `author` | 展示 |

**不写下载 URL。** 客户端按 `source + tag + filename` 生成：

```text
https://gitee.com/Azek431/hzzs/releases/download/<tag>/<filename>
https://github.com/Azek431/hzzs/releases/download/<tag>/<filename>
```

`stable` 与 `beta` 目录相互隔离；撤销版本通过 `revoked=true` 表达，不静默删除历史资产。

## CLI

位于 `tools/algorithm/`：

| 命令 | 作用 |
|---|---|
| `validate_algorithm_pack.py` | 校验源树 |
| `build_algorithm_pack.py` | 可重复构建未签名包 |
| `sign_algorithm_pack.py` | Ed25519 签名 / 生成密钥 |
| `verify_algorithm_pack.py` | 验签与完整性 |
| `build_algorithm_catalog.py` | 构建并签名目录 |
| `publish_algorithm_release.py` | 端到端发布（默认 dry-run） |

### Secrets（独立于 APK）

| Secret | 说明 |
|---|---|
| `ALGORITHM_SIGNING_PRIVATE_KEY_B64` | 算法 Ed25519 私钥 PEM 的 base64 |
| `ALGORITHM_SIGNING_KEY_ID` | 如 `hzzs-algorithm-official-1` |
| `GITEE_TOKEN` | Gitee 同步（执行模式） |
| GitHub `contents: write` | workflow token 上传 Release |

**禁止**使用 `ANDROID_KEYSTORE_*` 为算法包签名。

## 发布顺序（原子性）

1. 校验工作树算法源  
2. 构建 `.hzzsalg`  
3. 签名并本地反向验证  
4. 创建/更新 GitHub Release（可 draft）并上传包 + `SHA256SUMS` + 公钥  
5. 同步**完全相同**附件到 Gitee（`--immutable`：哈希一致跳过，不一致失败；不先删后传）  
6. 匿名下载 GitHub 与 Gitee 包  
7. 比较两侧 SHA-256  
8. 验证两个下载包签名  
9. 生成并签名 `stable.json` / `beta.json`  
10. **最后**更新 GitHub/Gitee `release-index` 的 `algorithms/*.json`  

任一步失败不得发布新目录。重复执行必须幂等。日志不得泄漏 token。

## 官方示例包

`algorithm-packs/official-bamboo-baseline/`：

- 镜像内置 `VisionThresholds` 默认值  
- 仅 `BAMBOO_BOOKSTORE`  
- 不伪造准确率  

## 测试

```bash
python -m unittest discover -s tools/algorithm/tests -v
python tools/algorithm/publish_algorithm_release.py \
  --source algorithm-packs/official-bamboo-baseline \
  --work-dir build/algorithm-release \
  --private-key /secure/key.pem \
  --key-id hzzs-algorithm-official-1
```

覆盖：可重复构建、签名、篡改、路径穿越、Zip 炸弹模拟、超大文件、禁止扩展名、目录排序、stable/beta 隔离、撤销、发布 dry-run。

## 与 APK 更新系统的关系

| | APK 更新 | 算法包 |
|---|---|---|
| 资产 | `.apk` / `.hzzsdelta` | `.hzzsalg` |
| 索引路径 | `updates/{channel}.json` | `algorithms/{channel}.json` |
| 签名密钥 | 安装证书 / keystore | 独立 Ed25519 |
| Tag | `vX.Y.Z` | `alg-<id>-vX.Y.Z` |

两者共享 Gitee 同步与 `release-index` 分支惯例，但密钥与目录命名空间隔离。

## 运行时（CC-1，引擎接入）

安装器 / 下载器负责验签与落盘；**本层**只消费已构造的 `AlgorithmRuntimeProfile`。

### 契约

| 类型 | 职责 |
| --- | --- |
| `AlgorithmRuntimeProfile` | 不可变声明式视觉参数（Kotlin + C++ 镜像） |
| `ActiveAlgorithmProvider` | 进程级激活、generation、失败回退内置 |
| `NativeVision.configureAlgorithm` | JNI 配置入口；与 `analyze` 串行 |
| `NativeVision.analyze` | 只读当前 generation 快照 |
| `NativeVision.activeAlgorithmGeneration` | 诊断 / 防旧帧写回 |
| `NativeVision.reset` | **仅**清分析瞬时状态（当前实现可为空操作）；**不**回退 builtin、**不**递增 generation。回退请 `configureAlgorithm(builtin)` |

### 数据流

```text
（目标）安装器验签 → 解析 rules → AlgorithmRuntimeProfile
ActiveAlgorithmProvider.activate / VisionEngine.configureAlgorithm(profile)
  → AlgorithmProfileValidator（finite / 范围 / 白名单 / schema）
  → NativeVision.configureAlgorithm(profile)   // 安全切换点
  → AlgorithmRuntime 替换不可变快照，generation++
  → 帧循环观察到 generation 变化后清空 tracker / ledger / 动作缓存
analyze(frame) 只读当前 generation 对应快照
失败 → 保留旧配置或回退 builtin，NativeVision 保持可用

（现状）进程默认 builtin.hzzs.v1；设置里的 AlgorithmConfig / 目录选择尚未桥接到上述激活路径。
```

### 允许外部化的参数（目标完整列表）

- 场景 / 玩家置信度下限
- 固定玩家参考框比例
- 主路径→启发式回退阈值
- 地面搜索区间与地面置信度
- 障碍尺寸比例窗口（瓶 / 蛋糕 / 雕像 / 竹隙 / 笔刷 / 刺）
- 启发式颜色通道阈值

### 当前引擎消费边界（诚实）

| 路径 | 实际读取的 profile 字段 |
| --- | --- |
| 主路径 `legacy_main`（vision2 / bamboo） | 主要是 confidence floor、固定玩家框几何；尺寸/颜色谓词仍硬编码 |
| 启发式回退（主路径过弱时） | 尺寸窗、地面搜索、颜色通道等完整 `SceneAlgorithmParams` |
| 用户 `VisionThresholds` | 经 `SceneConfig` 每帧传入（workWidth、玩家 X、障碍 mask 等），**不是**算法包 engine 段 |

主路径全参数化按里程碑分阶段推进；完成前不得宣称「装包即可全面调识别」。

### 故意仍硬编码（直至参数化里程碑）

| 项 | 原因 |
| --- | --- |
| `legacy_main` 主路径内部像素谓词与扫描步长 | 历史核心完整参数化成本高，易破坏回归（目标分阶段注入） |
| 玩家连通域合并与密度阈值 | 与设备采样强耦合 |
| 手势、双跳间隔、包名白名单、自动化门禁 | **安全边界**：算法包不得控制操作与权限 |
| 输入尺寸 / 检测数上限 | 防 DoS 与整数溢出 |

### 性能与安全

- 不在每帧读文件、解析 JSON 或分配大型规则对象
- configure 与 analyze 串行，防止半热切换与 use-after-free
- 网络算法配置不得控制手势 / Root / 包名白名单 / 安全门禁
- 诊断字段：`activeAlgorithmId`、`activeAlgorithmVersion`、`algorithmGeneration`、`usingBuiltinFallback`、`algorithmLoadError`

## 设置 UI 契约

设置模块通过 `AlgorithmCatalogController`（`StateFlow<AlgorithmCatalogState>`）展示目录与下载任务。

### 配置（schema v6）

```text
AlgorithmConfig
  selectionMode: AUTO | MANUAL
  pinnedAlgorithmId: String?
  channel: STABLE | BETA
  autoCheck: Boolean
  autoDownload: Boolean

UpdateConfig.sourcePreference: AUTO | PREFER_GITEE | PREFER_GITHUB
```

- 选择模式、通道、来源偏好属于**草稿**，保存后才持久化。
- 检查目录 / 下载 / 校验是**即时任务**，不走主题预览。
- **现状**：`AlgorithmCatalogController` 使用样本目录与模拟下载；`autoCheck` / `autoDownload` 可落盘但**无后台调度**；保存 `pinnedAlgorithmId` **尚未**调用 `configureAlgorithm`。
- **目标**：手动下载不自动激活；需用户保存选择。运行中切换显示 pending（下次启动分析时应用）。
- 第一版算法包是声明式视觉参数，不是任意代码；不得动态加载 `.so` / Dex。
- 识别赛季 / `VisionThresholds` 预览可热更新；算法包字段预览锁定 baseline。

### 页面状态

| phase | UI |
| --- | --- |
| Loading | 加载空状态卡 |
| Empty | 空目录 + 重试 |
| OfflineWithCache | 错误横幅 + 本地列表 |
| MirrorFallback | 警告卡 |
| SecurityWarning | 警告卡，阻止不可信包 |
| Downloading / Verifying | 进度卡 + 取消 |
| PendingActivation | 待启用说明 |
| Error / Incompatible | 错误/警告 + 重试 |

### 排序

远端：兼容优先 → 支持当前场景 → versionCode → 发布时间。  
已安装：当前使用 → 内置 → versionCode。

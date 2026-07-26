# core/update — 应用内双源签名更新

仅 HTTPS、清单签名绑定**本安装包证书**公钥的应用内更新（APK / 差分）。**仅改 URL / 偏好无法伪造信任**；Debug 包故意无法认证生产发行清单。

## 职责

- `UpdateRepository`：Gitee 优先、GitHub 校验；双源均成功时要求 signedPayload 一致；按 `UpdateSourcePreference` 决定首选源。
- `UpdateManifest` / `Artifact` / `PatchArtifact`：清单模型（含签名绑定）。
- `UpdateFileVerifier`：安装前校验（包名 / versionCode / 证书 / 哈希）。
- `DeltaPatchApplier` / `ApkInstaller`：差分包应用与安装跳转。
- 边界：体积、文件名、哈希、SHA256 正则、安全 asset/tag 名。

## 入口

1. `UpdateModels.kt` — 仓库 + 清单模型（`UpdateRepository` 类较大）。
2. `feature/settings/SettingsViewModel`（checkForUpdates / downloadAvailableUpdate / installDownloadedUpdate / ignoreAvailableUpdate）、`feature/settings/screens/NetworkUpdateSettingsScreen`（网络/更新设置页）。

## 不变量 / 安全

- 清单签名绑定本安装包证书公钥；安装前须 `UpdateFileVerifier.verifyPackage`。
- 仅 HTTPS；体积上限（manifest 1MB / artifact 1GB / patch manifest 4MB / patch ops 100k）。
- `wifiOnly` 严格遵循，不可 force 绕过；未正式发布索引时 `check` 失败属预期。
- MCP `check_update` 包装 `UpdateRepository.check`，失败返回 error 而非抛异常。

## 改这个包前必读

- 改 `UpdateRepository.check`：同步 `feature/settings/SettingsViewModel`、`docs/*`（更新说明）、MCP `check_update`。
- 改清单 schema：同步 `UpdateManifest.schemaVersion`、`UpdateFileVerifier`、差分包格式。
- 算法包更新是**另一独立系统**（`core/algorithm/*` + `release-index`），不要与 APK 更新混淆（见 `CLAUDE.md` 「与 APK 更新的区别」）。

## 测试

- 门禁：`python tools/quality/check_resources.py`、`python tools/quality/check_project.py`、`:app:testDebugUnitTest`。
- 真机验证：Gitee/GitHub 双源、wifiOnly、签名校验、差分包应用。

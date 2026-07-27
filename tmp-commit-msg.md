feat(algorithm): 移除 Ed25519 签名验签，0.1.0 简化为 sha256 + ZIP 白名单

## 动机

首发 0.1.0 前简化算法包校验链路：Ed25519 签名体系依赖私钥托管（CI Secret / 本机安全路径）和公钥内嵌（`AlgorithmTrustAnchors`），引入额外运维负担且私钥配置未就位。首发阶段 HTTPS + size/sha256 + ZIP 白名单已能防御篡改与路径穿越；签名约束保留文档标记，待私钥就位后回滚启用。

## 改动

- **删除** `AlgorithmPackVerifier.kt`（8KB）、`AlgorithmTrustAnchors.kt`（1.3KB）、`AlgorithmSignatureState` 枚举、`SecurityWarning` 相位、`trustAnchorsConfigured` 状态字段、`sign_algorithm_pack.py`（286 行）、`check_signing_secret.py`（74 行）
- **Kotlin 客户端**：`AlgorithmNetworkClient` 内联 ZIP 白名单解压（`readZipWhitelist`，含路径穿越/重复/大小上限校验）；远端下载不再因信任锚缺失而 fail-closed；UI 移除签名徽标、信任锚警告卡片、「需公钥后下载」降级按钮
- **Pure 函数**：`parseCatalog` / `planUpgrades` 移除 `trustAnchorsConfigured` 参数
- **工具链**：`common.py` 删 `SIGNATURE_*` 常量；`build_algorithm_catalog.py` / `publish_algorithm_release.py` / `prepare_algorithm_release.py` 移除签名/验签步骤；`verify_algorithm_pack.py` 简化为完整性校验；`cryptography` 依赖从 CI/工具链移除
- **CI**：`algorithm-release.yml` 不再需要 `ALGORITHM_SIGNING_*` Secrets，只跑 `requests` 无需 `cryptography`
- **文档同步**：`CLAUDE.md`（4 处）/ `AGENTS.md` / `CHANGELOG.md` / `docs/ALGORITHM_SYSTEM_V1.md` / `docs/algorithm/publishing.md` / `docs/algorithm/ALGORITHM_SWITCHING.md` / `docs/SECURITY.md` / `docs/PROGRESS.md` / `domain/vision/CLAUDE.md` / `core/algorithm/CLAUDE.md` / `core/algorithm/logic/CLAUDE.md` 全部标注 ⚠「暂不启用」并更新链路描述
- **测试**：`AlgorithmCatalogPureTest` 删除签名断言与 `planUpgrades_flagsLackOfTrustAnchor`；`SettingsUiLogicTest` 删除 `AlgorithmSignatureState` label 测试；`test_algorithm_pack_tooling.py` 替换为无符号包构建+完整性校验，新增 `test_unsigned_package_builds_and_verifies`

## 不变量

- 两点激活语义、generation 单调递增、fail-closed（Profile 校验失败回退内置）均不变
- `algorithm-packs/official-public-keys/` 目录保留作未来扩展
- 包内 `publicKeyDerB64` 字段（manifest 中若有）仍被校验逻辑忽略，不做信任判断
- 无符号包目录 schema、`release-index` 协议、目录 schema 保持原样，签名回滚只需恢复删除文件

## 验证

- 工具链单测：21/21 OK
- grep 全量残留检查：`app/src` 内无 `AlgorithmSignatureState` / `AlgorithmTrustAnchors` / `AlgorithmPackVerifier` / `SecurityWarning` / `trustAnchorsConfigured` 引用

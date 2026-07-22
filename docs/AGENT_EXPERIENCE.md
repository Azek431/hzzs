# 代理经验摘录

跨会话可复用的**工程经验**（短条）。硬约束仍以根目录 `CLAUDE.md` 与源码为准。  
会话级偏好写入 Claude 项目记忆；此处只记对仓库协作者也有用的条目。

## 2026-07-22

- **应用内悬浮窗开关 ≠ 系统权限**：`OverlayConfig.enabled` 只控制是否尝试加窗；真正门闩是 `Settings.canDrawOverlays`。缺权限时帧循环可正常、芯片「无悬浮窗」，须引导用户进系统设置。
- **信任锚 fail-closed**：`AlgorithmTrustAnchors.officialPublicKeyDerB64` 默认为空，外装「官方」算法包应被拒绝；内置算法仍可用。
- **算法发布无 tag**：包与目录都在 `release-index`（`algorithms/packages/` + `algorithms/{channel}.json`）；客户端不读 `releases/download`。用户说「上传到 GitHub 就能检测」= 更新该分支目录/包，不是 push main，也不是发 alg tag。
- **AI 可代发**：流程写在根 `CLAUDE.md`「算法包网络更新」；默认 dry-run，真上传需用户明确 + token/私钥本机环境，禁止私钥进聊天/仓库。
- **算法版本与通道**：包 `manifest.version` 用 semver，**首版 `0.1.0`**（补丁 +0.0.1 / 次要 +0.1.0 / 主要 +1.0.0）；**门禁全过才 bump**；`beta` vs `stable` 用户自选，未验证不上 stable。
- **默认赛季单一真相**：只改 `AppConfig.DEFAULT_SELECTED_SCENE`；文档禁止写死赛季中文名/枚举。
- **提交隔离**：UI/动效、算法网络、本机构建、IDE 脚本分提交；合 main 前可用 stash 隔开无关 WIP。
- **日常开发分支**：默认在 `main` 直接迭代（用户偏好）；除非明确要求再开 feature 分支。
- **文档同步**：硬约束/对外能力变更时同一任务更新 `CLAUDE.md` 与 `README.md`；**禁止改动 Star History**；也不得无故删除徽章、免责、版本表、构建/签名、MCP 边界、仓库链与许可证等关键信息。
- **本机测试**：全量 unit test 可能 OOM；优先相关单测 + compile，再视情况 assemble。
- **构建慢/IC 损坏**：本机 low-memory 且 VS Code+Claude+Serena+Kotlin LS 常驻时可用内存常 <1GiB；症状为 `shrunk-classpath-snapshot.bin` FileNotFound、IC 回退全量、daemon stop command。先 `gradlew --stop` + `tools/dev/repair_gradle_kotlin_cache.ps1`；Hilt 已迁 KSP（勿回 legacy-kapt）；日常 `hzzs.native.abis=arm64-v8a` + `CMAKE_BUILD_PARALLEL_LEVEL=2`。
- **Motion**：`animationScale`/`reduceMotion`/系统 animator 经 `HzzsMotionPolicy` 统一消费；禁止用动画倍率当业务超时。
- **几何**：动作与 Tracker 只读 `Detection.bounds`；`displayContour` 仅 HUD。

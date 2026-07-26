# core — 顶层包索引

`core/` 是稳定基础设施的容器。**不依赖 Android 服务或 UI**，被 domain/data/service/mcp/feature 各层消费。每个子包各有独立 `CLAUDE.md`。

## 入口

本包为顶索引，无独立实现文件；按子包导航进入各 `core/*/CLAUDE.md`。

## 子包导航

| 子包 | 职责 | 详情 |
| --- | --- | --- |
| `core/model` | 应用级稳定配置模型（`AppConfig` 及其子树、枚举、`displayName()`） | `core/model/CLAUDE.md` |
| `core/preferences` | `SettingsRepository`：DataStore + 草稿 preview + `validated()`/`ConfigJson` | `core/preferences/CLAUDE.md` |
| `core/algorithm` | 算法目录/下载/验签/安装/激活（CC-1） | `core/algorithm/CLAUDE.md` |
| `core/theme` | `ThemePackage` 编解码（声明式 JSON，无脚本/远程资源） | `core/theme/CLAUDE.md` |
| `core/update` | `UpdateRepository`：APK/差分更新（Gitee 优先、清单签名、证书绑定） | `core/update/CLAUDE.md` |
| `core/designsystem` | 设计系统（主题/断点/动效/组件/对比度） | `core/designsystem/CLAUDE.md` |
| `core/logging` | `AppLog`（Logcat + memory ring）+ `DiagnosticsExporter`（脱敏诊断） | `core/logging/CLAUDE.md` |
| `core/platform` | `ClipboardHelper` 等轻量平台适配 | （小，可直接读） |

## 跨子包约束

- 修改 `core/model` 字段：同步 `validated()`、`ConfigJson`、设置 UI、MCP schema、单测（见 `app/CLAUDE.md`）。
- 默认值必须安全：自动操作关、MCP 关、截图 AUTO 不升权。
- 配置 schema **10**（含 `overlay.persistBoxes` 默认开、自动复活等；访问日志自 schema 9 起）。

## 不变量

## 改这个包前必读

- 触及 `model`/`preferences`/`algorithm` 几乎必然触及「硬约束 / 工作流 / 安全门控 / 默认行为 / 对外能力」→ 同步 `CLAUDE.md` / `README.md` / `AGENTS.md` / `docs/*`。
- 改版本号：同步 `app/build.gradle.kts` 默认值与 CHANGELOG（若用户可见）。
- 改默认赛季：只改 `AppConfig.DEFAULT_SELECTED_SCENE`，并同步迁移/单测；**不要**在 README/CLAUDE/AGENTS/PROGRESS 写死赛季中文名。

## 测试

- 门禁：`python tools/quality/check_resources.py`、`python tools/quality/check_project.py`、`:app:testDebugUnitTest`。
- 相关：`SettingsSessionTest`、`ThemePackageTest`、`VisionResultValidatorTest`。

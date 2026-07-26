# core/platform — 轻量平台适配

`core/platform` 目前是轻量平台适配的容器。**不依赖 Android 服务或 UI**。

## 子包/文件

| 文件 | 职责 |
| --- | --- |
| `ClipboardHelper` | 剪贴板写入（空服务保护 + 结果回调） |

## 入口

1. `ClipboardHelper.kt` — 全部（单文件）。
2. `feature/settings/screens/McpSettingsScreen`（复制 URL / Token）、`feature/about/AboutScreen`（复制链接）。

## 不变量

- 空服务保护：剪贴板服务不可用时返回 false，避免「点了没反应」。
- 结果回调：返回 boolean 表示是否已提交系统剪贴板服务。

## 改这个包前必读

- 当前仅一个文件；若后续扩展，优先放入既有职责包，不新增根级业务 Gradle 模块。

## 测试

- 门禁：`python tools/quality/check_resources.py`、`python tools/quality/check_project.py`。
- 真机验证：剪贴板写入、空服务降级。

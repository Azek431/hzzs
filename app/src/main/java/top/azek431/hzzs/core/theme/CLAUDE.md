# core/theme — 声明式主题包

`.hzzstheme` 声明式主题包模型与编解码。**仅 JSON 字段，无脚本 / 字体 / 图标 / 远程 URL**；体积上限 64KB；数值在编解码时全部 clamp。

## 职责

- `HzzsThemePackage`：主题包数据（name/formatVersion/author/description/theme/overlay）。
- `ThemePackageCodec`：`encode`（截断过长字符串）/ `decode`（校验 format/版本/体积，未知枚举回退默认，非法浮点回退安全值）。

## 入口

1. `ThemePackage.kt` — 全部（单文件）。
2. `feature/settings/SettingsViewModel`（importTheme/exportTheme）、`feature/settings/screens/AppearanceSettingsScreen`（外观设置页）、`core/model/ThemeConfig`+`OverlayConfig`（被编解码的子树）。

## 不变量 / 安全

- 包内容只影响外观与悬浮窗展示，**不触及截图后端、自动操作或 MCP**。
- 安全边界：仅 JSON；无脚本/远程资源；体积上限；数值 clamp；未知枚举回退默认。

## 改这个包前必读

- 改 `HzzsThemePackage` 字段：同步 `ThemePackageCodec.encode/decode`、`ThemeConfig`/`OverlayConfig`、`feature/settings` 导入导出。
- 改安全边界：同步 `docs/SECURITY.md`（主题包声明式 JSON，无脚本/远程资源）。

## 测试

- 相关测试：`ThemePackageTest`（编解码/体积/数值 clamp/枚举回退）。
- 门禁：`python tools/quality/check_resources.py`、`python tools/quality/check_project.py`、`:app:testDebugUnitTest`。

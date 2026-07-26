# feature/onboarding — 首次引导

5 步首次引导向导：欢迎 → 赛季 → 截图后端 → 权限 → 外观+完成。**完成前不落盘**；点「完成」时写配置并标记 onboarding/免责声明版本。

## 职责

- 编辑内存草稿 `AppConfig`；主题/悬浮窗经 `onPreview` 即时预览（写仓库 preview，不落盘）。
- 自动操作默认强制关闭；仅完成页高级区可开，且须风险对话框倒计时确认。

## 入口

1. `OnboardingScreen.kt` — 全部（单文件）。
2. `MainActivity.HzzsRoot`（分流：未完成的引导 vs 主导航）、`platform/compat/CaptureCapabilities`/`SystemCapabilityAccess`（能力/权限展示）。

## 数据流

```text
5 步页面 → update { validated().also(onPreview) } → 仓库 preview（不落盘）
完成页「完成」→ onComplete(config.copy(onboarding.completed=true, disclaimerAcceptedVersion=DISCLAIMER_VERSION))
  → MainActivity.completeOnboarding → repository.save
```

## 不变量 / 安全

- 草稿只从首帧 `initial` 播种一次；禁止 `remember(initial)`（根界面 config 变化会重建草稿 + 强制 enabled=false → 「确认后开关又关」）。
- 自动操作风险对话框：4 秒倒计时 + 确认勾选；`disclaimerAcceptedVersion = DISCLAIMER_VERSION`。
- 引导期截图后端仅展示 AUTO / MEDIA_PROJECTION / ACCESSIBILITY；不推荐高级后端。
- 不直接申请 Root/Shizuku/JNI；权限页仅展示悬浮窗/无障碍状态与跳转设置。
- 动效：步骤切换走 `LocalHzzsMotion` shared-axis；减少动效即时切换。

## 改这个包前必读

- 改引导步骤：同步 `onboardingPageMetas()` 与页面分支。
- 改草稿：同步 `validated()`、`SettingsRepository.preview/save`、`MainActivity.completeOnboarding`。
- 改风险对话框：同步 `AppConfig.DISCLAIMER_VERSION`、`AutomationConfig.disclaimerAcceptedVersion` 语义。
- 改截图后端列表：同步 `platform/compat/CaptureCapabilities`。

## 测试

- 门禁：`python tools/quality/check_resources.py`、`python tools/quality/check_project.py`。
- 真机验证：5 步流程、草稿预览、风险对话框、完成写盘。

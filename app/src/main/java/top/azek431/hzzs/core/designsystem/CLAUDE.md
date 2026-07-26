# core/designsystem — 设计系统（主题/断点/动效/组件/对比度）

HZZS Design System 2.0：工具专业风主题与令牌。**冷静中性表面 + 品牌 accent（种子色）**，适合本地分析工具。

## 职责

- `HzzsTheme`：提供 `LocalHzzsDimensions` / `LocalHzzsStatusColors` / `LocalHzzsMotion`；保留 Dynamic / 多预设 / 自定义种子（本轮不收紧主题能力）。
- `HzzsBreakpoints`：窗口断点（一级导航 720dp、设置双栏 840dp、紧凑 320dp）。
- `HzzsColorContrast`：WCAG 对比度与 ARGB 规范化，供外观/HUD 共用。
- `HzzsMotion` / `HzzsNavTransitions`：Motion Policy（综合 `animationScale`/`reduceMotion`/系统 `animator_duration_scale`；一级导航 fade-through，设置分类/引导步骤短 shared-axis X）。
- `Components.kt`：跨页复用组件（HeroCard / SectionCard / HzzsCallout / MetricTile / StatusChip / PageHeader / 主次动作按钮 / 滚动页等）。

## 入口

1. `HzzsTheme.kt` — 主题令牌 + 断点 + 设计维度。
2. `HzzsColorContrast.kt` — 颜色对比度。
3. `HzzsMotion.kt` — 动效策略。
4. `HzzsNavTransitions.kt` — 转场。
5. `Components.kt` — 复用组件。
6. 所有 `feature/*` 页（主消费者）、`core/model/ThemeConfig`+`OverlayConfig`（被主题消费的配置）。

## 不变量

- 主题包只存语义控制（mode / preset / 缩放），不存组件级裸色。
- 窗口断点集中在 `HzzsBreakpoints`，布局读当前窗口宽度（非物理设备）。
- **Motion Policy**：综合 `ThemeConfig.animationScale`、`reduceMotion` 与系统 `animator_duration_scale`；减少动效或系统倍率为 0 时转场为 None。
- **颜色**：动画时长不得用于业务超时、帧龄或手势 TTL。

## 改这个包前必读

- 改 `HzzsTheme`：同步 `core/model/ThemeConfig`、`feature/*` 所有页面（主消费者）、外观设置页。
- 改 `HzzsBreakpoints`：同步 `MainActivity`（一级导航 / 设置双栏）、`feature/settings/SettingsScreen`。
- 改 `HzzsColorContrast`：同步 `service/overlay/OverlayController`（HUD 共用）。
- 改 `HzzsMotion`：同步 `MainActivity`、`feature/settings/SettingsScreen`、`feature/onboarding/OnboardingScreen`。
- 改 `Components.kt`：影响所有 `feature/*` 页；优先既有组件，不平行造轮子。

## 测试

- 门禁：`python tools/quality/check_resources.py`、`python tools/quality/check_project.py`。
- 相关：`ThemePackageTest`（主题包编解码）。
- 真机验证：亮/暗/AMOLED/Dynamic 主题、断点切换、减少动效。

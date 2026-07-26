# platform/compat — 平台能力探测与系统跳转

版本与能力探测；系统悬浮窗 / 无障碍 / 修改系统设置与指针位置（`SystemCapabilityAccess`）。**只做能力探测，不直接业务逻辑**。

## 职责

- `CaptureCapabilities` / `CaptureCapabilityResolver`：截图后端能力探测（支持度/推荐）。
- `GestureCapabilities` / `GestureCapabilityResolver`：手势后端能力探测。
- `resolveEffectiveCaptureBackend` / `resolveEffectiveGestureBackend`：开发者强制优先 + 本机不支持时 fail-soft 回退。
- `SystemCapabilityAccess`：悬浮窗/无障碍跳转设置、指针位置（`WRITE_SETTINGS` 优先，已授权 Shizuku/Root 可 `settings put`）。

## 入口

1. `CaptureCapabilities.kt`、`GestureCapabilities.kt`、`SystemCapabilityAccess.kt`。
2. `data/vision/VisionRuntimeController`（`resolveCaptureBackend`/`resolveGestureBackend`）、`feature/onboarding`（能力展示）、`feature/settings/screens/CaptureSettingsScreen`/`AutomationSettingsScreen`（能力展示）、`service/automation/ShellProcessSupport`（系统设置）。

## 数据流

```text
开发者 forceCaptureBackend 优先 → 本机不支持则 fail-soft 回退到可用后端
AUTO → MediaProjection（截图）；AUTO → 无障碍 / 条件 Shizuku（手势；永不 Root）
SystemCapabilityAccess.openOverlayPermissionSettings / openAccessibilitySettings / openSystemSettings(target)
```

## 不变量 / 安全

- `CaptureBackend.AUTO` **只**走 MediaProjection，不探测 Root/Shizuku/无障碍。
- `GestureBackend.AUTO` 优先无障碍；仅无障碍未连接且 Shizuku **已授权就绪** 时用 Shizuku；**永不**静默升 Root / 不在 AUTO 路径弹 Shizuku 授权。
- 本机 API 不支持请求后端时 **fail-soft** 回退到可用的用户主配置或 MediaProjection，写入诊断 `capture.requested/effective/fallbackReason`。
- 系统「指针位置」经 `SystemCapabilityAccess`：可点授权 Shizuku（主线程）→ **绝对路径** `/system/bin/settings` 首成功即停并缓存前缀（与 input 同源）→ `WRITE_SETTINGS` / Root；写后延迟回读 system/secure；不写入 AppConfig；诊断含指针/Shizuku/`shell.prefix`。
- 手势与截图后端正交。

## 改这个包前必读

- 改能力分支：按 API 版本分支（24/26/29/30/33/34+），见 `app/CLAUDE.md`「修改截图」。
- 改 `resolveEffectiveCaptureBackend` / `resolveEffectiveGestureBackend`：同步 `data/vision/VisionRuntimeController` 与诊断字段。
- 改 `SystemCapabilityAccess`：同步 `service/automation/HzzsAccessibilityService`（无障碍）、`ShellProcessSupport`（shell 前缀缓存）、开发者页「系统指针位置」。
- 改 `CaptureCapabilityResolver` / `GestureCapabilityResolver`：同步 `feature/onboarding` 与设置页能力展示。

## 测试

- 门禁：`python tools/quality/check_resources.py`、`python tools/quality/check_project.py`。
- 真机验证：API 24/26/29/30/33/34+ 分支、授权失效、旋转、空帧、超时和资源释放；AUTO 不升权。

# Android 7+ 权限与采集模式

| 系统 | AUTO 首选 | 需要用户处理 |
|---|---|---|
| Android 7–10 / API 24–29 | MediaProjection | 每次新的采集会话由系统确认 |
| Android 11+ / API 30+ | 已连接的 HZZS 无障碍截图 | 未连接时回退 MediaProjection |

- MediaProjection 只能使用用户明确授权的当前会话。
- Root 仅在用户手动选择并确认时探测，永不进入 AUTO 回退链。
- 自动操作同时要求：应用内总开关开启、HZZS 无障碍已连接、前台包名属于默认白名单。
- 默认自动操作白名单：`com.smile.gifmaker`、`com.kuaishou.nebula`。
- Android 13+ 通知权限、悬浮窗权限和无障碍权限仍由用户在系统界面授予。

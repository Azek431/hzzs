# 安全与权限模型

## 默认姿态

- 默认截图后端是 **MediaProjection**；`AUTO` **不**自动请求 Root、Shizuku 或无障碍。
- 系统悬浮窗（`SYSTEM_ALERT_WINDOW`）与无障碍服务**不会**由应用静默开启；须用户在系统设置中明确授予。应用内「显示悬浮窗」开关不能代替系统权限。
- 自动操作默认关闭；导入、备份和旧版本迁移不能静默开启。
- 自动操作需要当前免责声明版本，并默认在每次运行会话重新解锁（arm）。
- MCP 默认关闭；启用后只监听 loopback，使用随机 Bearer Token；默认每次写操作由手机确认。
- 主题包是有大小限制的声明式 JSON，不加载脚本、字体或远程资源。
- 更新产物必须校验包名、版本、SHA-256、证书与签名清单。
- 截图帧、日志、MCP 令牌和 DataStore 配置不进入系统云备份。
- Root 命令分别读取 stdout/stderr，并限制输出、超时和图片尺寸。

## 自动操作门控

必须同时满足（实现以 `VisionRuntimeController` / `GestureArbiter` 为准）：

1. `automation.enabled == true`
2. `disclaimerAcceptedVersion` 达到当前 `AppConfig.DISCLAIMER_VERSION`
3. 视觉分析正在运行
4. 无障碍服务已连接，前台窗口快照未过期
5. 前台包名 ∈ 允许列表（与默认白名单求交）
6. 会话已 arm（若要求会话解锁）
7. 场景置信度、障碍置信度、稳定帧、动作速率与手势回执校验通过

失败路径应 fail-closed：disarm、清空队列、不注入手势。

## MCP

| 层 | 要求 |
|---|---|
| 网络 | 仅 loopback |
| 认证 | 每服务生命周期随机 Bearer；比较使用恒时算法 |
| Origin | 非空时必须是本机回环标识 |
| 权限 | 只读 / 每次确认 / 会话信任 / 完整访问 |
| 完整访问 | 仅应用内权限，**不能**绕过系统录屏 / 悬浮窗 / 无障碍 / 安装界面 |
| 调试帧 | 需开发者选项与 MCP 显式允许；只暴露元数据或受控文件 |

## 诊断与日志

- `AppLog` 为进程内 Logcat + 内存 ring buffer；不写外部存储、不上传。
- 诊断导出（设置 / 关于）含版本、机型、配置摘要与最近日志；**不含** Bearer、签名密钥与调试帧像素。
- 日志路径对 `Bearer …` 与常见 `token/secret/password` 键值做脱敏；MCP 连接串仅经用户显式「复制连接信息」进剪贴板，不得写入日志。
- 关闭开发者选项后，DEBUG/VERBOSE 不再进入 ring buffer。

## 截图与帧

- 像素缓冲有明确租约与 `close()` 生命周期。
- 分辨率变化使帧池 generation 失效，旧 lease 不得回池。
- 最大边与像素总数有上限（与 Native 一致：边 4096，像素 8_388_608）。
- Shizuku 后端仅在用户显式选择时启用；通过反射调用 Shizuku 进程 API 执行 `screencap -p`，失败 fail-closed。AUTO 路径永不探测 Shizuku。

## 配置与主题

- JSON / DataStore 有字节上限与字段校验。
- 自动化 `allowedPackages` 与内置默认集合求交。
- 主题包拒绝未知可执行字段与超大 payload。

## 更新

- 仅 HTTPS。
- 清单签名绑定本应用安装证书公钥；Debug 包不能伪装成生产更新源。
- 差分补丁回放校验后才可安装。

## 官方算法包

- `.hzzsalg` 使用**独立 Ed25519 密钥**，不得复用 APK keystore。
- 包内仅声明式 JSON / 文本；拒绝可执行扩展名、符号链接、路径穿越与 Zip 炸弹。
- 目录 `algorithms/{channel}.json` 最后发布；资产哈希不一致时拒绝覆盖。
- Secrets：`ALGORITHM_SIGNING_PRIVATE_KEY_B64`、`ALGORITHM_SIGNING_KEY_ID`（与 `ANDROID_KEYSTORE_*` 分离）。
- 运行时只接受校验后的 `AlgorithmRuntimeProfile`；不得动态加载代码；失败回退内置；不得改写自动化门禁。
- 详见 [`docs/ALGORITHM_SYSTEM_V1.md`](ALGORITHM_SYSTEM_V1.md)。

## 贡献者注意

禁止提交 keystore、密码、Token、未脱敏截图与用户隐私数据。详见 [`CONTRIBUTING.md`](../CONTRIBUTING.md)。

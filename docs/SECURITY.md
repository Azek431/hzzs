# 安全与权限模型

## 默认姿态

- 默认截图后端是 **MediaProjection**；截图 `AUTO` **不**自动请求 Root、Shizuku 或无障碍。
- 手势注入后端 `GestureBackend` 与截图后端**正交**；手势 `AUTO` 优先无障碍，仅当无障碍未连接且 Shizuku **已授权就绪** 时用 Shizuku input，**永不**静默升 Root、不在 AUTO 路径弹 Shizuku 授权。
- 系统悬浮窗（`SYSTEM_ALERT_WINDOW`）与无障碍服务**不会**由应用静默开启；须用户在系统设置中明确授予。应用内「显示悬浮窗」开关不能代替系统权限。
- 自动操作默认关闭；导入、备份和旧版本迁移不能静默开启。
- 自动操作需要当前免责声明版本；启用后在分析运行中按识别结果直接规划手势。
- MCP 默认关闭；启用后只监听 loopback；默认 **免 Bearer**（`requireAuth=false`，同机免填 Header）；开启鉴权时使用**持久化** `authToken`，仅用户主动「轮换 Token」时更换，**不**在每次服务启动轮换；默认每次写操作由手机确认。
- MCP 单工具策略 `toolPolicies` 可强制确认 / 信任放行 / 禁用；外部摄入只能更严，不得把已禁用工具重新打开。
- 主题包是有大小限制的声明式 JSON，不加载脚本、字体或远程资源。
- 更新产物必须校验包名、版本、SHA-256、证书与签名清单。
- 截图帧、日志、MCP 令牌和 DataStore 配置不进入系统云备份。
- Root / Shizuku 命令分别读取 stdout/stderr，并限制输出、超时和图片尺寸。

## 自动操作门控

必须同时满足（实现以 `VisionRuntimeController` / `GestureArbiter` 为准）：

1. `automation.enabled == true`
2. `disclaimerAcceptedVersion` 达到当前 `AppConfig.DISCLAIMER_VERSION`
3. 视觉分析正在运行
4. 有效手势后端可用并完成前台门控：
   - `ACCESSIBILITY`（及 AUTO 落在无障碍时）：服务已连接，前台窗口可解析（事件 + 主动刷新）
   - `SHIZUKU` / `ROOT`：shell `input` 注入；前台包由 dumpsys 探测（失败 fail-closed）；`input` 完成语义为命令 exit0，**弱于** `dispatchGesture` 回执
5. **可选**包名门控：仅当用户开启 `restrictPackages` 时，前台包须 ∈ `allowedPackages`（默认**不**限制；外部摄入不得静默关闭限制或扩大列表）
6. 无障碍路径：前台窗口类名可用（空类名 fail-closed）；shell 路径 class 可空，仅包名+时效
7. 场景置信度、障碍置信度、稳定帧、动作速率与手势回执校验通过
8. 切换 `gestureBackend` / 其它安全边界 → `cancelActions()`；外部摄入 `saferGestureBackend` 禁止升风险序（AUTO &lt; 无障碍 &lt; Shizuku &lt; Root）

失败路径应 fail-closed：`cancelActions()` 取消在飞动作、清空队列、不注入手势。  
运行页展示 `lastAutomationDecision`；决策日志 `algo.decision` 的 skip/dispatch 以 INFO 输出。

## MCP

| 层 | 要求 |
|---|---|
| 网络 | 默认 IPv4 loopback `127.0.0.1`；用户显式关闭 `bindLocalhostOnly` 后可绑 `0.0.0.0`（局域网）。导入不得静默开局域网。展示地址与绑定分离 |
| 认证 | 默认免鉴权；开启 `requireAuth` 时用持久化 `authToken`（恒时比较，Bearer 前缀大小写不敏感）；**不**在每次启动轮换，仅设置页主动轮换 |
| Origin | 空 / 字面量 `null` 允许；非空时必须是本机回环标识 |
| 会话 | `Mcp-Session-Id` 仅内存；`initialize` 后即就绪；服务 stop/generation 推进后全部作废 |
| 权限 | 只读 / 每次确认 / 会话信任 / 完整访问 |
| 工具策略 | `mcp.toolPolicies`：`DEFAULT` / `ALWAYS_ASK` / `ALLOW_WHEN_TRUSTED` / `DISABLED`；禁用项不进 `tools/list`（策略读 `current()`，可含设置预览） |
| 服务绑定 | 前台服务启停/端口/鉴权/LAN **仅**跟已保存配置（`savedConfig`）；草稿不重启 socket |
| 会话信任 | 绑定当前内存会话；**不得**把 TRUSTED_SESSION 当跨重启持久特权 |
| 失效会话 | 服务重启后旧 `Mcp-Session-Id` 对 `tools/call` 降级无会话继续；TRUSTED 仍须有效会话 |
| 完整访问 | 仅应用内权限，**不能**绕过系统录屏 / 悬浮窗 / 无障碍 / 安装界面 |
| 并发 | 连接数上限；超额 429 |
| 审批 | 超时/停止服务时默认拒绝，避免断连后仍执行写副作用 |
| 调试帧 | 需开发者选项与 MCP 显式允许；只暴露元数据或受控文件 |
| 日志/诊断 | `get_logs` / `export_diagnostics` 需开发者；内容脱敏，不含 Bearer/像素 |
| 设置导出 | MCP `get_settings` / `app://settings/current` 脱敏 `authToken`（`***`）；用户备份导出仍可含 Token |
| 高风险写 | 开启自动操作、开启开发者、下载算法、改 MCP 权限/鉴权/工具策略：TRUSTED_SESSION 拒绝，需每次确认或 FULL_ACCESS |
| 局部补丁 | `patch_settings` 白名单路径，不得改 `automation.enabled` / MCP 鉴权令牌 / 自提权限级 |
| 外部摄入 | 不得静默关闭 `requireAuth`、改写/清空 `authToken`、自提权限级、开启 MCP 或放宽 `toolPolicies` |

## 诊断与日志

- `AppLog` 为进程内 Logcat + 内存 ring buffer；不写外部存储、不上传。
- 应用内 `LogViewerScreen` 仅展示 ring buffer，支持筛选/复制/分享；**不含**系统 logcat 全量采集。
- `AlgorithmPipelineScreen` 仅展示进程内激活阶段与最近一帧摘要，不含检测框像素与密钥。
- 诊断导出（设置 / 关于）含版本、机型、配置摘要与最近日志；**不含** Bearer、签名密钥与调试帧像素。
- 日志路径对 `Bearer …` 与常见 `token/secret/password` 键值做脱敏；MCP 连接串仅经用户显式「复制连接信息」进剪贴板，不得写入日志。
- 关闭开发者选项后，DEBUG/VERBOSE 不再进入 ring buffer。
- 系统「指针位置」开关写入 `Settings.System` 键 `pointer_location`：**已授权 Shizuku 优先**（`ShellProcessSupport` 同源 `settings put`）；否则「修改系统设置」；再 Root。**以回读校验为准**。应用**不得**静默获得 `WRITE_SETTINGS`、**不得**在此路径弹 Shizuku 授权或静默升 Root，也不得把该状态写入导入配置。

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
- **信任锚**：`AlgorithmTrustAnchors.officialPublicKeyDerB64` 当前含 `hzzs-algorithm-official-1`；列表为空时远端下载/安装 fail-closed。
- **APK 捆绑包**：`assets/algorithms/<id>/` 经 `BundledAlgorithmInstaller` 幂等预装，视为应用本体声明式参数，**不经**外装 Ed25519；不覆盖用户已安装同 id 包。
- 分析运行中切换算法只记 pending，由 `AlgorithmCatalogController.setAnalysisRunning` 与激活协调器在安全点切换。
- 详见 [`docs/ALGORITHM_SYSTEM_V1.md`](ALGORITHM_SYSTEM_V1.md)。

## 贡献者注意

禁止提交 keystore、密码、Token、未脱敏截图与用户隐私数据。详见 [`CONTRIBUTING.md`](../CONTRIBUTING.md)。

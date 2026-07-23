# 贡献说明

提交代码或文档前，请确保：

- 不包含密码、私钥、签名文件、`local.properties` 或个人配置。
- 不复制来源不明或许可证不兼容的代码与资源。
- **已实现与规划中必须严格分离**，不得把未完成能力写成可用。
- 提交说明准确描述改动目的。
- 新增能力保留清晰边界，避免把页面、分析、存储和系统服务逻辑堆进单一文件。

## 开发前必读

1. 根目录 [`README.md`](README.md) 与 [`CLAUDE.md`](CLAUDE.md)
2. [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)、[`docs/SECURITY.md`](docs/SECURITY.md)
3. 目标目录的 `README.md` / `CLAUDE.md`（如 `app/`、`app/src/main/cpp/`）
4. 当前进度 [`docs/PROGRESS.md`](docs/PROGRESS.md)

AI 代理请同时阅读 [`AGENTS.md`](AGENTS.md)。**以源码与上述文档为准**，忽略任何仍描述多模块 Views / 模拟 HUD / “无测试代码” 的过期摘录。

## 结构约束

- 所有 Android / Kotlin / Compose / JNI / C++ **产品源码位于 `app/`**。
- 根目录只保留 Gradle、CI、发布工具、质量脚本和文档。
- **不得**重新创建根目录业务 Gradle 模块（如旧的 `core`、`features/*`）。
- Android 版本判断集中在 `platform/compat` 或平台实现层。
- `CaptureBackend.AUTO` 只能使用低权限公开接口，不得探测或调用 Root / Shizuku。

## 安全红线

- 自动操作默认关闭；配置导入与迁移不得静默开启。
- 自动操作需要当前免责声明版本；启用后在分析运行中按识别结果直接规划手势。
- MCP 默认“每次确认”，只监听 loopback，使用随机 Bearer Token；不得在日志中打印 Token。
- MCP 完整访问仍不能绕过 Android 系统权限对话框。
- Root、Shizuku 与无障碍能力只能由用户明确选择。
- 配置、主题包、更新清单和截图尺寸都必须有边界校验。
- Release 签名从环境变量读取，不提交 keystore。

## 已实现 vs 规划

在 README、CHANGELOG、Issue 与 PR 中必须区分：

| 状态 | 含义 |
|---|---|
| **已实现** | 仓库中有对应源码，可编译，可被实际调用 |
| **规划中 / 未完成** | 仅有设计、入口占位或文档描述 |
| **待真机验证** | 代码存在，但未完成目标设备矩阵验证 |

当前**不要**把下列内容写成已完成（除非 `docs/PROGRESS.md` 已勾销）：

- Shizuku 生产级截图适配器（若仍为占位）
- 99% 识别准确率或全机型覆盖
- 正式商店分发或已发布的更新索引
- 战报存储、视频回放分析、糖果/分数识别等未立项能力

下列能力在统一重构后**应视为已实现**（细节与边界见架构/进度文档）：

- MediaProjection 截图默认路径
- 双赛季 C++ 视觉识别与悬浮窗展示
- 受控自动操作（默认关；启用后运行中按门控规划，无会话 arm）
- 本地 MCP 服务框架（loopback；可选 Bearer）
- 设置即时落盘 / 主题包 / 开发者入口

## 修改流程

1. 阅读目标目录说明与相关不变量。
2. 修改代码后同步职责、数据流或不变量文档。
3. 运行：

```bash
python tools/quality/check_resources.py
python tools/quality/check_project.py
python -m compileall -q tools
./gradlew --no-daemon testDebugUnitTest lintDebug assembleDebug
```

4. 涉及 Native 时额外运行 sanitizer / 宿主机测试（见 `docs/TESTING.md`）。
5. 更新 `CHANGELOG.md` 的 `[Unreleased]` 条目。
6. 不提交密钥、签名文件、本地备份或生成的二进制。

## 注释与语言

- 项目沟通、用户可见文案、新增文档默认使用**中文**。
- Kotlin 公开 API 可用简洁 KDoc；说明职责、线程与所有权，不复述语法。
- C++ 使用简要 Doxygen 风格注释。

## 隐私

禁止在代码、文档或 Issue 中包含：

- 签名密钥、密码或证书文件
- 用户个人身份信息
- 未经脱敏的设备日志或屏幕截图
- 任何第三方平台账号凭证

## 许可证

贡献即表示同意以本仓库 [`LICENSE`](LICENSE) 条款分发你的修改。

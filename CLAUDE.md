# CLAUDE.md

## 项目目标

HZZS 是“火崽崽奇妙屋”的本地 Android 画面分析工具。主要能力是截图、C++ 障碍识别、可选悬浮窗、配置预览和受控自动操作。最低系统为 Android 7.0（API 24）。

首发版本目标：**0.1.0** / `versionCode = 1`。默认赛季：**竹影书屋**。

## 结构约束

- 所有 Android、Kotlin、Compose、JNI 与 C++ 产品源码必须位于 `app/`。
- 根目录只保留 Gradle、CI、发布工具、质量脚本和文档。
- 不得重新创建根目录业务 Gradle 模块。
- Android 版本判断集中放在 `platform/compat` 或平台实现层。
- `CaptureBackend.AUTO` 只能使用低权限公开接口，不得探测或调用 Root/Shizuku。

## 安全不变量

- 自动操作默认关闭，配置导入和迁移不得静默开启。
- 自动操作需要当前免责声明版本，并默认要求每次会话重新解锁。
- MCP 默认“每次确认”，只监听 loopback，并使用随机 Bearer Token。
- MCP 完整访问仍不能绕过 Android 系统权限对话框。
- Root、Shizuku 与无障碍能力只能由用户明确选择。
- 配置、主题包、更新清单和截图尺寸都必须有边界校验。

## 修改流程

1. 阅读目标目录的 `README.md` 与 `CLAUDE.md`，以及 `docs/PROGRESS.md`。
2. 修改代码后同步职责、数据流或不变量文档，避免只更新表面说明。
3. 运行 `python tools/quality/check_resources.py` 和 `python tools/quality/check_project.py`。
4. 运行 Native 测试和 Android Gradle 门禁。
5. 更新 `CHANGELOG.md` 的 `[Unreleased]`（若用户可见行为变化）。
6. 不提交密钥、签名文件、`local.properties`、本地备份或生成的二进制。

## 坐标与线程

- 视觉结果使用视口归一化坐标 `[0, 1]`，只有平台绘制和手势分发层转换为像素。
- 截图帧拥有明确的 `close()` 生命周期，不得跨帧保存底层缓冲引用。
- WindowManager、View 与 Accessibility 回调必须在主线程协调。
- C++ 输入缓冲只在 JNI 调用期间借用，Native 不得持有 Java 数组地址。

## 文档真相源

- 产品与架构：`README.md`、`docs/ARCHITECTURE.md`、`docs/SECURITY.md`
- 进度：`docs/PROGRESS.md`
- 代理导航：`AGENTS.md`（须与源码同步，不得再描述旧多模块 Views 骨架）

## 语言

与用户沟通、新增用户可见文案与文档默认使用中文。

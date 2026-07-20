# HZZS 火崽崽数据分析

HZZS 是面向 **火崽崽奇妙屋** 的第三方 Android 本地画面分析工具。它使用截图后端采集屏幕帧，通过 C++17 视觉引擎识别不同赛季中的障碍，并以 Material 3 页面和可配置悬浮窗展示结构化结果。

项目的首要目标是：低权限默认、Android 7+ 兼容、比例坐标适配、设置可回滚、算法结果可测试，以及让开发者和 AI 能快速理解并安全修改代码。

> **免责声明**
>
> HZZS 与游戏、平台及其运营方没有隶属或授权关系。本项目用于技术研究与数据分析，不承诺识别结果绝对准确。自动操作属于高风险可选功能，可能因画面变化、网络延迟、系统卡顿或识别误差产生错误操作。使用者应遵守游戏规则、平台条款和当地法律，并自行承担使用风险。

## 主要能力

- Android 7.0（API 24）及以上。
- 单一 `app` Gradle 模块，业务代码按职责分包。
- 甜甜圈、竹影书屋两个赛季配置，共用比例坐标体系。
- 默认识别全部障碍，可按赛季关闭具体障碍类别。
- 固定比例、启动检测一次、持续检测三种玩家基准策略。
- MediaProjection 默认截图；无障碍、Shizuku、Root 仅由用户主动选择。
- 极简、紧凑、调试 HUD 三种悬浮窗。
- Material 3 内置主题、动态取色、AMOLED、高对比、自定义颜色。
- `.hzzstheme` 声明式主题导入、导出和复制，不执行脚本或下载远程资源。
- 设置修改立即临时预览；取消或离开恢复；点击保存后永久生效。
- 首次启动引导、简体中文免责声明、自动操作风险等待确认。
- 关于页连续点击版本号 7 次解锁开发者设置。
- MCP 本地服务提供只读、每次确认、会话信任、完整访问四级权限。
- C++ 输入边界、JNI 失败隔离、宿主机 Sanitizer、数据集回归与发布门禁。

## 权限与截图后端

| 后端 | 适用范围 | 默认 | 说明 |
|---|---|---:|---|
| 自动 | API 24+ | 是 | 只选择低权限 MediaProjection，不探测 Root 或 Shell |
| 屏幕录制 | API 24+ | 推荐 | 用户通过系统界面授权，适合连续分析 |
| 无障碍截图 | API 30+ | 否 | 仅在用户已开启无障碍服务后使用，存在系统调用频率限制 |
| Shizuku | 高级 | 否 | 当前版本保留能力入口，但内置截图适配器尚未完成，因此 UI 会明确标记不可用 |
| Root | Root 设备 | 否 | 每帧执行受超时和大小限制的 `screencap`，兼容性和风险最高 |

应用不会自动调用 `su`、自动启动 Shizuku、自动开启无障碍，也不能绕过 Android 系统授权界面。

## MCP

MCP 服务默认关闭，只绑定设备回环地址，并在每次启动时生成随机 Bearer Token。电脑端可通过 ADB 端口转发连接。应用内页面、状态、设置、分析和悬浮窗操作通过语义工具暴露，不依赖屏幕坐标点击。

即使选择“完整访问”，MCP 也只能执行应用本身有权执行的操作，无法替代系统录屏授权、悬浮窗授权、无障碍开关或安装确认。

## 代码结构

```text
app/
├─ src/main/java/top/azek431/hzzs/
│  ├─ core/        配置、主题、设计系统、更新模型
│  ├─ domain/      视觉与自动操作领域规则
│  ├─ data/        Native 适配、追踪和运行时控制器
│  ├─ feature/     引导、首页、运行、设置、关于
│  ├─ platform/    Android 版本与能力探测
│  ├─ service/     截图、无障碍和悬浮窗
│  ├─ mcp/         本地 MCP 传输和权限仲裁
│  └─ nativevision/JNI 加载边界
├─ src/main/cpp/   C++ 视觉算法、JNI 与宿主机 ABI
└─ src/test/       JVM 与 C++ 测试

docs/              架构、安全、测试和进度
tools/             质量检查、视觉回归和发布工具
.github/workflows/  构建与发布门禁
```

每个重要目录的 `README.md` 与 `CLAUDE.md` 说明职责、数据流、线程模型、修改入口和验证命令。

## 构建

需要：

- JDK 17
- Android SDK 37
- Android NDK `28.2.13676358`
- CMake `3.22.1`
- Python 3.11+，用于质量和视觉回归工具

```bash
python3 -m compileall -q tools
python3 tools/quality/check_resources.py
python3 tools/quality/check_project.py
./gradlew --no-daemon clean testDebugUnitTest lintDebug assembleDebug
```

Debug APK：

```text
app/build/outputs/apk/debug/app-debug.apk
```

Native 宿主机验证：

```bash
bash tools/vision/run_native_sanitizers.sh
python3 tools/vision/run_host_tests.py --dataset /path/to/screenshots --max-representative 48
python3 tools/vision/evaluate_dataset.py --dataset /path/to/screenshots --output build/vision-results
```

## 发布

正式 Release 必须提供完整签名环境变量。缺少任何签名项时任务会失败，不会生成误签名发布包。发布工作流负责 APK 验签、更新清单签名、增量补丁验证、GitHub/Gitee 资产同步与匿名下载校验。

## 测试边界

宿主机数据集运行可以证明崩溃安全、输出约束和性能分布，但 **不能替代人工真值标注**。在没有独立标注前，项目不会声称达到 99% 准确率、所有机型覆盖或固定像素误差目标。

中国厂商 ROM 的后台限制、悬浮窗、Root、Shizuku 和真实游戏操作仍需要对应真机报告。详见 [`docs/TESTING.md`](docs/TESTING.md) 与 [`docs/PROGRESS.md`](docs/PROGRESS.md)。

## 许可证

见 [`LICENSE`](LICENSE)。

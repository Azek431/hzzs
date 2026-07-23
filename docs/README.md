# HZZS 文档入口

这是一张“先看哪里”的地图，不复制源码中的默认值，也不替代安全规范。遇到文档与当前代码冲突时，以当前分支源码和测试为准。

## 你是哪类读者？

| 读者 | 建议顺序 |
| --- | --- |
| 第一次接触 Android | [初学者路线](navigation/BEGINNER.md) → [按任务找代码](navigation/README.md) → `app/README.md` |
| 准备修改代码 | [按任务找代码](navigation/README.md) → [Kotlin 地图](navigation/KOTLIN.md) / [Native 地图](navigation/NATIVE.md) → [测试与 CI](navigation/TOOLS_TESTS_CI.md) |
| AI 代理 | 根 `AGENTS.md` / `CLAUDE.md` → 本页 → [按任务找代码](navigation/README.md) → 目标目录局部说明 → 源码与测试 |

## 文档各管什么？

| 文档 | 唯一职责 | 不应放入 |
| --- | --- | --- |
| 根 `README.md` | 产品能力、安装构建、权限和发布入口 | 大段符号级代码地图 |
| `architecture.md` | 当前包依赖、状态所有权和运行时数据流 | 历史进度 |
| `SECURITY.md` | 权限、安全门控和 fail-closed 边界 | 普通 UI 教程 |
| `testing.md` | 测试命令、门禁和设备矩阵 | 功能完成度 |
| `PROGRESS.md` | 当前完成、进行中和待验证状态 | 永久架构规范 |
| `ALGORITHM_SYSTEM_V1.md` | 算法包格式、信任与发布协议 | App UI 结构 |
| `navigation/` | 去哪里找入口、下一跳和测试 | 复制上述专题全文 |
| `CHANGELOG.md` | 用户可见的历史变化 | 当前架构真相 |

> Windows 文件名不区分大小写，但 GitHub/Linux 区分。本仓库当前专题文件实际是 `architecture.md` 和 `testing.md`，链接与命令应使用真实大小写。

## 源码的物理边界

- `app/`：唯一 Android 产品 Gradle 模块，包含 Kotlin、Compose、JNI、C++ 和应用测试。
- `algorithm-packs/`：声明式算法包源树，不是已签名发布物。
- `tools/`：质量、视觉、算法包、APK 发布和本地修复工具。
- `.github/workflows/`：构建与发布自动化。
- `docs/`：规范、状态、历史与本导航层。

不要从 `.backups/`、`.claude/worktrees/`、`build/`、`.gradle/`、`app/.cxx/` 或同步冲突副本推断当前实现。这些是本机状态、生成物或历史快照。第一步应核对根 `settings.gradle.kts` 和当前 `app/`。

## 代码状态词

阅读文档或源码时，区分以下状态：

- **已接线**：进入当前产品构建和真实调用链。
- **stub**：只有占位入口，当前不产生真实结果。
- **仅有源文件**：文件存在，但没有进入 CMake/Gradle/运行时。
- **实验性**：已经接线，但仍有显式开关或验证限制。
- **历史记录**：解释过去一次改动，不能自动当作当前规范。

文件存在不等于功能已上线；必须同时核对构建清单、调用点和测试。

## 开始修改前

1. 从 [按任务找代码](navigation/README.md) 找到入口和状态所有者。
2. 阅读目标目录 `README.md` / `CLAUDE.md`。
3. 沿“入口 → 状态所有者 → 平台边界 → 测试”追踪，不从 UI 文案猜实现。
4. 查看 `git status`，不要覆盖已有工作。
5. 修改后按 [工具、测试与 CI 地图](navigation/TOOLS_TESTS_CI.md) 选择门禁。

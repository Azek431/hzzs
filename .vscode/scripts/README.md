# VS Code 任务与脚本

本目录任务默认面向 **Debug 包** `top.azek431.hzzs.debug`
（`applicationIdSuffix = .debug`）。正式包 `top.azek431.hzzs` 仅用于发版安装场景。

## 常用任务

| 任务 | 作用 |
| --- | --- |
| 构建 Debug APK | 只编译 |
| 安装 Debug APK | 先检查 device，再编译并安装 |
| 启动 Debug 应用 | `am start` 精确组件 |
| 构建、安装并启动 Debug | 日常真机一条龙 |
| 真机诊断一条龙 | 清 logcat → 安装 → 启动（不含长监听） |
| 监听核心 logcat | 单独长任务，需手动停；勿放进 dependsOn |
| 检查设备与包状态 | 双包安装/进程诊断 |
| 质量门禁（Python） | `check_resources` + `check_project` |
| 调试前置 JDWP | 给 launch.json 用（install+启动+forward） |
| 仅转发 JDWP | 进程已在跑时跳过安装 |
| 移除 JDWP 转发 | 无转发时不失败 |

## 脚本

| 文件 | 职责 |
| --- | --- |
| `hzzs-common.ps1` | 包名常量、`Invoke-HzzsAdb`、设备/包/Gradle/Python 公共函数 |
| `hzzs-install-debug.ps1` | 设备闸门 + `:app:installDebug` |
| `hzzs-launch-app.ps1` | `-Flavor debug\|release` 启动 |
| `hzzs-package-action.ps1` | force-stop / clear-data / uninstall |
| `hzzs-jdwp-prepare.ps1` | 安装+启动+等 PID+JDWP 转发 |
| `hzzs-adb-forward-remove.ps1` | 安全移除 tcp 转发 |
| `hzzs-logcat-clear.ps1` / `hzzs-logcat-watch.ps1` | 清缓冲 / 长监听 |
| `hzzs-quality-gates.ps1` | 静态质量门禁 |
| `hzzs-device-status.ps1` | 设备与双包状态 |
| `hzzs-export-diagnostics.ps1` | 导出诊断目录 |

## 环境

- 全局 `CMAKE_BUILD_PARALLEL_LEVEL=2`（tasks.json `options.env`；`gradlew.bat` 未设置时也默认 2）减轻过订阅。
- `gradlew.bat` 默认强制 `configuration-cache=true`，避免用户级 gradle.properties 里的 false 拖慢每次配置。
- 需要 `adb` 与 `.\gradlew.bat` 在 PATH / 仓库根目录可用；质量门禁需要 `python`（或 `py`）。
- 真机任务要求 `adb devices` 中至少一台状态为 **`device`**（USB 调试已授权，或无线 `adb connect <ip>:5555`）。无设备 / unauthorized / offline 时会打印 `adb devices` 原文与排查提示后失败。
- 多设备时设置 `$env:ANDROID_SERIAL = '<serial>'`，否则 adb 可能选错机。
- **PowerShell `$ErrorActionPreference=Stop` + 原生 adb stderr**：会把「listener not found」等非致命 stderr 变成终止错误。所有 adb 调用应走 `Invoke-HzzsAdb`（清理转发用 `-IgnoreFailure`）。
- JDWP：清理旧端口忽略「listener not found」；启动后轮询 PID（默认 15s）；真正 forward 失败时打印 `adb forward --list`。
- 诊断导出默认 `local-diagnostics/device/<时间戳>/`（仓库已 exclude）；可用环境变量 `HZZS_DIAGNOSTICS_ROOT` 覆盖。
- 构建报 Kotlin IC / `classpath-snapshot` 损坏，或 daemon 被 stop：仓库根执行 `.\tools\dev\repair_gradle_kotlin_cache.ps1`（可加 `-Compile`）。
- 脚本使用 **UTF-8 BOM**，确保 Windows PowerShell 5.1 正确解析。
- 嵌套脚本失败靠 throw/`try`，不要在 `Set-StrictMode` 下盲读未赋值的 `$LASTEXITCODE`。

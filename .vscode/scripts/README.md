# VS Code 任务与脚本

本目录任务默认面向 **Debug 包** `top.azek431.hzzs.debug`  
（`applicationIdSuffix = .debug`）。正式包 `top.azek431.hzzs` 仅用于发版安装场景。

## 常用任务

| 任务 | 作用 |
| --- | --- |
| 构建 Debug APK | 只编译 |
| 安装 Debug APK | 编译并安装 |
| 启动 Debug 应用 | `am start` 精确组件 |
| 构建、安装并启动 Debug | 日常真机一条龙 |
| 真机诊断一条龙 | 清 logcat → 安装 → 启动（不含长监听） |
| 监听核心 logcat | 单独长任务，需手动停；勿放进 dependsOn |
| 检查设备与包状态 | 双包安装/进程诊断 |
| 质量门禁（Python） | `check_resources` + `check_project` |
| 调试前置 JDWP | 给 launch.json 用 |

## 脚本

| 文件 | 职责 |
| --- | --- |
| `hzzs-common.ps1` | 包名常量、adb/gradle 公共函数 |
| `hzzs-launch-app.ps1` | `-Flavor debug\|release` 启动 |
| `hzzs-package-action.ps1` | force-stop / clear-data / uninstall |
| `hzzs-jdwp-prepare.ps1` | 安装+启动+JDWP 转发 |
| `hzzs-quality-gates.ps1` | 静态质量门禁 |
| `hzzs-device-status.ps1` | 设备与双包状态 |
| `hzzs-export-diagnostics.ps1` | 导出诊断目录 |

## 环境

- 全局 `CMAKE_BUILD_PARALLEL_LEVEL=2`（tasks.json `options.env`；`gradlew.bat` 未设置时也默认 2）减轻 4 核过订阅。
- `gradlew.bat` 默认强制 `configuration-cache=true`，避免 `%GRADLE_USER_HOME%\gradle.properties` 里的 false 拖慢每次配置。
- 需要 `adb` 与 `.\gradlew.bat` 在 PATH / 仓库根目录可用。
- 构建报 Kotlin IC / `classpath-snapshot` 损坏，或 daemon 被 stop：仓库根执行 `.\tools\dev\repair_gradle_kotlin_cache.ps1`（可加 `-Compile`）。

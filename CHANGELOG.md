# 变更日志

所有值得关注的变更都将记录在此文件中。

格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)。

## [Unreleased]

## [0.1.0] — 2025-12

### 新增

- 初始 Android 项目结构（Kotlin + Gradle + Material Components）
- 深色首页界面（状态卡片、功能入口、开发信息与社区链接）
- 悬浮窗预览面板（显示、拖动、关闭、透明度调节）
- 悬浮窗"开始/结束执行"状态切换交互
- QQ 群与 Telegram 主频道入口（首页 + 悬浮窗）
- C++ 跑酷分析基础引擎（状态机、跳跃阶段估算、危险 ETA、动作提示）
- JNI 桥接 `NativeAnalysisBridge`（库加载自检）
- CMake 构建配置（C++17、`-Wall -Wextra -Wpedantic`）
- VS Code 真机诊断工具链（`HzzsAndroidTools.ps1`）
- README 项目说明与开发路线图

### 已知限制

- 尚未接入 MediaProjection 屏幕采集
- 尚未实现实时视觉识别与 HUD 绘制
- 尚未建立战报存储与历史数据能力

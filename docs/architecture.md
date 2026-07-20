# HZZS 架构

## 单模块、强分层

HZZS 使用单一 `app` Gradle 模块，减少跨模块追踪成本。包级依赖方向：

```text
feature → data / service → domain / core
platform 仅通过接口向运行时暴露能力
```

| 包 | 职责 |
|---|---|
| `core` | 稳定模型、DataStore、主题、设计系统、更新库 |
| `domain` | 与 Android 无关的视觉与手势规则（可 JVM 测试） |
| `data/vision` | 帧循环所有者、JNI 适配、追踪、调试帧 |
| `feature` | Compose 界面；不直接 Root / Shell / JNI / WindowManager |
| `service` | 截图后端、悬浮窗、无障碍手势 |
| `platform/compat` | 版本与能力探测 |
| `mcp` | 回环 MCP 与权限仲裁 |
| `nativevision` | JNI 加载边界（失败不崩进程） |

## 运行时

1. `FrameSourceFactory` 根据已保存后端创建截图源。
2. `VisionRuntimeController` 获取帧、校验视口与配置并调用引擎。
3. `NativeVisionEngine` 经 `NativeVision` JNI 调用 C++，再映射为领域模型。
4. `VisionResultValidator` 应用类别过滤、置信度与坐标不变量。
5. `MultiObjectTracker` 做跨帧稳定。
6. 结果进入持久 Canvas 悬浮窗；自动操作只有通过全部门控后才进入 `GestureArbiter`。

帧循环是 native 引擎与 tracker 的**唯一所有者**。配置收集器只替换不可变快照。`generation` 令牌防止已停止会话的陈旧帧写回 UI。

## 截图

| 后端 | 行为 |
|---|---|
| AUTO | **仅** MediaProjection，永不升权 |
| MEDIA_PROJECTION | VirtualDisplay + ImageReader + 帧池租约 |
| ACCESSIBILITY | API 30+ `takeScreenshot`，有频率限制 |
| SHIZUKU | 用户显式选择；经 Shizuku 执行 `screencap -p`（需安装/授权） |
| ROOT | 受限 `screencap` |

`CapturedFrame` 拥有像素租约，分析结束后必须 `close()`。不得跨帧保存底层缓冲引用。

## 配置

DataStore 存储 schema v5。`SettingsRepository` 合并持久配置和内存预览。

- 主题、悬浮窗、视觉阈值：可临时预览。
- 截图后端、MCP、自动操作、开发者、更新：预览时强制保留 baseline，**仅保存后生效**。

默认 `selectedScene = BAMBOO_BOOKSTORE`（竹影书屋）。`SceneId` 枚举序：`SWEET_FACTORY = 0`、`BAMBOO_BOOKSTORE = 1`，与 C++ `scene` 参数一致。

## 坐标与线程

- 视觉结果使用视口归一化坐标 `[0, 1]`，只有平台绘制和手势分发层转换为像素。
- WindowManager、View 与 Accessibility 回调必须在主线程协调。
- C++ 输入缓冲只在 JNI 调用期间借用，Native 不得持有 Java 数组地址。

## 自动操作

默认关闭。运行路径：

```text
armAutomation() 门控
  → 规划 Avoidance / GestureSpec
  → GestureArbiter（串行、TTL、回执校验）
  → HzzsAccessibilityService.dispatchGesture
```

门控包括：设置开关、免责声明版本、视觉运行中、无障碍前台包白名单、窗口类、场景置信度、帧时效与动作速率。

## MCP

仅监听 `127.0.0.1`，每次启动生成令牌。权限：只读、每次确认、会话信任、完整访问。工具调用进入统一动作注册表，不能直接触摸 Compose 内部状态。

## C++ 视觉

扁平目录 `app/src/main/cpp/`，库名 `hzzs_vision`：

- `sweet_factory` / `bamboo_bookstore` 场景检测
- 共享 `scene_geometry` / `color_components`
- `jni_bridge` 边界校验与结果编码

跨帧状态在 Kotlin，不在 C++ 状态机中。

## 更新

`UpdateRepository`：Gitee 优先、GitHub 校验、清单签名、APK / 差分哈希与证书绑定。应用内 UI 负责触发检查与安装跳转。

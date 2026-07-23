# 初学者阅读路线

这份说明假设你还不熟悉 Android Studio。目标不是第一天看懂所有代码，而是先学会沿一条链找到“谁接收按钮、谁保存状态、谁真正做事”。

## 先把项目想成一所学校

| 项目概念 | 学校比喻 | 在本项目中的例子 |
| --- | --- | --- |
| Application | 校门管理员，进程启动时最先工作 | `HzzsApplication` |
| Activity | 教学楼大厅，承载整套界面 | `MainActivity` |
| Compose Screen | 一间教室，负责显示和接收点击 | `RuntimeScreen` |
| ViewModel | 班长，保留页面状态并转交意图 | `RuntimeViewModel` |
| Repository | 档案室，保存或提供统一数据 | `SettingsRepository` |
| DataStore | 正式档案柜，重启后仍存在 | `hzzs_settings_v5` |
| preview | 老师桌上的临时草稿 | 设置预览 |
| Controller | 流水线值日班长 | `VisionRuntimeController` |
| Service | 能调用 Android 系统能力的工作人员 | 截图服务、无障碍服务 |
| JNI | Kotlin 与 C++ 之间的翻译窗口 | `NativeVision` + `jni_bridge.cpp` |
| C++ 引擎 | 专门检查一张图片的实验室 | `vision_engine.cpp` |
| 单元测试 | 自动批改题目的答案纸 | `app/src/test/` |
| CI | 每次交作业后的自动总检查 | `.github/workflows/` |

## 四层心智模型

```text
第 1 层：页面——用户看见什么、点击什么
第 2 层：状态与规则——现在是什么状态、允不允许做
第 3 层：平台服务——向 Android 请求截图、窗口或手势
第 4 层：Native——高效分析当前这一张图片
```

页面不应该直接执行 Root、Shell、JNI 或 WindowManager；它只把意图交给下层。看到页面很长时，不要从每个颜色和间距读起，先找它使用的 ViewModel。

## 第一条练习：追踪“开始分析”

按顺序打开：

1. `feature/runtime/RuntimeScreen.kt`
2. 找 `RuntimeViewModel.toggle()`。
3. 跳到 `data/vision/VisionRuntimeController.start()`。
4. 再看 `runLoop()`，只读每轮的大步骤，不必先懂全部条件。
5. 最后看 `stop()` 如何取消任务、停止截图和隐藏 HUD。

你会看到：按钮本身不截图；它只通知运行时控制器。

## 第二条练习：追踪“修改主题但还没保存”

1. 打开 `feature/settings/SettingsViewModel.kt`。
2. 找更新草稿的函数。
3. 跳到 `core/preferences/SettingsRepository.kt` 的 `SettingsEditSession`。
4. 观察 `preview` 与 `save` 的区别。
5. 回到 `MainActivity.kt`，看 `HzzsRoot` 如何把配置交给 `HzzsTheme`。

比喻：预览是草稿纸，保存才是把新规章放进档案柜。

## 第三条练习：追踪“一张截图如何变成框”

1. `service/capture/FrameCapture.kt`：认识 `CapturedFrame` 和 `FrameSource`。
2. `data/vision/VisionRuntimeController.kt`：找 `frame.use`。
3. `data/vision/NativeVisionEngine.kt`：看 Kotlin 如何调用 Native。
4. `nativevision/NativeVision.kt`：看 JNI 声明。
5. `app/src/main/cpp/jni_bridge.cpp`：看边界校验。
6. `app/src/main/cpp/vision_engine.cpp`：看场景分发。

关键点：截图像“可重复使用的饭盒”。离开 `frame.use` 后饭盒会归还池中，不能把像素数组留给以后使用。

## 建议学习顺序

1. 先读本页和 [按任务找代码](README.md)。
2. 学会在 Android Studio 中使用“转到定义”和“查找用法”。
3. 先读纯 Kotlin 模型和单元测试，例如设置会话、校验器、手势仲裁。
4. 再读 ViewModel 和 Repository。
5. 然后读 `VisionRuntimeController` 的大流程。
6. 最后才进入 JNI/C++、WindowManager、无障碍、Root、Shizuku 和签名发布。

## 第一次修改适合做什么？

较适合：

- 修正文案或无障碍描述；
- 给纯 Kotlin 校验增加测试；
- 补充不会重复源码的导航说明；
- 修复可由现有测试证明的简单数据映射。

不建议第一次就做：

- 改跨语言枚举顺序或 JNI 构造器；
- 改截图缓冲所有权；
- 让 `AUTO` 尝试 Root/Shizuku/无障碍；
- 改自动操作前台校验；
- 发布 APK 或算法包；
- 把单模块拆成多个 Gradle 产品模块。

## 如何安装开发环境

最低准备：

1. 安装 Android Studio，并让它安装 Android SDK。
2. 使用 JDK 17；项目已有 Gradle Wrapper，不需要单独安装 Gradle。
3. 用 Android Studio 打开仓库根目录，而不是只打开 `app/`。
4. 等待 Gradle Sync 完成。
5. 先运行：

```powershell
python tools/quality/check_resources.py
python tools/quality/check_project.py
.\gradlew.bat :app:assembleDebug
```

连接真机后再安装 Debug 包。截图、悬浮窗、无障碍、Root 和 Shizuku 都涉及不同系统授权；构建成功不代表这些权限已经授予。

本机内存紧张或 Kotlin 增量缓存损坏时，先看 `testing.md` 的“本机 Kotlin IC / 低内存”章节，不要随意改项目构建配置。

## 读代码时经常问的词

- **Flow / StateFlow**：会持续更新的数据管道。
- **协程**：可以暂停和取消的异步任务。
- **Mutex**：一次只让一个任务进入的门锁。
- **generation**：本次运行的腕带编号；旧任务晚到时会因编号不符被丢弃。
- **归一化坐标**：把屏幕横纵范围都写成 `0..1`，避免绑定某种分辨率。
- **fail-closed**：拿不准或验证失败时选择“不执行危险动作”。
- **stub**：预留位置，当前还没有真实实现。

看不懂一个长函数时，先写出它的输入、输出、状态所有者和 `finally/close`，再读细节。

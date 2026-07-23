# 工具、测试与 CI 地图

本页回答三个问题：某个脚本做什么、改动后应跑什么、CI 实际覆盖到哪里。完整命令仍以 `testing.md` 和各工具 README 为准。

## 工具分区

| 路径 | 输入 | 主要输出/副作用 | 是否联网/要密钥 |
| --- | --- | --- | --- |
| `tools/quality/` | 当前仓库源码 | 静态门禁结果 | 否 |
| `tools/vision/` | C++ 源、可选代表帧数据集 | host 库、sanitizer/识别报告 | 否；数据集本地提供 |
| `tools/algorithm/` | 算法源树、签名密钥 | 校验结果、确定性 `.hzzsalg`、目录；`--execute` 可发布 | dry-run 不联网但仍需私钥；execute 需双源 token |
| `tools/release/` | APK、版本与签名材料 | 更新索引、差分、发布辅助产物 | 发布时联网并需 Secrets |
| `tools/dev/` | 本机 Gradle/Kotlin 缓存 | 停 daemon、修复本地缓存 | 否 |
| `.vscode/scripts/` | ADB 设备/本机环境 | 启动、诊断、JDWP 与质量辅助 | 连接设备 |

不要提交 `build/` 中的产物、签名包、私钥、公钥临时派生文件、日志或本机数据集。

## 最低质量门禁

任何代码或文档结构改动至少运行：

```powershell
python tools/quality/check_resources.py
python tools/quality/check_project.py
```

再按范围增加：

| 改动 | 建议验证 |
| --- | --- |
| 纯文档导航 | 两个质量脚本 + Markdown 路径人工核对 |
| Kotlin 纯逻辑 | 相关 `:app:testDebugUnitTest --tests ...` |
| 设置/配置安全 | Settings、主题、MCP 外部摄入相关测试 |
| 截图/自动操作 | 解析和仲裁单测 + 对应 API 真机矩阵 |
| Compose UI | JVM 纯逻辑；有条件补/跑 instrumentation |
| JNI/C++ | Native sanitizer + host tests + 相关 JVM 测试 |
| 算法包源或工具 | 源树校验 + `tools/algorithm/tests` |
| 完整 Android 交付 | `testDebugUnitTest`、`lintDebug`、`assembleDebug` |
| Release | Release 签名配置、验签与发布专项门禁 |

本机内存紧张时优先跑相关测试，不要为了跑全量测试而改变产品构建约束。

## 测试层次

### JVM

位置：`app/src/test/java/`

适合：配置会话、校验、算法 profile、Tracker 纯逻辑、手势仲裁、主题包和帧租约。速度快，不证明真实系统权限与 WindowManager 行为。

### Native direct

位置：`app/src/test/cpp/native_tests.cpp`

由 sanitizer 脚本编译，适合证明 C++ 输入边界、scene、profile 和并发快照。它不经过 Java/JNI。

### Host ABI 与代表帧

入口：`tools/vision/run_host_tests.py`、`host_api.cpp` 和 host 构建脚本。

适合证明宿主 ABI、输出范围和无崩溃。没有人工真值时只能报告稳定性与耗时，不能宣称准确率。

### Android 设备测试

当前仓库缺少完整的已跟踪 `src/androidTest` 覆盖。MediaProjection、悬浮窗、无障碍、Root、Shizuku、前台服务、权限撤销与厂商 ROM 仍需真机验证。

### Python 算法工具测试

位置：`tools/algorithm/tests/`。

覆盖确定性构建、schema、签验、篡改、路径穿越、Zip 炸弹模拟和 dry-run 等；普通构建 CI 当前不自动运行这组测试。

## 三条 CI

| Workflow | 触发与职责 | 主要盲区 |
| --- | --- | --- |
| `.github/workflows/build.yml` | 普通质量、Native、JVM、Lint、Debug APK | 无 instrumentation；当前不跑算法工具 unittest |
| `.github/workflows/release.yml` | 签名 APK、验签、差分、双源发布 | 依赖 Release Secrets 和外部网络 |
| `.github/workflows/algorithm-release.yml` | 手动算法包 dry-run/execute | 当前存在 workflow 与 CLI 参数漂移，发布前必须修复并验证 |

## 算法包流水线

```text
algorithm-packs/<id>/ 源树
→ validate_algorithm_pack.py
→ build_algorithm_pack.py（确定性未签名包）
→ sign_algorithm_pack.py（独立 Ed25519）
→ verify_algorithm_pack.py
→ build_algorithm_catalog.py
→ publish_algorithm_release.py（默认 dry-run）
```

`tools/algorithm/common.py` 是 schema、ZIP 白名单和大小边界的代码真相源。源树只能放声明式内容；私钥必须与 APK keystore 分离。

真实发布只使用 `release-index`：先上传并双侧验证 `algorithms/packages/` 资产，最后更新 `algorithms/stable.json` 或 `beta.json`。不要创建算法 Release tag。

### 发布工具的当前已知风险

这些是后续独立修复事项，不应在不相关提交中顺手改变：

- `algorithm-release.yml` 仍传入 publisher 不接受的 `--draft/--no-draft`，当前手动工作流会在参数解析阶段失败；
- publisher 构建通道目录时只放本次一个 entry，未合并远端旧目录，多算法可能丢目录项；
- concurrency group 含源目录，不同算法可并发覆盖同一通道目录；
- manifest channel 与 CLI channel 尚无一致性检查；
- 双源写入不是事务，一侧成功、一侧失败会部分完成。

修复发布器时必须增加旧目录合并、同版本不可变、同通道串行和失败策略测试。

## 当前测试盲区索引

- `VisionRuntimeController` start/stop/generation/HUD 排帧端到端；
- `MultiObjectTracker` 专门单测；
- 截图源异常 stop 与资源释放；
- 无障碍回调和前台窗口真实集成；
- Compose 主导航、Onboarding、Hilt 与 DataStore instrumentation；
- 算法 catalog 网络回退、兼容版本、路径字段、下载状态；
- 算法 Verifier/Store/Activation 的直接客户端测试；
- JNI 构造器描述符和设备 round-trip；
- 多点找色接线与参数协议。

导航列出缺口不等于测试已失败，而是说明现有测试尚不能证明这些行为。

## 提交前最后检查

1. `git status` 中是否混入别人的未提交文件？
2. 是否只暂存本任务路径，而不是 `git add .`？
3. 是否准确写出已运行和未运行的测试？
4. 文档是否把 stub、源文件或字段误称为已上线能力？
5. 安全、默认行为或用户能力变化是否同步专题文档和 CHANGELOG？
6. 是否保留 README 的 Star History 等受保护区块？

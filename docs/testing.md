# 测试策略

## 本地门禁

```bash
python tools/quality/check_resources.py
python tools/quality/check_project.py
python -m compileall -q tools
./gradlew --no-daemon clean testDebugUnitTest lintDebug assembleDebug
```

Windows 可使用 `.\gradlew.bat`。

日常本机加速（仅 Debug / 真机）：

```powershell
# 推荐：gradle.local.properties 中 hzzs.native.abis=arm64-v8a
$env:CMAKE_BUILD_PARALLEL_LEVEL = '2'
.\gradlew.bat -Phzzs.native.abis=arm64-v8a :app:assembleDebug
```

完整 ABI 与 CI 门禁不要传该参数。若配置阶段每次都很慢，检查 `%GRADLE_USER_HOME%\gradle.properties` 是否强制关闭了 `configuration-cache`（应删除/注释 `org.gradle.configuration-cache=false`）。仓库 `gradlew`/`gradlew.bat` 默认会 `-Dorg.gradle.configuration-cache=true` 覆盖用户级 false；调试用户关闭时设 `HZZS_ALLOW_USER_CC_OVERRIDE=1`。`CMAKE_BUILD_PARALLEL_LEVEL` 在 wrapper 未设置时默认为 `2`。

### 本机 Kotlin IC / 低内存

- 产品使用 **Hilt + KSP**（不再 kapt stub 双编译）。
- 堆与 worker 按 low-memory / 4 线程画像写在根 `gradle.properties`；可用 `gradle.local.properties` 覆盖。
- 若出现 `classpath-snapshot` / `shrunk-classpath-snapshot.bin` 找不到、IC 回退全量、或 `Gradle build daemon has been stopped: stop command received`：

```powershell
# 停 daemon + 清 Kotlin/KSP IC；空闲内存尚可时再加 -Compile
.\tools\dev\repair_gradle_kotlin_cache.ps1 -Compile
# 或手动（IDE 常会 stop 常驻 daemon 时加 --no-daemon）
.\gradlew.bat --no-daemon --console=plain :app:compileDebugKotlin
```

- 可用物理内存 < ~1.5 GiB 时先关多余语言服务 / 浏览器，再构建；全量 `:app:testDebugUnitTest` 易 OOM，优先相关 `--tests`。
- 项目默认 `ksp.incremental=false`（低内存写盘更稳）；充裕时可在用户级 `gradle.properties` 打开。

Release（需签名配置，见 README「Release 构建与签名」）：

```bash
./gradlew --no-daemon :app:testDebugUnitTest :app:lintRelease :app:assembleRelease
```

缺少 keystore 路径/密码/alias 时 `assembleRelease` 必须失败；不得静默使用 debug 签名发布。

### 质量脚本

| 脚本 | 作用 |
|---|---|
| `tools/quality/check_resources.py` | 资源 XML、图标、赞赏图等静态检查 |
| `tools/quality/check_project.py` | 单模块架构、manifest 导出面、MCP loopback、AUTO 不升权等不变量 |

## JVM 单元测试

位于 `app/src/test/java/`，至少覆盖：

- `GestureArbiter` / `ActionCommitLedger`
- `VisionResultValidator`
- 设置会话预览丢弃与自动化 fail-closed
- 主题包编解码与恶意字段
- 帧序号与像素池分代租约

运行：

```bash
./gradlew :app:testDebugUnitTest
```

## Native

```bash
bash tools/vision/build_host.sh
python tools/vision/run_host_tests.py --dataset /path/to/images --max-representative 48
bash tools/vision/run_native_sanitizers.sh
python tools/vision/evaluate_dataset.py --dataset /path/to/images --output build/vision-results
```

说明：

- 宿主机测试参数是 `--max-representative`，不是 `--limit`。
- 数据集路径也可通过环境变量 `HZZS_TEST_DATASET` 或目录 `test_images/`、`.test-data/` 提供。
- **无人工真值时只报告稳定性与耗时，不报告准确率。**

`app/src/test/cpp/native_tests.cpp` 由 sanitizer 脚本编译运行（须链接 `sea_salt_living_room.cpp` 等与 `CMakeLists.txt` 一致的源；含 scene 0..`kSceneCount-1` 边界断言）。

## Android 场景矩阵

最低覆盖 API 24、26、28、30、33、35：

- 冷启动 / 热启动 / 旋转 / 深浅主题 / 进程恢复
- 权限允许与拒绝
- MediaProjection 取消
- 无障碍断开
- 悬浮窗权限撤销
- 重复启动停止与低内存恢复

厂商 ROM、Root、Shizuku 与真实游戏链路必须使用对应真机验证。

## 算法包工具链

```bash
python -m unittest discover -s tools/algorithm/tests -v
python tools/algorithm/validate_algorithm_pack.py --source algorithm-packs/official-bamboo-baseline
python tools/algorithm/publish_algorithm_release.py \
  --source algorithm-packs/official-bamboo-baseline \
  --work-dir build/algorithm-release \
  --private-key /secure/algorithm-ed25519.pem \
  --key-id hzzs-algorithm-official-1
```

默认 `publish_algorithm_release.py` 为 **dry-run**（不访问网络）。真实发布需显式 `--execute`，并配置独立 Secrets：`ALGORITHM_SIGNING_PRIVATE_KEY_B64`、`ALGORITHM_SIGNING_KEY_ID`（不得复用 APK keystore）。  
覆盖：可重复构建、签名/篡改、路径穿越、Zip 炸弹模拟、超大文件、禁止扩展名、stable/beta 隔离、撤销标记、Gitee 不可变同步。详见 [`docs/ALGORITHM_SYSTEM_V1.md`](ALGORITHM_SYSTEM_V1.md)。

## CI

`.github/workflows/build.yml`：质量脚本 → Native sanitizer / host tests → Gradle 测试 / lint / debug APK。  
`.github/workflows/release.yml`：签名构建、验签、差分补丁、双源发布与匿名哈希校验。  
`.github/workflows/algorithm-release.yml`：官方算法包校验、签名、双源同步与目录发布（默认 dry-run）。

## 禁止的宣称

在缺少独立人工边界标注前，不得在文档、Issue 或发布说明中宣称：

- 99% 准确率
- 全机型覆盖
- 固定像素误差达标

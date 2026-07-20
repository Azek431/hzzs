# 测试策略

## 本地门禁

```bash
python tools/quality/check_resources.py
python tools/quality/check_project.py
python -m compileall -q tools
./gradlew --no-daemon clean testDebugUnitTest lintDebug assembleDebug
```

Windows 可使用 `.\gradlew.bat`。

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

`app/src/test/cpp/native_tests.cpp` 由 sanitizer 脚本编译运行。

## Android 场景矩阵

最低覆盖 API 24、26、28、30、33、35：

- 冷启动 / 热启动 / 旋转 / 深浅主题 / 进程恢复
- 权限允许与拒绝
- MediaProjection 取消
- 无障碍断开
- 悬浮窗权限撤销
- 重复启动停止与低内存恢复

厂商 ROM、Root、Shizuku 与真实游戏链路必须使用对应真机验证。

## CI

`.github/workflows/build.yml`：质量脚本 → Native sanitizer / host tests → Gradle 测试 / lint / debug APK。  
`.github/workflows/release.yml`：签名构建、验签、差分补丁、双源发布与匿名哈希校验。

## 禁止的宣称

在缺少独立人工边界标注前，不得在文档、Issue 或发布说明中宣称：

- 99% 准确率
- 全机型覆盖
- 固定像素误差达标

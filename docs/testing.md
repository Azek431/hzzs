# 测试策略

## 本地门禁

```bash
python tools/quality/check_resources.py
python tools/quality/check_project.py
python -m compileall -q tools
./gradlew clean testDebugUnitTest lintDebug assembleDebug
```

## Native

```bash
tools/vision/build_host.sh
python tools/vision/run_host_tests.py --dataset /path/to/images --limit 48
tools/vision/run_native_sanitizers.sh
python tools/vision/evaluate_dataset.py --dataset /path/to/images
```

## Android 场景

最低覆盖 API 24、26、28、30、33、35：冷启动、热启动、旋转、深浅主题、进程恢复、权限允许/拒绝、MediaProjection 取消、无障碍断开、悬浮窗权限撤销、重复启动停止和低内存恢复。

厂商 ROM、Root、Shizuku 与真实游戏链路必须使用对应真机验证。无人工边界标注时不得宣称准确率目标达成。

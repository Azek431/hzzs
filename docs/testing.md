# 测试说明

## 当前可在普通 Linux 主机运行的测试

```bash
python3 tools/quality/check_resources.py
bash tools/vision/build_host.sh
python3 tools/vision/run_host_tests.py
bash tools/vision/run_native_sanitizers.sh
```

覆盖内容：

- Android XML 和图标资源完整性；
- 赞赏二维码资源存在性与支付宝二维码可解码性；
- C++ 代表性截图回归；
- 空白帧误报；
- Native 单元测试；
- AddressSanitizer 与 UndefinedBehaviorSanitizer。

## Android 构建验证

在具备 JDK 17、Android SDK 37、NDK 28.2.13676358 和 CMake 的环境中运行：

```bash
./gradlew --no-daemon clean testDebugUnitTest lintDebug assembleDebug
```

Release 构建必须提供正式签名环境变量，缺失时应直接失败。

## 视觉数据集

当前数据集包含甜品工厂 258 张、竹影书屋 186 张，共 444 张。机器绘制结果用于人工复核，但在完成人工真实边界标注前，不声明满足“左右边界误差不超过玩家宽度 5%”。

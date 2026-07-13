# HZZS 本地视觉回归

## CI 合成回归

synthetic_regression.py 只依赖 Python 标准库和宿主机 C++17 编译器。它会在临时目录编译正式 vision2 核心，并通过合成像素帧覆盖：

- 全负样本；
- 绿瓶、蛋糕和悬挂尖刺；
- 多类障碍同时出现；
- 不同分辨率；
- rowStride 大于 width；
- 颜色阈值负样本；
- 输出容量和无效 stride 防护。

在 Linux 或 macOS 项目根目录运行：

    python3 tools/hzzs-vision-tests/synthetic_regression.py

该回归不读取项目外素材，适合在 GitHub Actions 中作为必过检查。

## 本地真实素材回归

项目根目录的 .env.hzzs.local 保存项目外测试素材目录，已加入 .gitignore。

PowerShell 脚本可读取：

    HZZS_TEST_ASSET_ROOT=D:\Code\AI\火崽崽\火崽崽奇妙屋\算法测试\测试图片

host_regression.py 需要 Python、Pillow、NumPy 和可用的 g++。Android 项目本身不依赖这些工具，真实素材也不会进入 CI 或仓库。

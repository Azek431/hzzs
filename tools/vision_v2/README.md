# FastContourV2 低计算量轮廓识别原型

本目录是与 Android 运行时隔离的 Python 实验层，用于验证三赛季共用底层、不同参数 profile 的低计算量障碍检测方案。

> 当前代码不会被 Gradle、CMake 或 APK 引用，不改变现有 Legacy 检测、Tracker、动作规划与默认行为。

## 运行时管线

1. 保持纵横比缩放到工作宽度 360；
2. 5-bit RGB LUT（32768 项）一次查表同时分类多个障碍；
3. 只扫描障碍预期出现的窄行带或走廊；
4. 使用稀有且稳定的颜色作为候选锚点；
5. 候选点使用 4-of-7 稀疏颜色签名验证；
6. 将标准轮廓放置到候选位置；
7. 沿轮廓法线在 ±3～4 个工作像素内做局部 RGB 对比贴边；
8. 竹影地面缺口使用平台列证据、RLE 与局部连通域轮廓。

生产候选算法不创建全帧 Canny/Sobel 图。Canny 仅可作为没有人工 mask 时的开发期边缘代理指标。

## 安装与运行

```bash
python -m pip install -r tools/vision_v2/requirements.txt
python tools/vision_v2/benchmark.py /path/to/测试图片 \
  --output build/vision-v2-benchmark
```

数据集根目录应包含：

```text
甜品工厂/
竹影书屋/
海盐客厅/
```

## 输出约定

- `contour`：原始截图坐标中的 `Nx2 float32` 近似轮廓；
- `box`：由轮廓派生的矩形；当前 HZZS 集成前仍必须遵守现有 `Detection.bounds` 几何真相源；
- `score`：规则内部得分，不是经过概率校准的置信度；
- 每类当前最多返回一个最佳候选，适合当前前方障碍实验。

## 测试边界

测试图片没有人工逐像素 mask，所以当前不得报告真实 IoU、precision 或 recall。开发阶段只使用：

- AutoJs 多点找色命中作为海盐定位基线；
- 高置信模板对齐作为甜品和竹影固定贴图的伪标签；
- 人工查看接触表；
- 轮廓点到 Canny 边缘距离作为辅助代理指标。

正式迁移 C++ 前应补人工多边形标注，并分别报告每类检出指标、轮廓 IoU、边界 F-score 和 P50/P95 延迟。

## C++ host smoke（与 Python 实验并列）

独立于 `tools/vision/build_host.*` / `libhzzs_vision`：

```powershell
python tools/vision_v2/run_host_smoke.py
python tools/vision_v2/run_host_smoke.py --test pipeline
python tools/vision_v2/run_host_smoke.py --sanitize address
python tools/vision_v2/run_host_smoke.py --sanitize undefined
```

详见 `app/src/main/cpp/vision_v2/README.md`。APK 与 Legacy host ABI **不**链接 `vision_v2`。

## 集成原则

1. 先在独立 host 测试目标中实现，不立即接入 APK；
2. Legacy 保持默认输出；FastContourV2 先采用影子对比模式；
3. C++ 热路径使用固定容量候选与帧级 contour arena，避免每帧堆分配；
4. 所有阈值使用工作图尺寸、视口比例或玩家宽度，不使用固定手机屏幕像素；
5. CC 根据最新 `main` 决定 cherry-pick、rebase 或重新实现接线层。

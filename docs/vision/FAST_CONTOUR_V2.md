# FastContourV2 隔离实验设计

## 状态

- 当前为实验原型，不接入 APK、JNI、Tracker 或动作规划。
- Legacy 仍是唯一正式检测输出。
- `Detection.bounds` 仍是当前动作、Tracker 和距离判断的几何真相源。
- 轮廓只作为实验输出；升级为规划真相源必须单独迁移协议和测试。

## 目标

FastContourV2 试图用一套底层适配三个赛季：

```text
低分辨率工作图
→ 限定扫描带
→ RGB15 LUT 单次分类
→ 固定容量候选
→ 稀疏多点验证
→ 标准轮廓放置
→ 局部 RGB 法线贴边
→ bounds 派生
```

竹影地面缺口属于动态结构，复用工作图与固定容量结果，但使用列证据、RLE 和局部连通区域，而不是固定贴图 profile。

## 性能原则

- 不对每个模板重复扫描完整 ROI；
- 不在生产热路径生成全帧 Canny/Sobel；
- 不使用大模板相关、SIFT/ORB、GrabCut 或神经网络；
- 不在帧路径解析 JSON 或执行文件 IO；
- 不保存 JNI 输入缓冲或截图帧引用；
- 候选、轮廓与检测结果使用固定容量缓冲。

## 坐标约定

原型内部必须明确区分：

1. 原始截图像素；
2. 保持宽高比缩放后的工作图像素；
3. 视口归一化坐标；
4. 玩家宽度或其他相对业务单位；
5. HUD/手势设备像素。

正式接入时，Native 输出继续遵守仓库的 `[0,1]` 归一化协议。

## 分阶段集成

1. Python 基准和参数留在 `tools/vision_v2/`；
2. 独立 C++ core 与 host test，不加入现有 native library；
   **当前进度**：`python tools/vision_v2/run_host_smoke.py`（core + boundary + pipeline；可选 ASan/UBSan）。
   已有固定容量条带扫描/稀疏验证/轮廓放置 API（`fast_contour_pipeline.*`）；海盐 scan-point 与竹影缺口仍待迁。
   产物在 `build/vision-v2-host/`，**未**进入 `CMakeLists.txt` / `build_host.*` / APK。
3. 在最新 `main` 上由 CC 手工完成最小 CMake 接线（仅授权后）；
4. 先运行 ShadowCompare，只记录新旧差异；
5. 分赛季验证后再考虑切换默认后端；
6. 蛋糕低对比轮廓、大水坑缺少正例等空白未解决前不得宣称完成。

## 验证边界

没有人工真值时，只报告：

- 速度与 P50/P95；
- 输出稳定性；
- 新旧算法差异；
- 人工抽样；
- 轮廓到边缘的代理指标。

不得把代理指标表述为真实 precision、recall、IoU 或边界 F-score。

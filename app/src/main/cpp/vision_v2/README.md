# FastContourV2 Native Core（未接线）

这里提供一个独立、固定容量、无帧级堆分配的 C++ 核心草案：

- `Rgb15Lut`：32768 项 RGB15 类别位掩码；
- `verify_sparse_pattern`：固定稀疏点、邻域颜色验证；
- `ContourArena`：扁平固定容量轮廓池；
- `refine_contour_rgb`：仅沿输入轮廓法线读取少量 RGB，不创建全帧梯度图。

当前文件**没有加入 CMakeLists.txt**，不会改变 Android Native Library。CC 应先读取最新 `main` 的类型、ABI、测试脚本和并行改动，再决定复用、重命名、拆分或重新接线。

独立宿主机烟雾测试：

```bash
c++ -std=c++20 -Wall -Wextra -Werror \
  app/src/main/cpp/vision_v2/fast_contour_core.cpp \
  app/src/main/cpp/vision_v2/fast_contour_core_test.cpp \
  -o /tmp/fast_contour_core_test
/tmp/fast_contour_core_test
```

正式集成要求：

1. 使用仓库当前 Android C++ 标准和命名约定；
2. 增加 ASan/UBSan、非法输入、容量边界和真实帧测试；
3. LUT/profile 在初始化或配置 generation 切换时构建；
4. 不在帧路径解析 JSON；
5. 首轮只做 ShadowCompare，Legacy 保持正式输出；
6. 不让轮廓静默替代现有 `Detection.bounds` 动作语义。

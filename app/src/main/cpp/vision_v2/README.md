# FastContourV2 Native Core（未接线）

这里提供一个独立、固定容量、无帧级堆分配的 C++ 核心草案：

- `Rgb15Lut`：32768 项 RGB15 类别位掩码；
- `verify_sparse_pattern`：固定稀疏点、邻域颜色验证；
- `ContourArena`：扁平固定容量轮廓池；
- `refine_contour_rgb`：仅沿输入轮廓法线读取少量 RGB，不创建全帧梯度图。

当前文件**没有加入 CMakeLists.txt**，不会改变 Android Native Library。CC 应先读取最新 `main` 的类型、ABI、测试脚本和并行改动，再决定复用、重命名、拆分或重新接线。

独立宿主机烟雾测试（**不**进入 `libhzzs_vision` / Android NDK）：

```powershell
# 推荐：跨平台入口（Windows 走 ps1，其它走 bash；勿直接 exec 无 +x 的 .sh）
python tools/vision_v2/run_host_smoke.py
python tools/vision_v2/run_host_smoke.py --sanitize address
python tools/vision_v2/run_host_smoke.py --sanitize undefined
python tools/vision_v2/run_host_smoke.py --test boundary
```

```bash
# 或显式脚本
bash tools/vision_v2/build_host_smoke.sh
bash tools/vision_v2/build_host_smoke.sh --sanitize=address
```

产物目录：`build/vision-v2-host/`（已被根 `.gitignore` 的 `build/` 忽略）。

- `fast_contour_core_test`：LUT / 稀疏验证 / arena / 贴边 happy path
- `fast_contour_core_boundary_test`：非法输入、容量边界

**不要**把本目录源文件加入 `app/src/main/cpp/CMakeLists.txt` 或 `tools/vision/build_host.*`，除非单独授权 APK/Shadow 接线阶段。

正式集成要求：

1. 使用仓库当前 Android C++ 标准和命名约定；
2. 增加 ASan/UBSan、非法输入、容量边界和真实帧测试；
3. LUT/profile 在初始化或配置 generation 切换时构建；
4. 不在帧路径解析 JSON；
5. 首轮只做 ShadowCompare，Legacy 保持正式输出；
6. 不让轮廓静默替代现有 `Detection.bounds` 动作语义。

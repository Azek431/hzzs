# 视觉识别设置界面说明

> **状态**：规划中（Milestone 2 ~ 3）
>
> 本文档描述未来视觉识别模块的设置界面设计、所有可调参数及其含义。

## 1. 设置界面结构

视觉识别设置作为独立页面（`VisionSettingsActivity`）或悬浮窗内嵌面板，组织如下分组：

```
视觉识别设置
├── 通用设置
│   ├── 识别开关（总开关）
│   ├── 识别帧率（FPS）
│   └── ROI 区域（自动/自定义）
├── 绿瓶（毒瓶）识别
│   ├── 色相范围 H: [40 - 80]
│   ├── 饱和度下限 S: [60]
│   ├── 亮度下限 V: [40]
│   ├── G/R 比值: [1.3]
│   ├── G/B 比值: [1.2]
│   └── 最小置信度: [0.62]
├── 蛋糕断层识别
│   ├── 颜色阈值（橙红色范围）
│   ├── 轮廓面积范围
│   └── 最小置信度: [0.62]
├── 裱花袋识别
│   ├── 颜色阈值（白色/奶油色）
│   ├── 位置约束（顶部区域）
│   └── 最小置信度: [0.62]
├── 分析引擎参数
│   ├── 场景确认帧数: [2]
│   ├── 提示稳定帧数: [2]
│   ├── ETA 最大窗口: [2200ms]
│   └── 提示 ETA 范围: [170ms - 900ms]
└── 调试选项
    ├── 调试叠加层（VisionDebugOverlayView）
    ├── 日志级别（INFO/WARN/ERROR/DEBUG）
    └── 导出检测数据
```

## 2. 所有可调参数详解

### 2.1 通用设置

| 参数名 | 类型 | 默认值 | 范围 | 说明 |
| --- | --- | --- | --- | --- |
| `vision_enabled` | Boolean | `true` | - | 视觉识别总开关。关闭后所有识别模块停止，HUD 仅显示基础信息。 |
| `recognition_fps` | Int | `30` | [10, 60] | 视觉识别的帧率。与 HUD 渲染帧率（20fps）可独立设置。过高 FPS 增加 CPU/GPU 负载。 |
| `roi_mode` | Enum | `AUTO` | [AUTO, CUSTOM] | ROI 区域模式。AUTO 自动裁剪状态栏/导航栏；CUSTOM 使用自定义坐标。 |
| `roi_left` | Float | 自动计算 | [0.0, 1.0] | 自定义 ROI 左边界（归一化）。仅 CUSTOM 模式下生效。 |
| `roi_top` | Float | 自动计算 | [0.0, 1.0] | 自定义 ROI 上边界。 |
| `roi_right` | Float | 自动计算 | [0.0, 1.0] | 自定义 ROI 右边界。 |
| `roi_bottom` | Float | 自动计算 | [0.0, 1.0] | 自定义 ROI 下边界。 |

### 2.2 绿瓶（毒瓶）识别参数

| 参数名 | 类型 | 默认值 | 范围 | 说明 |
| --- | --- | --- | --- | --- |
| `bottle_h_min` | Int | `40` | [0, 180] | HSV 色相下限。值越小越偏黄绿，越大越偏青绿。 |
| `bottle_h_max` | Int | `80` | [0, 180] | HSV 色相上限。 |
| `bottle_s_min` | Int | `60` | [0, 255] | 饱和度下限。过低会混入灰色区域。 |
| `bottle_v_min` | Int | `40` | [0, 255] | 亮度下限。过低会漏掉暗色场景中的绿瓶。 |
| `bottle_g_r_ratio` | Float | `1.3` | [1.0, 3.0] | RGB 辅助判断：G/R 最小比值。 |
| `bottle_g_b_ratio` | Float | `1.2` | [1.0, 3.0] | RGB 辅助判断：G/B 最小比值。 |
| `bottle_min_area` | Int | `200` | [50, 5000] | 最小有效轮廓面积（像素）。分辨率越高，此值需越大。 |
| `bottle_max_area` | Int | `10000` | [500, 50000] | 最大有效轮廓面积。防止大面积绿色区域被误检。 |
| `bottle_aspect_min` | Float | `0.3` | [0.1, 0.8] | 瓶状最小长宽比。 |
| `bottle_aspect_max` | Float | `0.8` | [0.2, 1.0] | 瓶状最大长宽比。 |
| `bottle_conf_threshold` | Float | `0.62` | [0.5, 0.9] | 最低输出置信度。与 C++ 端 `kMinHazardConfidence` 保持一致。 |

### 2.3 蛋糕断层识别参数

| 参数名 | 类型 | 默认值 | 范围 | 说明 |
| --- | --- | --- | --- | --- |
| `gap_color_h_min` | Int | `0` | [0, 180] | 橙红色色相下限。 |
| `gap_color_h_max` | Int | `20` | [0, 180] | 橙红色色相上限。 |
| `gap_s_min` | Int | `80` | [0, 255] | 饱和度下限。 |
| `gap_min_width` | Float | `0.05` | [0.02, 0.50] | 最小宽度（归一化坐标）。小于此值的橙色区域不视为断层。 |
| `gap_max_width` | Float | `0.50` | [0.05, 1.0] | 最大宽度（归一化坐标）。 |
| `gap_y_position_min` | Float | `0.60` | [0.3, 1.0] | 断层 Y 位置下限（归一化）。断层通常位于屏幕下半部分。 |
| `gap_conf_threshold` | Float | `0.62` | [0.5, 0.9] | 最低输出置信度。 |

### 2.4 裱花袋识别参数

| 参数名 | 类型 | 默认值 | 范围 | 说明 |
| --- | --- | --- | --- | --- |
| `piping_color_h_min` | Int | `0` | [0, 180] | 奶油色/白色色相范围（任意色相均可，依赖饱和度）。 |
| `piping_s_max` | Int | `40` | [0, 255] | 饱和度上限。白色/奶油色饱和度很低。 |
| `piping_v_min` | Int | `180` | [100, 255] | 亮度下限。裱花袋为浅色物体。 |
| `piping_max_y` | Float | `0.35` | [0.1, 0.5] | 最大 Y 位置（归一化）。裱花袋悬挂在顶部，Y 值较小。 |
| `piping_min_width` | Float | `0.04` | [0.02, 0.30] | 最小宽度（归一化坐标）。 |
| `piping_conf_threshold` | Float | `0.62` | [0.5, 0.9] | 最低输出置信度。 |

### 2.5 分析引擎参数

这些参数与 C++ 端常量对应，允许用户在特定场景下微调：

| 参数名 | 类型 | 默认值 | 对应 C++ 常量 | 范围 | 说明 |
| --- | --- | --- | --- | --- | --- |
| `scene_confirm_frames` | Int | `2` | `kTransitionConfirmFrames` | [1, 5] | 场景切换确认所需的连续帧数。值越大越稳定但响应越慢。 |
| `prompt_stable_frames` | Int | `2` | `kRequiredStableFrames` | [1, 5] | 动作提示稳定所需的连续帧数。 |
| `eta_max_window_ms` | Int | `2200` | `kMaxUsefulEtaMs` | [500, 5000] | ETA 最大窗口（毫秒）。超过此时间的危险物不进入提示。 |
| `prompt_eta_min_ms` | Int | `170` | `kMinPromptEtaMs` | [50, 500] | 提示最小 ETA。太小的 ETA 意味着来不及反应。 |
| `prompt_eta_max_ms` | Int | `900` | `kMaxPromptEtaMs` | [300, 2000] | 提示最大 ETA。太大的 ETA 会造成干扰。 |
| `prompt_conf_threshold` | Float | `0.72` | `kMinPromptConfidence` | [0.5, 0.95] | 提示最小可信度。 |
| `occlusion_conf_boost` | Float | `0.85` | 遮挡时 `effective_min_confidence` | [0.7, 0.95] | 遮挡状态下的置信度提升阈值。 |
| `safety_margin` | Float | `0.014` | `kPlayerSafetyMargin` | [0.0, 0.05] | 玩家安全余量（归一化坐标）。影响 ETA 计算的距离。 |
| `wide_gap_threshold` | Float | `0.18` | `kWideGapThreshold` | [0.10, 0.30] | 宽断层阈值（归一化宽度）。超过此值触发二连跳提示。 |
| `slide_height_ratio` | Float | `0.76` | `kSlideHeightRatio` | [0.50, 0.90] | 滑铲检测的高度比例。 |
| `upward_velocity_threshold` | Float | `-0.070` | `kUpwardVelocityThreshold` | [-0.100, -0.030] | 起跳上升的垂直速度阈值。 |
| `downward_velocity_threshold` | Float | `0.070` | `kDownwardVelocityThreshold` | [0.030, 0.100] | 下落阶段的垂直速度阈值。 |
| `baseline_smoothing` | Float | `0.08` | `kBaselineSmoothing` | [0.01, 0.20] | 基线平滑系数（指数移动平均）。 |
| `airborne_threshold` | Float | `0.018` | `kAirborneThreshold` | [0.005, 0.050] | 着地判定阈值。 |
| `second_jump_velocity` | Float | `-0.100` | `kSecondJumpImpulseThreshold` | [-0.150, -0.070] | 二段跳垂直速度阈值。 |

### 2.6 调试选项

| 参数名 | 类型 | 默认值 | 说明 |
| --- | --- | --- | --- |
| `debug_overlay_enabled` | Boolean | `false` | 是否启用 `VisionDebugOverlayView` 调试叠加层。默认关闭，生产环境不需要。 |
| `debug_log_level` | Enum | `WARN` | [VERBOSE, INFO, WARN, ERROR] | 日志级别。VERBOSE 输出每帧详细数据。 |
| `export_detection_data` | Boolean | `false` | 是否将检测数据导出到本地文件（用于离线分析）。 |
| `export_path` | String | `"/sdcard/HZZS/detections/"` | 导出数据保存路径。 |

## 3. 参数持久化

所有参数通过 `SharedPreferences` 存储，Key 前缀为 `vision_`：

```
hzzs_vision_settings（SharedPreferences 文件名）
├── vision_enabled
├── vision_recognition_fps
├── vision_roi_mode
├── vision_bottle_h_min ...
├── vision_gap_conf_threshold ...
├── vision_prompt_stable_frames ...
└── vision_debug_overlay_enabled
```

参数变更时即时写入（`apply()`），应用启动时从 SharedPreferences 加载。

## 4. 参数校验规则

| 规则 | 说明 |
| --- | --- |
| 范围约束 | 所有参数在 UI 输入时校验范围，超出则自动修正到边界值。 |
| 联动约束 | `bottle_conf_threshold` 修改时，同步检查 C++ 端 `kMinHazardConfidence` 是否一致，不一致时显示警告。 |
| 默认值恢复 | 提供"恢复默认值"按钮，一键重置所有参数。 |
| 热重载 | 修改参数后无需重启应用，下一帧自动生效。 |

## 5. 已知限制

1. 当前项目尚无视觉识别模块的实现，所有参数为设计阶段的预设值。
2. 参数默认值基于 60fps 竖屏录屏素材的平均观测值，实际可能需要针对具体设备分辨率调整。
3. 不同游戏的版本更新可能导致物体颜色/形状变化，参数可能需要随版本更新重新校准。

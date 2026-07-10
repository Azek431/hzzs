# 绿瓶算法详解

## 概述

绿瓶检测器在玩家中心 Y 水平线上执行 RGB 绿色像素扫描，用于识别画面中的绿色收集物。

## 算法流程

### 1. 计算扫描参数

```
playerLeftPx   = playerReference.left   * screenWidth
playerRightPx  = playerReference.right  * screenWidth
playerWidthPx  = playerReference.width  * screenWidth
scanY          = playerReference.centerY * screenHeight（限制在 [0, height-1]）
scanStartX     = playerRightPx + playerWidthPx * 0.15（限制不超过 width-20）
scanEndX       = screenWidth - 20
```

### 2. 行扫描

从 scanStartX 到 scanEndX，逐像素检查是否为绿色：

```
if (isGreenPixel(r, g, b)) {
    如果 segStart < 0 → 记录段起点
} else {
    如果 segStart >= 0 → 记录段终点，segStart = -1
}
```

### 3. 合并小缺口

如果两个绿色段之间的距离 <= gapMergeWidth（默认 5px），合并为一个段。

### 4. 过滤与选择

- 过滤宽度 < minSegmentWidth（默认 3px）的候选
- 取玩家右侧最近的候选（left - playerRightPx 最小）

### 5. 计算精确边界

```
padding = playerWidthPx * 0.065，限制在 [4, 10]px
leftX   = closest.left + padding
rightX  = closest.right - padding
centerX = (leftX + rightX) / 2
```

### 6. 置信度计算

```
widthScore   = min(closest.width / (playerWidthPx * 0.5), 1.0)
chromaScore  = average(max(R,G,B) - min(R,G,B)) / 255
confidence   = widthScore * 0.4 + chromaScore * 0.6
```

### 7. 阈值过滤

如果 confidence < confidenceThreshold（默认 0.5），返回未检测到。

## RGB 绿色规则

```
G > rgbGMin          (默认 120)
G - R > 15
G - B > 30
max(R,G,B) - min(R,G,B) > 45
```

## 输出字段

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| found | Boolean | 是否检测到 |
| leftX | Float | 左边界归一化坐标 |
| rightX | Float | 右边界归一化坐标 |
| centerX | Float | 中心归一化坐标 |
| scanY | Int | 扫描线 Y 像素坐标 |
| confidence | Float | 置信度 0.0~1.0 |
| edgeGapPx | Int | 左边缘到玩家右侧距离 |
| costMs | Float | 检测耗时毫秒 |

## 可调参数

| 参数 | 默认值 | 范围 | 说明 |
| --- | --- | --- | --- |
| rgbGMin | 120 | 0~255 | G 通道下限 |
| rgbRMin | 30 | 0~255 | R 通道下限 |
| rgbBMin | 30 | 0~255 | B 通道下限 |
| minSegmentWidth | 3 | 1~50 | 最小段宽（像素） |
| gapMergeWidth | 5 | 0~100 | 小缺口合并宽度（像素） |
| detectionPadding | 10 | 0~200 | 检测区域 padding（像素） |
| confidenceThreshold | 0.5 | 0.0~1.0 | 置信度阈值 |

## 性能

- 无状态，可安全在任意线程调用
- 检测耗时通常在 1~5ms 以内（取决于屏幕宽度）
- 单次扫描 O(n)，n = scanEndX - scanStartX

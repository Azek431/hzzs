# ============================================================
# HZZS（火崽崽助手）：项目专用 R8 Keep Rules
# ============================================================

# ---------------------------------------------------------------------------
# JNI 门面层：NativeEngineFacade — 可能因 R8 混淆导致符号名不匹配
# ---------------------------------------------------------------------------
-keep class top.azek431.hzzs.core.data.native.NativeEngineFacade {
    native <methods>;
}

# ---------------------------------------------------------------------------
# JNI 库加载层：NativeLibraryLoader — 静态初始化加载 .so
# ---------------------------------------------------------------------------
-keep class top.azek431.hzzs.core.data.native.NativeLibraryLoader

# ---------------------------------------------------------------------------
# JNI 视觉识别层：VisionAnalysisBridge — 绿瓶检测 JNI 符号
# ---------------------------------------------------------------------------
-keep class top.azek431.hzzs.data.vision.VisionAnalysisBridge {
    private native java.lang.String nativeVisionScanGreenBottle(int[],int,int,float,float,float,float);
}

# ---------------------------------------------------------------------------
# Android 组件（Activity / Service / BroadcastReceiver）
# 由 Android Gradle Plugin 根据 Manifest 自动处理，无需手动 keep。
# ---------------------------------------------------------------------------

# 以下为开发预留区域：
# 后续仅在引入特定库、使用反射、或 Release 构建出现 R8 警告时，
# 再根据具体报错或官方文档添加"最小必要规则"。

# 注意：
# - 不要添加旧 ProGuard 教程中的 -dontpreverify、
#   -optimizationpasses、-verbose 等全局规则。
# - 不要为了"保险"而 keep 所有 Activity / Service / Receiver；
#   Android Gradle Plugin 会根据 Manifest 自动处理 Android 组件。
# ============================================================

# HZZS-MIGRATION-BEGIN jni-keep
-keep class top.azek431.hzzs.runtime.vision.HzzsVisionBridge { native <methods>; }
-keepclasseswithmembernames class * { native <methods>; }
# HZZS-MIGRATION-END jni-keep

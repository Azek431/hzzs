# ============================================================
# HZZS（火崽崽助手）：项目专用 R8 Keep Rules
# ============================================================

# ---------------------------------------------------------------------------
# JNI / Native 方法 Keep Rules
# ---------------------------------------------------------------------------
# NativeAnalysisBridge.kt 声明了 private external fun，R8 可能混淆类名。
# 保留整个 NativeAnalysisBridge object 不被混淆，确保 JNI 符号名匹配：
# C++ 端导出 Java_top_azek431_hzzs_NativeAnalysisBridge_*，
# Kotlin 端需要类名 top.azek431.hzzs.NativeAnalysisBridge 保持不变。
-keep class top.azek431.hzzs.NativeAnalysisBridge {
    private native java.lang.String nativeGetEngineInfo();
    private native java.lang.String nativeRunSelfCheck();
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

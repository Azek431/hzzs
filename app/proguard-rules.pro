# ============================================================
# HZZS（火崽崽助手）：项目专用 R8 Keep Rules
# ============================================================
#
# 当前基础项目没有：
# - 反射创建类或成员；
# - WebView JavaScript 接口；
# - JNI / Native 动态调用；
# - JSON 反射序列化；
# - 第三方 SDK 特殊混淆要求。
#
# 因此这里暂时不添加 Keep Rule。
#
# 后续只有在引入某个库、使用反射或 Release 构建出现 R8 警告时，
# 再根据具体报错或官方文档添加”最小必要规则”。
#
# 注意：
# - 不要添加旧 ProGuard 教程中的 -dontpreverify、
#   -optimizationpasses、-verbose 等全局规则。
# - 不要为了”保险”而 keep 所有 Activity / Service / Receiver；
#   Android Gradle Plugin 会根据 Manifest 自动处理 Android 组件。
# ============================================================
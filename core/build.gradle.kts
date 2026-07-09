// core 模块 — 纯 Kotlin 代码 + JNI 桥接层
//
// 包含：
// - model/：纯数据类（RectF、FrameAnalysisResult 等）
// - util/：工具类（FeatureFlags、ThreadSafeQueue、ObjectPool）
// - data/native/：JNI 调用封装（NativeLibraryLoader、NativeJsonParser、NativeEngineFacade）
//
// 特点：
// - 不含 Android UI 代码（无 Activity/Fragment/View）
// - 可以被所有 feature 模块依赖
// - 可以做纯 Kotlin 单元测试（model/util 部分）

plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "top.azek431.hzzs.core"
    compileSdk = 37

    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
}

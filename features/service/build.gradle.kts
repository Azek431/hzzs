// features/service 模块 — 自动操作服务
//
// 包含：
// - AutoOperationService（项目唯一无障碍服务入口）
// - AutoActionQueue（旧版坐标动作兼容队列）
// - RuntimeActionQueue（实时视觉动作队列）
// - GestureInjector / RuntimeGestureInjector（手势注入）
//
// 特点：
// - 包含 Android Service 代码
// - 依赖 core 模块（model + util）
// - 被 app 模块依赖

plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "top.azek431.hzzs.features.service"
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
    implementation(project(":core"))

    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
}

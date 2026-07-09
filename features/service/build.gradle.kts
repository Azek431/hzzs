// features/service 模块 — 自动操作服务
//
// 包含：
// - AutoOperationService（无障碍服务入口）
// - AutoActionQueue（操作队列管理）
// - GestureInjector（手势注入）
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

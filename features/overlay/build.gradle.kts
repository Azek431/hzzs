// features/overlay 模块 — 悬浮窗系统
//
// 包含：
// - OverlayPreviewManager（悬浮窗生命周期入口）
// - OverlayHUDRenderer（模拟帧生成 + JNI 调用）
// - HUDCanvasView（自定义 Canvas 绘制视图）
// - HUDDrawers（Canvas 绘制扩展函数）
// - HUDColorPalette（颜色常量）
// - OverlayWindowController（WindowManager 封装）
// - OverlayDragController（拖动逻辑）
// - OverlayResizeController（缩放逻辑）
// - OverlaySettingsBinder（设置绑定）
// - VisionDebugOverlayView（视觉调试叠加视图）
//
// 特点：
// - 包含 Android UI 代码（View、Canvas）
// - 依赖 core 模块（model + native + util）
// - 依赖 features/service 模块（AutoActionQueue）

plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "top.azek431.hzzs.ui.overlay"
    compileSdk = 37

    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":features:service"))

    implementation(libs.androidx.appcompat)
    implementation(libs.material)
}

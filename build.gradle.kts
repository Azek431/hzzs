// 顶层 build.gradle.kts
//
// 所有子模块共享的插件配置。
// 版本与 gradle/libs.versions.toml 中的 agp 版本保持一致。

plugins {
    id("com.android.application") version "9.2.0" apply false
    id("com.android.library") version "9.2.0" apply false
}

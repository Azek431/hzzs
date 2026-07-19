plugins {
    alias(libs.plugins.android.library)
}
android {
    namespace = "top.azek431.hzzs.nativevision"
    compileSdk = 37
    defaultConfig { minSdk = 24 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

android {
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt") } }
    defaultConfig {
        externalNativeBuild { cmake { cppFlags += listOf("-std=c++17", "-Wall", "-Wextra", "-Werror") } }
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
    }
}

// 签名配置：从环境变量读取 Release 签名信息
// 仅在四个环境变量全部非空时启用签名，否则使用 debug 签名
val releaseStoreFile = System.getenv("AZEK431_RELEASE_STORE_FILE").orEmpty()
val releaseStorePassword = System.getenv("AZEK431_RELEASE_STORE_PASSWORD").orEmpty()
val releaseKeyAlias = System.getenv("AZEK431_RELEASE_KEY_ALIAS").orEmpty()
val releaseKeyPassword = System.getenv("AZEK431_RELEASE_KEY_PASSWORD").orEmpty()

// 检查签名配置是否完整（四个字段都不能为空）
val releaseSigningConfigured = listOf(
    releaseStoreFile,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword
).all { it.isNotBlank() }

plugins {
    alias(libs.plugins.android.application)
}

android {
    // CMake 构建配置：指定 CMakeLists.txt 路径
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    // 应用包名（命名空间）
    namespace = "top.azek431.hzzs"

    // 编译 SDK 版本：37（Android 17）
    compileSdk = 37

    defaultConfig {
        // 应用 ID，与 namespace 一致
        applicationId = "top.azek431.hzzs"

        // 最低支持 Android 版本：24（Android 7.0）
        // 选择 API 24 的原因：覆盖 99%+ 的活跃设备，同时简化悬浮窗权限处理
        minSdk = 24

        // 目标 Android 版本
        targetSdk = 37

        // 版本号：0.1.0（早期开发阶段）
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // 签名配置：仅在环境变量全部提供时创建 release 签名配置
    signingConfigs {
    if (releaseSigningConfigured) {
        create("release") {
            storeFile = file(releaseStoreFile)
            storePassword = releaseStorePassword
            keyAlias = releaseKeyAlias
            keyPassword = releaseKeyPassword
            storeType = "PKCS12"

            // 本项目最低支持 Android 7.0（API 24），不需要 V1。
            enableV1Signing = false

            // Android 7.0+ 的基础 APK 签名方案。
            enableV2Signing = true

            // Android 9.0+ 的现代签名方案，并为未来签名轮换保留能力。
            enableV3Signing = true

            // 仅用于 Android 11+ 的 ADB 增量安装，不作为普通发布 APK 的默认配置。
            enableV4Signing = false
            }
        }
    }

    // 构建类型配置
    buildTypes {
        release {
            // 启用代码混淆和资源压缩
            isMinifyEnabled = true
            isShrinkResources = true

            // 如果签名配置完整，使用 release 签名
            if (releaseSigningConfigured) {
                signingConfig = signingConfigs.getByName("release")
            }

            // 使用 ProGuard 规则文件
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    // Java 编译选项：使用 Java 11 兼容性
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

// 依赖配置
dependencies {
    // AndroidX 核心库
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)

    // 测试依赖
    testImplementation(libs.junit)

    // Android 仪器测试依赖
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}

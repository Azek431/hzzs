val releaseStoreFile = System.getenv("AZEK431_RELEASE_STORE_FILE").orEmpty()
val releaseStorePassword = System.getenv("AZEK431_RELEASE_STORE_PASSWORD").orEmpty()
val releaseKeyAlias = System.getenv("AZEK431_RELEASE_KEY_ALIAS").orEmpty()
val releaseKeyPassword = System.getenv("AZEK431_RELEASE_KEY_PASSWORD").orEmpty()

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
    namespace = "top.azek431.hzzs"
    compileSdk = 37

    defaultConfig {
        applicationId = "top.azek431.hzzs"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

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

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true

            if (releaseSigningConfigured) {
                signingConfig = signingConfigs.getByName("release")
            }

            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)

    testImplementation(libs.junit)

    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}

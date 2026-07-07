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

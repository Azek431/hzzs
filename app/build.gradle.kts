plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.legacy.kapt)
    alias(libs.plugins.hilt)
}

android {
    namespace = "top.azek431.hzzs"
    compileSdk = 37

    defaultConfig {
        applicationId = "top.azek431.hzzs"
        minSdk = 24
        targetSdk = 37
        versionCode = 100
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
        buildConfigField("String", "GITEE_OWNER", "\"Azek431\"")
        buildConfigField("String", "GITEE_REPO", "\"hzzs\"")
        buildConfigField("String", "GITHUB_OWNER", "\"Azek431\"")
        buildConfigField("String", "GITHUB_REPO", "\"hzzs\"")
    }

    signingConfigs {
        create("release") {
            val storeFilePath = providers.environmentVariable("ANDROID_KEYSTORE_PATH").orNull
            if (!storeFilePath.isNullOrBlank()) storeFile = file(storeFilePath)
            storePassword = providers.environmentVariable("ANDROID_STORE_PASSWORD").orNull
            keyAlias = providers.environmentVariable("ANDROID_KEY_ALIAS").orNull
            keyPassword = providers.environmentVariable("ANDROID_KEY_PASSWORD").orNull
            storeType = "PKCS12"
        }
    }

    buildTypes {
        debug { applicationIdSuffix = ".debug"; versionNameSuffix = "-debug" }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    packaging { resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}") }
}

kapt { correctErrorTypes = true }

tasks.matching { it.name.contains("Release", ignoreCase = true) && it.name.contains("assemble", ignoreCase = true) }.configureEach {
    doFirst {
        val required = listOf("ANDROID_KEYSTORE_PATH", "ANDROID_STORE_PASSWORD", "ANDROID_KEY_ALIAS", "ANDROID_KEY_PASSWORD")
        val missing = required.filter { System.getenv(it).isNullOrBlank() }
        if (missing.isNotEmpty()) throw GradleException("Release signing configuration missing: ${missing.joinToString()}")
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:designsystem"))
    implementation(project(":core:preferences"))
    implementation(project(":core:update"))
    implementation(project(":feature:home"))
    implementation(project(":feature:runtime"))
    implementation(project(":feature:settings"))
    implementation(project(":feature:about"))
    implementation(project(":service:automation"))
    implementation(project(":service:capture"))
    implementation(project(":service:overlay"))
    implementation(project(":data:vision"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.hilt.android)
    kapt(libs.hilt.compiler)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    testImplementation(libs.junit)
}

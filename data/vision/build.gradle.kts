plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.legacy.kapt)
    alias(libs.plugins.hilt)
}
android {
    namespace = "top.azek431.hzzs.data.vision"
    compileSdk = 37
    defaultConfig { minSdk = 24 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
kapt { correctErrorTypes = true }

dependencies {
    implementation(project(":service:overlay"))
    implementation(project(":service:automation"))
    implementation(project(":service:capture"))
    implementation(project(":core:preferences"))
    implementation(project(":core:model"))
    implementation(project(":domain:vision"))
    implementation(project(":native:vision"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.hilt.android)
    kapt(libs.hilt.compiler)
}

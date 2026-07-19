plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.legacy.kapt)
    alias(libs.plugins.hilt)
}
android {
    namespace = "top.azek431.hzzs.core.preferences"
    compileSdk = 37
    defaultConfig { minSdk = 24 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kapt { correctErrorTypes = true }
dependencies {
    implementation(project(":core:model"))
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.hilt.android)
    kapt(libs.hilt.compiler)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

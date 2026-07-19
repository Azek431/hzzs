plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.legacy.kapt)
    alias(libs.plugins.hilt)
}
android {
    namespace = "top.azek431.hzzs.feature.runtime"
    compileSdk = 37
    defaultConfig { minSdk = 24 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
}
kapt { correctErrorTypes = true }
dependencies {
    implementation(project(":data:vision"))
implementation(project(":core:model"))
    implementation(project(":core:designsystem"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.hilt.android)
    kapt(libs.hilt.compiler)
    debugImplementation(libs.compose.ui.tooling)

    implementation(project(":core:preferences"))
    implementation(project(":domain:vision"))
    implementation(project(":service:capture"))
    implementation(project(":service:automation"))
    implementation(project(":service:overlay"))
}

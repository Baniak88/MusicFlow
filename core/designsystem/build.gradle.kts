plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "app.musicflow.core.designsystem"
    buildFeatures { compose = true }
}

dependencies {
    api(project(":core:model"))
    api(project(":core:common"))
    api(platform(libs.compose.bom))
    api(libs.compose.ui)
    api(libs.compose.ui.graphics)
    api(libs.compose.foundation)
    api(libs.compose.animation)
    api(libs.compose.material3)
    api(libs.compose.material.icons.extended)
    api(libs.compose.ui.tooling.preview)
    api(libs.coil.compose)
    api(libs.coil.network.okhttp)
    implementation(libs.androidx.palette)
    implementation(libs.androidx.core.ktx)
    debugImplementation(libs.compose.ui.tooling)
    testImplementation(libs.junit)
    testImplementation(libs.truth)
}

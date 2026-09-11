plugins {
    id("zales.android.library")
    id("zales.android.compose")
}

android {
    namespace = "io.github.nkvas1.zales.feature.home"
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:design"))
    implementation(project(":core:model"))
    implementation(project(":core:storage"))
    implementation(project(":core:voice"))
    implementation(project(":core:words"))
    implementation(project(":tunnel:api"))
    implementation(project(":tunnel:service"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)
}

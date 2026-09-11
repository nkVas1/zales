plugins {
    id("zales.android.library")
    id("zales.android.compose")
}

android {
    namespace = "io.github.nkvas1.zales.design"
}

dependencies {
    implementation(project(":core:common"))
    implementation(libs.androidx.core.ktx)
}

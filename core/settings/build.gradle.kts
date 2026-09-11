plugins {
    id("zales.android.library")
}

android {
    namespace = "io.github.nkvas1.zales.settings"
}

dependencies {
    implementation(project(":core:common"))
    implementation(libs.kotlinx.coroutines.android)
}

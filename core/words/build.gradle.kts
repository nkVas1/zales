plugins {
    id("zales.android.library")
}

android {
    namespace = "io.github.nkvas1.zales.words"
}

dependencies {
    api(project(":tunnel:api"))
    api(project(":parsing"))
    implementation(libs.androidx.core.ktx)
}

plugins {
    id("zales.android.library")
}

android {
    namespace = "io.github.nkvas1.zales.tunnel.service"

    buildFeatures {
        aidl = true
    }
}

dependencies {
    api(project(":tunnel:api"))
    api(project(":core:model"))
    implementation(project(":core:common"))
    implementation(project(":core:storage"))
    implementation(project(":parsing"))
    implementation(project(":tunnel:engine-xray"))
    implementation(project(":tunnel:xray-config"))
    implementation(libs.kotlinx.coroutines.android)
}

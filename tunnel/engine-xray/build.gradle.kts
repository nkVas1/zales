plugins {
    id("zales.android.library")
}

android {
    namespace = "io.github.nkvas1.zales.tunnel.xray.engine"
}

dependencies {
    api(project(":tunnel:api"))
    implementation(project(":tunnel:xray-config"))
    implementation(project(":core:common"))
    implementation(libs.zales.core)
    implementation(libs.kotlinx.serialization.json)
}

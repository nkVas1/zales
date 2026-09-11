plugins {
    id("zales.android.library")
}

android {
    namespace = "io.github.nkvas1.zales.tunnel.diagnostics"
}

dependencies {
    api(project(":tunnel:api"))
    api(project(":core:model"))
    implementation(project(":core:common"))
    implementation(project(":tunnel:autopilot"))
    implementation(project(":tunnel:xray-config"))
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

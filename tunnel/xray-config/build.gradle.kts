plugins {
    id("zales.jvm.library")
}

dependencies {
    api(project(":core:model"))
    api(project(":tunnel:api"))
    implementation(libs.kotlinx.serialization.json)
}

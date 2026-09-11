plugins {
    id("zales.jvm.library")
}

dependencies {
    api(project(":core:model"))
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.kotlinx.coroutines.core)
}

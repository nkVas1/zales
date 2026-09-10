plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.detekt)
}

detekt {
    parallel = true
    buildUponDefaultConfig = true
    config.setFrom(files("$rootDir/config/detekt/detekt.yml"))
    source.setFrom(files(subprojects.map { "${it.projectDir}/src" }))
}

dependencies {
    detektPlugins(libs.detekt.formatting)
}

plugins {
    id("zales.android.application")
    id("zales.android.compose")
}

android {
    namespace = "io.github.nkvas1.zales"

    defaultConfig {
        applicationId = "io.github.nkvas1.zales"
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        debug {
            // A debug build installs alongside the release one, so a broken
            // dev build can never take away a working tunnel.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:design"))
    implementation(project(":core:storage"))
    implementation(project(":core:voice"))
    implementation(project(":core:words"))
    implementation(project(":feature:home"))
    implementation(project(":feature:key"))
    implementation(project(":tunnel:api"))
    implementation(project(":tunnel:service"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
}

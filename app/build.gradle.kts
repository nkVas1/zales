plugins {
    id("zales.android.application")
    id("zales.android.compose")
}

android {
    namespace = "io.github.nkvas1.zales"

    defaultConfig {
        applicationId = "io.github.nkvas1.zales"
        versionCode = 4
        versionName = "0.2.2"
    }

    buildFeatures {
        buildConfig = true
    }

    // Xray-core and its userspace network stack are about 34 MB of compiled Go
    // per architecture, which is simply what this kind of client costs. Shipping
    // both in one file would make every download 76 MB for a person who needs
    // exactly one of them, so the release is split and the universal build is
    // kept only as a fallback for anyone unsure what their phone is.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = true
        }
    }

    // Signing details never live in the repository. CI writes the keystore to a
    // file and passes the rest through the environment; a local release build
    // without them simply comes out unsigned, which is the honest outcome.
    val keystore = System.getenv("ZALES_KEYSTORE")?.let(::file)?.takeIf { it.exists() }
    if (keystore != null) {
        signingConfigs {
            create("release") {
                storeFile = keystore
                storePassword = System.getenv("ZALES_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ZALES_KEY_ALIAS")
                keyPassword = System.getenv("ZALES_KEY_PASSWORD")
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = false
            }
        }
    }

    buildTypes {
        debug {
            // A debug build installs alongside the release one, so a broken
            // dev build can never take away a working tunnel.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            if (keystore != null) signingConfig = signingConfigs.getByName("release")
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
    implementation(project(":core:settings"))
    implementation(project(":feature:diagnostics"))
    implementation(project(":feature:home"))
    implementation(project(":feature:key"))
    implementation(project(":feature:settings"))
    implementation(project(":tunnel:api"))
    implementation(project(":tunnel:service"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
}

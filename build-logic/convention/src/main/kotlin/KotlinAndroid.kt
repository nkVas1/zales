// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

import com.android.build.api.dsl.CommonExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension

/**
 * Settings shared by every Android module in Zales, applied by the
 * `zales.android.*` convention plugins.
 *
 * SDK levels come from the version catalog so that a single line in
 * `gradle/libs.versions.toml` moves the whole project.
 *
 * Written in property style rather than the familiar `defaultConfig { }`
 * blocks: AGP 9 dropped the action-taking overloads from [CommonExtension].
 */
internal fun Project.configureKotlinAndroid(extension: CommonExtension) {
    extension.compileSdk = catalogVersion("compileSdk").toInt()
    extension.defaultConfig.minSdk = catalogVersion("minSdk").toInt()

    extension.compileOptions.sourceCompatibility = JavaVersion.VERSION_17
    extension.compileOptions.targetCompatibility = JavaVersion.VERSION_17

    // Unit tests run on a bare JVM, where android.util.Log throws by default.
    extension.testOptions.unitTests.isReturnDefaultValues = true

    extension.lint.warningsAsErrors = true
    extension.lint.abortOnError = true
    // AGP 9 always generates the lint reports; build/reports/lint has them.

    extensions.configure<KotlinAndroidProjectExtension> {
        jvmToolchain(JDK_VERSION)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
            allWarningsAsErrors.set(true)
        }
    }
}

private const val JDK_VERSION = 21

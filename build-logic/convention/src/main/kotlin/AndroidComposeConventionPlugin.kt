// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

import com.android.build.api.dsl.CommonExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.getByType

/**
 * Adds Compose to a module that already has `zales.android.application`
 * or `zales.android.library` applied.
 */
class AndroidComposeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("org.jetbrains.kotlin.plugin.compose")

        val extension = extensions.getByType<CommonExtension>()
        extension.buildFeatures.compose = true

        val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
        fun lib(alias: String) = libs.findLibrary(alias).orElseThrow {
            IllegalStateException("Library '$alias' is missing from gradle/libs.versions.toml")
        }

        dependencies {
            val bom = platform(lib("compose-bom"))
            add("implementation", bom)
            add("androidTestImplementation", bom)

            add("implementation", lib("compose-ui"))
            add("implementation", lib("compose-ui-graphics"))
            add("implementation", lib("compose-foundation"))
            add("implementation", lib("compose-ui-tooling-preview"))
            add("debugImplementation", lib("compose-ui-tooling"))
        }
    }
}

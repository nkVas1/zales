// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

import org.gradle.api.Project
import org.gradle.api.artifacts.MinimalExternalModuleDependency
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.provider.Provider
import org.gradle.kotlin.dsl.getByType

/** The single `libs` version catalog shared by the build and by build-logic. */
internal val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

/** Looks up a library alias in the catalog, failing loudly if it is missing. */
internal fun Project.catalogLibrary(alias: String): Provider<MinimalExternalModuleDependency> =
    libs.findLibrary(alias)
        .orElseThrow { IllegalStateException("Library '$alias' is missing from gradle/libs.versions.toml") }

/**
 * Reads a plain version string from the catalog.
 *
 * Fails loudly rather than falling back to a default: a missing SDK level
 * silently defaulting to something plausible is exactly the class of bug that
 * only shows up on a user's phone.
 */
internal fun Project.catalogVersion(alias: String): String =
    libs.findVersion(alias)
        .orElseThrow { IllegalStateException("Version '$alias' is missing from gradle/libs.versions.toml") }
        .requiredVersion

pluginManagement {
    includeBuild("build-logic")
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        google()
        mavenCentral()
        // zales-core.aar, built from native/ by native/build_core.py (docs/adr/0003).
        maven {
            name = "zalesNative"
            url = uri("native/prebuilt/maven")
            content { includeGroup("io.github.nkvas1.zales") }
        }
    }
}

rootProject.name = "zales"

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

// Modules are added phase by phase — see docs/ROADMAP.md.
// Phase 0 ships the shell only.
include(":app")
include(":core:common")
include(":core:design")
include(":core:model")
include(":core:storage")
include(":core:voice")
include(":core:words")
include(":feature:home")
include(":feature:key")
include(":parsing")
include(":tunnel:api")
include(":tunnel:xray-config")
include(":tunnel:autopilot")
include(":tunnel:engine-xray")
include(":tunnel:service")

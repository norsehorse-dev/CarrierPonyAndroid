pluginManagement {
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
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "CarrierPony"
include(":app")
include(":carrierponycore")

// PonyDirect (WAN direct transport) as a local composite build, so the app can
// consume the standalone library without a published artifact. F-Droid builds it
// from source via the same include.
includeBuild("../PonyDirect-Kotlin")

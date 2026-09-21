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

rootProject.name = "Apogee"

// :app -> :server -> :net -> :core, and nothing points back up.
// :core and :net are pure Kotlin/JVM with no Android dependencies so the
// identical simulation runs on a phone and on a headless dedicated server,
// and so physics is unit-testable in milliseconds instead of via installDebug.
include(":core")
include(":net")
include(":server")
include(":app")

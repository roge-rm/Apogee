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

// :app -> :server -> :net -> :core, and nothing points back up. :core and :net are pure Kotlin/JVM
// with no Android dependencies, so the same simulation runs on a phone and on a headless dedicated
// server, and physics can be unit-tested in milliseconds instead of through installDebug.
include(":core")
include(":net")
include(":server")
// The standalone server. :app doesn't use it. See its build file.
include(":dedicated")

// :app needs the Android SDK, and a server build has neither the SDK nor any use for it. The Docker
// image that builds :dedicated is a plain JDK container, and including :app there fails at
// *configuration* time, before any task runs, because the Android plugin looks for an SDK it will
// never find.
val hasAndroidSdk = file("local.properties").exists() ||
    System.getenv("ANDROID_HOME") != null ||
    System.getenv("ANDROID_SDK_ROOT") != null

if (hasAndroidSdk) {
    include(":app")
} else {
    logger.lifecycle("No Android SDK found - building server modules only, without :app.")
}

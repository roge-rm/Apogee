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
        // The browser build's toolchain (see the browser build). The Kotlin plugin would add
        // these itself, but project-level repositories are refused above, so they're declared
        // here, each limited to the one tool it serves; the plugin is told not to add its own in
        // the root build.gradle.kts.
        exclusiveContent {
            forRepository {
                ivy("https://nodejs.org/dist") {
                    name = "Node.js distributions"
                    patternLayout { artifact("v[revision]/[artifact](-v[revision]-[classifier]).[ext]") }
                    metadataSources { artifact() }
                }
            }
            filter { includeModule("org.nodejs", "node") }
        }
        exclusiveContent {
            forRepository {
                ivy("https://github.com/yarnpkg/yarn/releases/download") {
                    name = "Yarn distributions"
                    patternLayout { artifact("v[revision]/[artifact](-v[revision]).[ext]") }
                    metadataSources { artifact() }
                }
            }
            filter { includeModule("com.yarnpkg", "yarn") }
        }
        exclusiveContent {
            forRepository {
                ivy("https://github.com/WebAssembly/binaryen/releases/download") {
                    name = "Binaryen distributions"
                    patternLayout { artifact("version_[revision]/[module]-version_[revision]-[classifier].[ext]") }
                    metadataSources { artifact() }
                }
            }
            filter { includeModule("com.github.webassembly", "binaryen") }
        }
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
    include(":shared")
    include(":app")
} else {
    logger.lifecycle("No Android SDK found - building server modules only, without :app.")
}

// AGP 9 supplies Kotlin support itself, so there's no kotlin-android plugin here, only the separate
// Compose compiler plugin.
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Release signing comes from ../Keys/apogee-keystore.properties, beside the project rather than in
// it (the same layout as my other apps), so neither the keystore nor its passwords can ever be
// committed. Without that file, on a fresh clone say, the release build is just unsigned.
val signingProperties: Properties? = rootProject.file("../Keys/apogee-keystore.properties")
    .takeIf { it.exists() }
    ?.let { f -> Properties().apply { f.inputStream().use { load(it) } } }
val releaseStoreFile = signingProperties?.getProperty("storeFile")

android {
    namespace = "com.rm.apogee"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.rm.apogee"
        // Held at 27 on purpose. The API level gates none of the rendering we need (GLES 3.1/3.2
        // are a driver capability, queried at runtime), and the modern performance APIs can all be
        // reached behind SDK_INT checks (see platform/PerfHints.kt). The price is that we have to
        // keep a real low-end quality tier. See render/QualityTier.kt.
        minSdk = 27
        targetSdk = 37
        // Bumped when a build is worth keeping and telling apart from the last one, not on every
        // change. Note that this isn't what decides whether a client can join a server.
        // Protocol.VERSION and the part catalogue's content hash do that, and they move on their
        // own.
        versionCode = 38
        versionName = "0.8.12"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // The sound engine is native: phones, and the x86_64 emulator.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++17", "-O2", "-ffast-math")
                arguments += listOf("-DANDROID_STL=c++_shared")
            }
        }
    }

    ndkVersion = "28.2.13676358"

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "4.1.2"
        }
    }

    signingConfigs {
        if (releaseStoreFile != null) {
            create("release") {
                storeFile = file(releaseStoreFile)
                storePassword = signingProperties?.getProperty("storePassword")
                keyAlias = signingProperties?.getProperty("keyAlias")
                keyPassword = signingProperties?.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
            if (releaseStoreFile != null) {
                signingConfig = signingConfigs.getByName("release")
            }
            buildConfigField("boolean", "PERF", "false")
        }
        debug {
            buildConfigField("boolean", "PERF", "false")
        }
        // For timing a phone I can't reach with adb: the release build under its own name, so it
        // sits beside the real one without touching its saves, and signed with the debug key. On
        // its first start it puts the scene in src/perf/assets in place and logs its frame times
        // to a file in Download (see PerfKit).
        create("perf") {
            initWith(getByName("release"))
            applicationIdSuffix = ".perf"
            versionNameSuffix = "-perf"
            signingConfig = signingConfigs.getByName("debug")
            buildConfigField("boolean", "PERF", "true")
            matchingFallbacks += listOf("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
        // For BuildConfig.PERF, which sets up the perf build (see PerfKit).
        buildConfig = true
        // Oboe comes as a prefab package.
        prefab = true
    }
}

dependencies {
    implementation(project(":shared"))

    implementation(libs.oboe)
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}

/**
 * Drops the debug APK where I collect builds to install: `./gradlew :app:dropDebugApk`.
 *
 * That directory holds debug builds from several apps side by side, so this writes exactly one file
 * under a stable name that says it's Apogee, and clears out any older Apogee APK instead of piling
 * up versions.
 */
tasks.register("dropDebugApk") {
    group = "build"
    description = "Copies the debug APK to /srv/downloads/temp/debug/apogee-debug.apk"
    dependsOn("assembleDebug")

    val source = layout.buildDirectory.file("outputs/apk/debug/app-debug.apk")
    val destination = File("/srv/downloads/temp/debug/apogee-debug.apk")

    doLast {
        val apk = source.get().asFile
        require(apk.exists()) { "No debug APK at ${apk.absolutePath}" }
        destination.parentFile.mkdirs()

        // Only ever one Apogee build here.
        destination.parentFile.listFiles { file ->
            file.name.startsWith("apogee-") && file.name.endsWith(".apk") &&
                file.name != destination.name
        }?.forEach { stale ->
            logger.lifecycle("Removing stale build ${stale.name}")
            stale.delete()
        }

        apk.copyTo(destination, overwrite = true)
        logger.lifecycle("Debug APK -> ${destination.absolutePath} (${destination.length() / 1024} KB)")
    }
}

/**
 * Renders every sound in the game to WAV, on this machine, through the same synth the phone runs:
 * `./gradlew :app:soundGallery` -> a WAV per sound in app/build/sound-gallery, with each one's peak
 * and loudness printed. `-Praw` measures with the limiter off, for setting levels.
 */
tasks.register<Exec>("soundGallery") {
    group = "verification"
    description = "Renders every sound recipe to WAV files in build/sound-gallery"
    val out = layout.buildDirectory.dir("sound-gallery").get().asFile
    val cpp = file("src/main/cpp")
    val raw = project.hasProperty("raw")
    doFirst { out.mkdirs() }
    commandLine(
        "sh", "-c",
        "g++ -std=c++17 -O2 -ffast-math -o '${out}/gallery' '${cpp}/tools/sound_gallery.cpp' '${cpp}/synth/synth.cpp' " +
            "&& '${out}/gallery' '${out}'" + (if (raw) " raw" else ""),
    )
}

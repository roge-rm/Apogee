// AGP 9 supplies Kotlin support itself, so there is no kotlin-android plugin
// here - only the separate Compose compiler plugin.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.rm.apogee"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.rm.apogee"
        // Held at 27 deliberately. API level gates none of the rendering
        // capability we need (GLES 3.1/3.2 are a driver capability, queried at
        // runtime), and the modern performance APIs are all reachable behind
        // SDK_INT checks - see platform/PerfHints.kt. The price is that we own
        // a genuine low-end quality tier; see render/QualityTier.kt.
        minSdk = 27
        targetSdk = 37
        // Bumped when a build is worth keeping and telling apart from the
        // last one, not on every change. Note that this is not what decides
        // whether a client may join a server: Protocol.VERSION and the part
        // catalogue's content hash do that, and they move independently.
        versionCode = 17
        versionName = "0.5.0"

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

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
        // For BuildConfig.DEBUG, which gates the on-screen frame/sim timing overlay.
        buildConfig = true
        // Oboe arrives as a prefab package.
        prefab = true
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":net"))
    implementation(project(":server"))

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
 * Drops the debug APK where Dan collects builds to install:
 * `./gradlew :app:dropDebugApk`.
 *
 * That directory holds debug builds from several apps side by side, so this
 * writes exactly one file under a stable, app-identifying name and clears any
 * older Apogee APK rather than accumulating versions.
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
 * Renders every sound in the game to WAV, on this machine, through the same
 * synth the phone runs: `./gradlew :app:soundGallery` ->
 * a WAV per sound in app/build/sound-gallery, each one's peak and loudness printed.
 * `-Praw` measures with the limiter off, for setting levels.
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

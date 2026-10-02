// AGP 9 brings Kotlin support itself, so only the Compose compiler plugin is added here.
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Release signing comes from ../Keys/apogee-keystore.properties, outside the repo so the keys can't
// be committed. Without it the release build is unsigned.
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
        // Held at 27 on purpose. GLES 3.1/3.2 is queried at runtime and newer perf APIs sit behind
        // SDK_INT checks (platform/PerfHints.kt). It means keeping a low-end tier
        // (render/QualityTier.kt).
        minSdk = 27
        targetSdk = 37
        // Bumped for builds worth telling apart. Joining a server is decided by Protocol.VERSION
        // and the part catalogue's hash, which move on their own.
        versionCode = 40
        versionName = "0.8.14"

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
        // For timing a phone I can't reach with adb: release under its own id, so its saves are
        // separate, signed with the debug key. It seeds the scene in src/perf/assets and logs frame
        // times to Download (see PerfKit).
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
 * Copies the debug APK to where I collect builds: `./gradlew :app:dropDebugApk`. That folder is
 * shared with other apps, so it keeps one Apogee APK under a fixed name and deletes older ones.
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
 * Renders every sound to WAV on this machine with the phone's synth: `./gradlew :app:soundGallery`
 * writes app/build/sound-gallery and prints peak and loudness. `-Praw` turns the limiter off, for
 * setting levels.
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

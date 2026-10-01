import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

// What the Android app and the browser share: game session, renderer, sound and Compose screens.
// Platform pieces (GL, synth, settings storage) sit behind expect/actual. :app depends on this;
// the browser build is its wasmJs executable.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

// The version, read from :app's build file, so the browser shows the same one the app does.
val buildInfoDir = layout.buildDirectory.dir("generated/buildInfo")
val appBuild = rootProject.file("app/build.gradle.kts").readText()
val appVersionName = Regex("versionName = \"([^\"]+)\"").find(appBuild)!!.groupValues[1]
val appVersionCode = Regex("versionCode = (\\d+)").find(appBuild)!!.groupValues[1]
val generateBuildInfo by tasks.registering {
    // Copied into locals so doLast captures values, not the build script (configuration cache).
    val outDir = buildInfoDir
    val name = appVersionName
    val code = appVersionCode
    inputs.property("version", "$name ($code)")
    outputs.dir(outDir)
    doLast {
        val file = outDir.get().file("com/rm/apogee/BuildInfo.kt").asFile
        file.parentFile.mkdirs()
        file.writeText(
            "package com.rm.apogee\n\n" +
                "/** Generated from app/build.gradle.kts by :shared:generateBuildInfo. */\n" +
                "object BuildInfo {\n" +
                "    const val VERSION_NAME = \"$name\"\n" +
                "    const val VERSION_CODE = $code\n" +
                "}\n",
        )
    }
}

// The synth as WebAssembly (web/synth/build.sh), served next to the page with its worklet. It needs
// Emscripten (emsdk in ~/.local/share/emsdk, or $EMSDK). Without it, the page is built silent.
val synthOut = layout.buildDirectory.dir("web-synth")
val emsdk = File(System.getenv("EMSDK") ?: "${System.getProperty("user.home")}/.local/share/emsdk")
val buildSynth = tasks.register<Exec>("buildSynth") {
    inputs.dir(rootProject.file("app/src/main/cpp/synth"))
    inputs.dir(rootProject.file("web/synth"))
    outputs.dir(synthOut)
    val hasEmsdk = File(emsdk, "emsdk_env.sh").exists()
    onlyIf("Emscripten is installed") { hasEmsdk }
    commandLine(rootProject.file("web/synth/build.sh").absolutePath, synthOut.get().asFile.absolutePath)
}

kotlin {
    android {
        namespace = "com.rm.apogee.shared"
        compileSdk = 37
        minSdk = 27
        withHostTestBuilder {}
    }

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        outputModuleName = "apogee"
        browser {
            commonWebpackConfig { outputFileName = "apogee.js" }
        }
        binaries.executable()
    }

    sourceSets {
        commonMain {
            kotlin.srcDir(generateBuildInfo)
        }
        commonMain.dependencies {
            api(project(":core"))
            api(project(":net"))
            api(project(":server"))
            api(libs.jb.compose.runtime)
            api(libs.jb.compose.foundation)
            api(libs.jb.compose.ui)
            api(libs.jb.compose.material3)
            api(libs.jb.compose.icons.extended)
            api(libs.kotlinx.coroutines.core)
        }
        androidMain.dependencies {
            implementation(libs.androidx.core.ktx)
            implementation(libs.androidx.activity.compose)
            implementation(libs.kotlinx.coroutines.android)
        }
        getByName("androidHostTest").dependencies {
            implementation(libs.junit)
            implementation(libs.kotlinx.coroutines.test)
        }
        wasmJsMain.dependencies {
            implementation(libs.kotlinx.browser)
        }
        wasmJsMain {
            resources.srcDir(files(synthOut).builtBy(buildSynth))
        }
    }
}

// Binaryen's download repository is declared in settings.gradle.kts, which refuses plugin ones.
plugins.withType<org.jetbrains.kotlin.gradle.targets.wasm.binaryen.BinaryenPlugin> {
    the<org.jetbrains.kotlin.gradle.targets.wasm.binaryen.BinaryenEnvSpec>().downloadBaseUrl.set(null as String?)
}

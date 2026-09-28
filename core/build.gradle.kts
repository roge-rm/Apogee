import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The whole simulation, with NO Android dependency on purpose, so it runs unchanged inside the app,
// inside the dedicated server, inside a plain JUnit test, and in a browser. It's Kotlin
// Multiplatform for that last one: the code is in commonMain, the few things only the JVM has (files,
// threads) are behind small expect/actual pieces, and the tests run on the JVM.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

/**
 * commonMain's resources as Kotlin source, for the browser, which has no classpath to read them
 * from. Each file is a string, in pieces, since one constant that long is more than some tools like.
 */
val embedResources: TaskProvider<Task> = tasks.register("embedResources") {
    val from = file("src/commonMain/resources")
    val out = layout.buildDirectory.dir("generated/embeddedResources")
    inputs.dir(from)
    outputs.dir(out)
    doLast {
        fun quote(text: String) = buildString {
            append('"')
            for (c in text) when (c) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '$' -> append("\\$")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(c)
            }
            append('"')
        }
        val text = StringBuilder()
        text.append("package com.rm.apogee.core\n\n")
        text.append("// Generated from src/commonMain/resources by :core:embedResources.\n")
        text.append("internal val EMBEDDED_RESOURCES: Map<String, () -> String> = mapOf(\n")
        from.walkTopDown().filter { it.isFile }.sortedBy { it.path }.forEach { f ->
            val path = "/" + f.relativeTo(from).invariantSeparatorsPath
            val pieces = f.readText().chunked(16_000).joinToString(",\n        ") { quote(it) }
            text.append("    \"$path\" to { listOf(\n        $pieces,\n    ).joinToString(\"\") },\n")
        }
        text.append(")\n")
        val file = out.get().file("com/rm/apogee/core/EmbeddedResources.kt").asFile
        file.parentFile.mkdirs()
        file.writeText(text.toString())
    }
}

kotlin {
    jvm {
        // JVM 11 across every shared module so :app (compileOptions 11) can use them.
        compilerOptions { jvmTarget = JvmTarget.JVM_11 }
    }

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
    }

    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.coroutines.core)
        }
        jvmTest.dependencies {
            implementation(libs.junit)
            implementation(libs.kotlinx.coroutines.test)
        }
        wasmJsMain {
            // The browser has no classpath to read the part catalogue and the career tree from, so
            // they're compiled in.
            kotlin.srcDir(embedResources)
        }
    }
}

// Binaryen (wasm-opt) is set up per project; its download repository is declared in
// settings.gradle.kts, which refuses plugin-added ones.
plugins.withType<org.jetbrains.kotlin.gradle.targets.wasm.binaryen.BinaryenPlugin> {
    the<org.jetbrains.kotlin.gradle.targets.wasm.binaryen.BinaryenEnvSpec>().downloadBaseUrl.set(null as String?)
}

/** The JVM tests' runtime classpath, which the scenarios below run from. */
val jvmTestClasspath: FileCollection = files(
    kotlin.jvm().compilations.getByName("test").output.allOutputs,
    kotlin.jvm().compilations.getByName("test").runtimeDependencyFiles,
)

/**
 * Runs the headless ascent scenario: `./gradlew :core:flyAscent`.
 *
 * This is the physics loop. A full flight to orbit runs in well under a second here, compared with
 * minutes to rebuild, install and fly on a device.
 */
tasks.register<JavaExec>("flyAscent") {
    group = "verification"
    description = "Flies the stock rocket to orbit headlessly and prints telemetry."
    classpath = jvmTestClasspath
    mainClass.set("com.rm.apogee.core.scenario.AscentScenarioKt")
}

tasks.register<JavaExec>("padDiagnostic") {
    group = "verification"
    description = "Probe: behaviour of an unpowered craft resting on the launch pad."
    classpath = jvmTestClasspath
    mainClass.set("com.rm.apogee.core.scenario.PadDiagnosticKt")
}

tasks.register<JavaExec>("craftStats") {
    group = "verification"
    description = "Prints the stock rocket's stage analysis."
    classpath = jvmTestClasspath
    mainClass.set("com.rm.apogee.core.scenario.StatsKt")
}

/**
 * Measures simulation cost against vessel count: `./gradlew :core:tickBenchmark`.
 *
 * It answers the server-sizing question directly (how many craft fit in a 60Hz tick), which is the
 * only honest way to argue about what the server should be written in.
 */
tasks.register<JavaExec>("tickBenchmark") {
    group = "verification"
    description = "Measures simulation cost per tick against vessel count."
    classpath = jvmTestClasspath
    mainClass.set("com.rm.apogee.core.scenario.TickBenchmarkKt")
}

tasks.register<JavaExec>("terrainSurvey") {
    group = "verification"
    description = "Prints the statistics of the generated terrain."
    classpath = jvmTestClasspath
    mainClass.set("com.rm.apogee.core.scenario.TerrainSurveyKt")
}

/**
 * Prints the test runtime classpath, so a scenario can be run without Gradle buffering its output.
 * Otherwise a long benchmark killed mid-run reports nothing at all.
 */
tasks.register("printTestClasspath") {
    val cp = jvmTestClasspath
    doLast { println(cp.asPath) }
}

/**
 * Draws the terrain as shaded maps: `./gradlew :core:terrainAtlas`.
 *
 * PNGs in build/terrain-atlas, with material colour, hillshade and water, at regional and local
 * scales around the launch sites. It's for judging what the generator makes without a device in the
 * loop.
 */
tasks.register<JavaExec>("terrainAtlas") {
    group = "verification"
    description = "Writes shaded terrain maps to build/terrain-atlas."
    classpath = jvmTestClasspath
    mainClass.set("com.rm.apogee.core.scenario.TerrainAtlasKt")
    args = listOf(layout.buildDirectory.dir("terrain-atlas").get().asFile.absolutePath)
}

/**
 * Draws the Cape from above, with what stands where: `./gradlew :core:capeMap`. PNGs in
 * build/cape-map.
 */
tasks.register<JavaExec>("capeMap") {
    group = "verification"
    description = "Writes maps of the Cape's spaceport, airfield and harbour to build/cape-map."
    classpath = jvmTestClasspath
    mainClass.set("com.rm.apogee.core.scenario.CapeMapKt")
    args = listOf(layout.buildDirectory.dir("cape-map").get().asFile.absolutePath)
}

tasks.register<JavaExec>("restSurvey") {
    group = "verification"
    description = "Prints how still each reference craft settles."
    classpath = jvmTestClasspath
    mainClass.set("com.rm.apogee.core.scenario.RestSurveyKt")
}



// The tests run on the JVM, as jvmTest; `test` is kept as the name for them, as before it was
// multiplatform.
tasks.register("test") {
    group = "verification"
    description = "Runs the tests (on the JVM)."
    dependsOn("jvmTest")
}

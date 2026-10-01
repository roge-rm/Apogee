import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The whole simulation, with no Android dependency, so it runs in the app, the server, JUnit and a
// browser. JVM-only bits (files, threads) are behind expect/actual. Tests run on the JVM.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

/**
 * commonMain's resources as Kotlin source, since the browser has no classpath. Each file is split
 * into pieces because some tools choke on one huge string constant.
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
            // No classpath in the browser, so the resources are compiled in.
            kotlin.srcDir(embedResources)
        }
    }
}

// Binaryen's download repository is declared in settings.gradle.kts, which refuses plugin-added ones.
plugins.withType<org.jetbrains.kotlin.gradle.targets.wasm.binaryen.BinaryenPlugin> {
    the<org.jetbrains.kotlin.gradle.targets.wasm.binaryen.BinaryenEnvSpec>().downloadBaseUrl.set(null as String?)
}

/** The JVM tests' runtime classpath, which the scenarios below run from. */
val jvmTestClasspath: FileCollection = files(
    kotlin.jvm().compilations.getByName("test").output.allOutputs,
    kotlin.jvm().compilations.getByName("test").runtimeDependencyFiles,
)

/** Flies the headless ascent scenario to orbit in under a second: `./gradlew :core:flyAscent`. */
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

/** Measures how many craft fit in a 60 Hz tick: `./gradlew :core:tickBenchmark`. */
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

/** Prints the test classpath, to run a scenario without Gradle buffering its output. */
tasks.register("printTestClasspath") {
    val cp = jvmTestClasspath
    doLast { println(cp.asPath) }
}

/**
 * Draws shaded terrain maps around the launch sites to build/terrain-atlas:
 * `./gradlew :core:terrainAtlas`.
 */
tasks.register<JavaExec>("terrainAtlas") {
    group = "verification"
    description = "Writes shaded terrain maps to build/terrain-atlas."
    classpath = jvmTestClasspath
    mainClass.set("com.rm.apogee.core.scenario.TerrainAtlasKt")
    args = listOf(layout.buildDirectory.dir("terrain-atlas").get().asFile.absolutePath)
}

/** Draws the Cape from above to build/cape-map: `./gradlew :core:capeMap`. */
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



// `test` runs jvmTest.
tasks.register("test") {
    group = "verification"
    description = "Runs the tests (on the JVM)."
    dependsOn("jvmTest")
}

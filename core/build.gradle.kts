import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Pure Kotlin/JVM. It has NO Android dependency on purpose, because this module is the whole
// simulation, and it has to run unchanged inside the app, inside the dedicated server, and inside a
// plain JUnit test.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    // JVM 11 across every shared module so :app (compileOptions 11) can use them.
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
    }
}

dependencies {
    api(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

/**
 * Runs the headless ascent scenario: `./gradlew :core:flyAscent`.
 *
 * This is the physics loop. A full flight to orbit runs in well under a second here, compared with
 * minutes to rebuild, install and fly on a device.
 */
tasks.register<JavaExec>("flyAscent") {
    group = "verification"
    description = "Flies the stock rocket to orbit headlessly and prints telemetry."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.rm.apogee.core.scenario.AscentScenarioKt")
}

tasks.register<JavaExec>("padDiagnostic") {
    group = "verification"
    description = "Probe: behaviour of an unpowered craft resting on the launch pad."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.rm.apogee.core.scenario.PadDiagnosticKt")
}

tasks.register<JavaExec>("craftStats") {
    group = "verification"
    description = "Prints the stock rocket's stage analysis."
    classpath = sourceSets["test"].runtimeClasspath
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
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.rm.apogee.core.scenario.TickBenchmarkKt")
}

tasks.register<JavaExec>("terrainSurvey") {
    group = "verification"
    description = "Prints the statistics of the generated terrain."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.rm.apogee.core.scenario.TerrainSurveyKt")
}

/**
 * Prints the test runtime classpath, so a scenario can be run without Gradle buffering its output.
 * Otherwise a long benchmark killed mid-run reports nothing at all.
 */
tasks.register("printTestClasspath") {
    val cp = sourceSets["test"].runtimeClasspath
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
    classpath = sourceSets["test"].runtimeClasspath
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
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.rm.apogee.core.scenario.CapeMapKt")
    args = listOf(layout.buildDirectory.dir("cape-map").get().asFile.absolutePath)
}

tasks.register<JavaExec>("restSurvey") {
    group = "verification"
    description = "Prints how still each reference craft settles."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.rm.apogee.core.scenario.RestSurveyKt")
}



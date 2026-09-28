import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The game server, the same one whether a phone hosts, a dedicated server runs it, or a browser
// plays alone against its own copy. It's all common code: the sockets it's reached through are
// :net's.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvm {
        compilerOptions { jvmTarget = JvmTarget.JVM_11 }
    }

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":net"))
            implementation(libs.kotlinx.coroutines.core)
        }
        jvmTest.dependencies {
            implementation(libs.junit)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

plugins.withType<org.jetbrains.kotlin.gradle.targets.wasm.binaryen.BinaryenPlugin> {
    the<org.jetbrains.kotlin.gradle.targets.wasm.binaryen.BinaryenEnvSpec>().downloadBaseUrl.set(null as String?)
}

/**
 * Connects to a running host as a real client: `./gradlew :server:netProbe`.
 *
 * It closes the loop no unit test can: a game hosted from a real device, joined from a real second
 * process, over a real socket.
 */
tasks.register<JavaExec>("netProbe") {
    group = "verification"
    description = "Joins a running Apogee host and reports what it sees."
    classpath = files(
        kotlin.jvm().compilations.getByName("test").output.allOutputs,
        kotlin.jvm().compilations.getByName("test").runtimeDependencyFiles,
    )
    mainClass.set("com.rm.apogee.server.NetProbeKt")
}

// The tests run on the JVM, as jvmTest; `test` is kept as the name for them, as before it was
// multiplatform.
tasks.register("test") {
    group = "verification"
    description = "Runs the tests (on the JVM)."
    dependsOn("jvmTest")
}

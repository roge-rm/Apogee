import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The game server, used by a hosting phone, a dedicated server and a solo browser. Sockets are :net's.
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

/** Joins a running host over a real socket as a client: `./gradlew :server:netProbe`. */
tasks.register<JavaExec>("netProbe") {
    group = "verification"
    description = "Joins a running Apogee host and reports what it sees."
    classpath = files(
        kotlin.jvm().compilations.getByName("test").output.allOutputs,
        kotlin.jvm().compilations.getByName("test").runtimeDependencyFiles,
    )
    mainClass.set("com.rm.apogee.server.NetProbeKt")
}

// `test` runs jvmTest.
tasks.register("test") {
    group = "verification"
    description = "Runs the tests (on the JVM)."
    dependsOn("jvmTest")
}

import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// How clients and servers talk, and a client's prediction of the craft it flies. The messages,
// the in-process transport and the prediction are common, for the browser too; real sockets and
// finding games on the network are the JVM's, in jvmMain.
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
            api(project(":core"))
            api(libs.kotlinx.serialization.protobuf)
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

// The tests run on the JVM, as jvmTest; `test` is kept as the name for them, as before it was
// multiplatform.
tasks.register("test") {
    group = "verification"
    description = "Runs the tests (on the JVM)."
    dependsOn("jvmTest")
}

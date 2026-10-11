import java.net.URI
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

// Apogee for Linux and Windows: the shared app in a window, with the world drawn by LWJGL off
// screen and the synth on miniaudio (native/).
//
//   ./gradlew :desktop:run            build the sound engine for this machine and start it
//   ./gradlew :desktop:debAmd64       apogee_<version>_amd64.deb, for Debian Trixie
//   ./gradlew :desktop:debArm64       apogee_<version>_arm64.deb
//   ./gradlew :desktop:appImageAmd64  apogee-<version>-x86_64.AppImage, most desktops and the Deck
//   ./gradlew :desktop:appImageArm64  apogee-<version>-aarch64.AppImage
//   ./gradlew :desktop:windowsX64     apogee-<version>-setup.exe and the portable zip
//
// Everything but :run and debAmd64 needs Docker, for the cross compilers.

kotlin { jvmToolchain(21) }

/** The version, read from app/build.gradle.kts where it's set. */
val appGradle = rootProject.file("app/build.gradle.kts").readText()
val versionName = Regex("versionName = \"([^\"]+)\"").find(appGradle)!!.groupValues[1]

val lwjglVersion = libs.versions.lwjgl.get()
val lwjglModules = listOf("lwjgl", "lwjgl-opengl", "lwjgl-glfw")

/** The app's jars for each platform. Skia's renderer and LWJGL's natives differ. */
val debAmd64Runtime: Configuration = configurations.create("debAmd64Runtime")
val debArm64Runtime: Configuration = configurations.create("debArm64Runtime")
val windowsX64Runtime: Configuration = configurations.create("windowsX64Runtime")

dependencies {
    implementation(project(":shared"))
    implementation(compose.desktop.common)
    // Dispatchers.Main on desktop is Swing's event thread.
    implementation(libs.kotlinx.coroutines.swing)
    runtimeOnly(compose.desktop.currentOs)
    val windows = System.getProperty("os.name").startsWith("Windows")
    val arm = System.getProperty("os.arch") == "aarch64"
    val natives = if (windows) "natives-windows" else if (arm) "natives-linux-arm64" else "natives-linux"
    for (module in lwjglModules) {
        runtimeOnly("org.lwjgl:$module:$lwjglVersion:$natives")
        debAmd64Runtime("org.lwjgl:$module:$lwjglVersion:natives-linux")
        debArm64Runtime("org.lwjgl:$module:$lwjglVersion:natives-linux-arm64")
        windowsX64Runtime("org.lwjgl:$module:$lwjglVersion:natives-windows")
    }
    debAmd64Runtime(compose.desktop.linux_x64)
    debArm64Runtime(compose.desktop.linux_arm64)
    windowsX64Runtime(compose.desktop.windows_x64)
    testImplementation(libs.junit)
}

for (runtime in listOf(debAmd64Runtime, debArm64Runtime, windowsX64Runtime)) {
    runtime.extendsFrom(configurations.implementation.get())
    runtime.isCanBeConsumed = false
    // Resolved the same way as the run classpath, so :shared gives its desktop jar.
    val from = configurations.runtimeClasspath.get().attributes
    runtime.attributes {
        for (key in from.keySet()) {
            @Suppress("UNCHECKED_CAST")
            attribute(key as Attribute<Any>, from.getAttribute(key)!!)
        }
    }
}

val nativeDir = layout.projectDirectory.dir("native")
val nativeOut = file("native")
fun nativeOutDir(name: String): Directory = layout.projectDirectory.dir(File(nativeOut, name).absolutePath)

/**
 * Use ccache if this machine has it. The build containers have their own (see their Dockerfiles),
 * each with its own cache folder mounted in, because their versions differ from this machine's.
 */
val ccache: Boolean = File("/usr/bin/ccache").canExecute()
val launcher = if (ccache) "-DCMAKE_C_COMPILER_LAUNCHER=ccache -DCMAKE_CXX_COMPILER_LAUNCHER=ccache" else ""
/** Mounts and settings for a build container: the build folder at the same path, and its compiler cache. */
fun containerArgs(image: String): String {
    val cache = File(System.getProperty("user.home"), ".cache/ccache-containers/$image")
    if (ccache) cache.mkdirs()
    return "-v '$nativeOut':'$nativeOut'" + if (ccache) " -v '$cache':/ccache -e CCACHE_DIR=/ccache" else ""
}

/** What the sound engine is built from, besides native/. */
val engineSources = listOf(
    rootProject.file("app/src/main/cpp/synth"),
    rootProject.file("app/src/main/cpp/jni.cpp"),
    rootProject.file("app/src/main/cpp/engine.h"),
)

val nativeBuild = nativeOutDir("build")

/** The sound engine for this machine: libapogee_audio.so. */
val buildEngine = tasks.register<Exec>("buildEngine") {
    inputs.files(engineSources)
    inputs.files(nativeDir.file("CMakeLists.txt"), nativeDir.file("engine_desktop.cpp"))
    outputs.dir(nativeBuild)
    workingDir = nativeDir.asFile
    val out = nativeBuild.asFile.path
    commandLine("sh", "-c", "cmake -S . -B '$out' -DCMAKE_BUILD_TYPE=Release $launcher >/dev/null && cmake --build '$out' -j8")
}

/** The sound engine for 64-bit ARM, cross-compiled in a Debian Trixie container (native/Dockerfile.arm64). */
val nativeArm64 = nativeOutDir("build-arm64")
val buildEngineArm64 = tasks.register<Exec>("buildEngineArm64") {
    inputs.files(engineSources)
    inputs.files(
        nativeDir.file("CMakeLists.txt"), nativeDir.file("engine_desktop.cpp"),
        nativeDir.file("aarch64-linux-gnu.cmake"), nativeDir.file("Dockerfile.arm64"),
    )
    outputs.dir(nativeArm64)
    val root = rootProject.projectDir.absolutePath
    workingDir = nativeDir.asFile
    commandLine(
        "sh", "-c",
        "docker build -q -t apogee-arm64-cross -f Dockerfile.arm64 . >/dev/null && " +
            "docker run --rm -u \$(id -u):\$(id -g) -v '$root':/src ${containerArgs("arm64-cross")} -w /src/desktop/native apogee-arm64-cross sh -c '" +
            "J=/usr/lib/jvm/java-21-openjdk-amd64/include; " +
            "cmake -S . -B ${nativeArm64.asFile.path} -DCMAKE_BUILD_TYPE=Release -DCMAKE_TOOLCHAIN_FILE=aarch64-linux-gnu.cmake $launcher \"-DJNI_INCLUDE_DIRS=\$J;\$J/linux\" >/dev/null && " +
            "cmake --build ${nativeArm64.asFile.path} -j8'",
    )
}

compose.desktop {
    application {
        mainClass = "com.rm.apogee.desktop.MainKt"
        jvmArgs += listOf("-Djava.library.path=${nativeBuild.asFile.absolutePath}", "--enable-native-access=ALL-UNNAMED")
    }
}
tasks.matching { it.name == "run" }.configureEach { dependsOn(buildEngine) }

/** The app's classpath, in build/run-classpath.txt, to start it without Gradle. */
val runClasspath = tasks.register("runClasspath") {
    dependsOn(buildEngine)
    val jar = tasks.jar.flatMap { it.archiveFile }
    val classpath: FileCollection = configurations.runtimeClasspath.get()
    val out = layout.buildDirectory.file("run-classpath.txt")
    inputs.files(jar, classpath)
    outputs.file(out)
    doLast {
        val files = listOf(jar.get().asFile) + classpath.files
        out.get().asFile.writeText(files.joinToString(File.pathSeparator))
    }
}

/**
 * Copies [artifacts] into [lib], named with their group, because Compose's
 * "runtime-saveable-desktop" is a stub with the same file name as AndroidX's jar, and without the
 * group one of them would be lost.
 */
fun copyJars(artifacts: Set<ResolvedArtifactResult>, lib: File, mode: String? = null) {
    lib.mkdirs()
    for (a in artifacts) {
        val id = a.id.componentIdentifier
        val name = if (id is org.gradle.api.artifacts.component.ModuleComponentIdentifier) "${id.group}-${a.file.name}" else a.file.name
        val to = File(lib, name)
        a.file.copyTo(to, overwrite = true)
        if (mode != null) Files.setPosixFilePermissions(to.toPath(), PosixFilePermissions.fromString(mode))
    }
}

/** Runs [command] and returns its output, failing the build with the output if it fails. */
fun runCommand(vararg command: String, env: Map<String, String> = emptyMap()): String {
    val process = ProcessBuilder(*command).redirectErrorStream(true).apply { environment().putAll(env) }.start()
    val said = process.inputStream.bufferedReader().readText()
    check(process.waitFor() == 0) { "${command.first().substringAfterLast('/')} failed: $said" }
    return said.trim()
}

// --- The Debian packages ------------------------------------------------------
//
// Laid out the usual Debian way for a Java program: jars in /usr/lib/apogee/lib, the sound engine
// in /usr/lib/apogee/native, a launcher in /usr/bin, a menu entry and an icon. Built with dpkg-deb
// from a staged tree, owned by root.

fun registerDeb(arch: String, runtime: Configuration, engine: TaskProvider<Exec>, engineDir: Directory) {
    val cap = arch.replaceFirstChar { it.uppercase() }
    val stage = layout.buildDirectory.dir("deb/$arch")
    val stageTask = tasks.register<Sync>("stageDeb$cap") {
        dependsOn(engine)
        into(stage)
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        // Debian's file modes. The Gradle cache keeps jars private to their owner, which would
        // make them root-only once installed.
        filePermissions { unix("rw-r--r--") }
        dirPermissions { unix("rwxr-xr-x") }
        from(tasks.named("jar")) { into("usr/lib/apogee/lib") }
        from(engineDir.file("libapogee_audio.so")) { into("usr/lib/apogee/native") }
        from(file("src/main/resources/apogee.png")) { into("usr/lib/apogee"); rename { "splash.png" } }
        from(file("deb/apogee")) {
            into("usr/bin")
            filePermissions { unix("rwxr-xr-x") }
        }
        from(file("deb/apogee.desktop")) { into("usr/share/applications") }
        from(rootProject.file("art/apogee-icon-1024.png")) {
            into("usr/share/icons/hicolor/1024x1024/apps")
            rename { "apogee.png" }
        }
        from(rootProject.file("LICENSE")) {
            into("usr/share/doc/apogee")
            rename { "copyright" }
        }
    }
    val deb = layout.buildDirectory.file("deb/apogee_${versionName}_$arch.deb")
    tasks.register("deb$cap") {
        group = "distribution"
        description = "Builds apogee_${versionName}_$arch.deb"
        notCompatibleWithConfigurationCache("uses the build script's helpers")
        dependsOn(stageTask)
        val root = stage.get().asFile
        val out = deb.get().asFile
        val template = file("deb/control")
        val version = versionName
        val artifacts = runtime.incoming.artifacts.resolvedArtifacts
        inputs.files(runtime)
        inputs.dir(stage)
        inputs.file(template)
        outputs.file(out)
        doLast {
            copyJars(artifacts.get(), File(root, "usr/lib/apogee/lib"), "rw-r--r--")
            Files.setPosixFilePermissions(root.toPath(), PosixFilePermissions.fromString("rwxr-xr-x"))
            val sizeKb = root.walkTopDown().filter { it.isFile && !it.path.contains("/DEBIAN/") }.sumOf { it.length() } / 1024
            val control = File(root, "DEBIAN/control")
            control.parentFile.mkdirs()
            control.writeText(
                template.readText().replace("@VERSION@", version).replace("@ARCH@", arch).replace("@SIZE@", sizeKb.toString()),
            )
            val result = ProcessBuilder("dpkg-deb", "--root-owner-group", "--build", root.path, out.path)
                .redirectErrorStream(true).start()
            val said = result.inputStream.bufferedReader().readText()
            check(result.waitFor() == 0) { "dpkg-deb failed: $said" }
            // Removed so it isn't staged in with the jars next time.
            control.parentFile.deleteRecursively()
        }
    }
}

registerDeb("amd64", debAmd64Runtime, buildEngine, nativeBuild)
registerDeb("arm64", debArm64Runtime, buildEngineArm64, nativeArm64)

// --- The AppImages --------------------------------------------------------------
//
// One file that runs on most Linux desktops: Ubuntu 22.04 and newer, Fedora, Arch, the Steam Deck.
// It carries what the Debian package gets from the system: a Java runtime (Eclipse Temurin) and a
// sound engine built on Ubuntu 22.04 (native/Dockerfile.appimage) with the C++ runtime linked in,
// so it only needs glibc 2.35. Downloads are pinned and checked against their SHA-256.

/** A download needed to build a package, fetched once into build/downloads. */
class Download(val url: String, val sha256: String) {
    val name: String get() = url.substringAfterLast('/')
}
val appImageTool = Download(
    "https://github.com/AppImage/appimagetool/releases/download/1.9.1/appimagetool-x86_64.AppImage",
    "ed4ce84f0d9caff66f50bcca6ff6f35aae54ce8135408b3fa33abfc3cb384eb0",
)
val downloads = layout.buildDirectory.dir("downloads")

fun fetch(d: Download, into: File): File {
    val file = File(into, d.name)
    fun sum(f: File) = MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }
    if (file.isFile && sum(file) == d.sha256) return file
    into.mkdirs()
    URI(d.url).toURL().openStream().use { input -> file.outputStream().use { input.copyTo(it) } }
    val got = sum(file)
    check(got == d.sha256) { "${d.name}: expected SHA-256 ${d.sha256}, got $got" }
    return file
}

fun registerAppImage(
    arch: String, appImageArch: String, runtime: Configuration,
    jre: Download, appImageRuntime: Download, cmakeArgs: String,
) {
    val cap = arch.replaceFirstChar { it.uppercase() }
    val engineDir = nativeOutDir("build-appimage-$arch")
    val engine = tasks.register<Exec>("buildEngineAppImage$cap") {
        inputs.files(engineSources)
        inputs.files(
            nativeDir.file("CMakeLists.txt"), nativeDir.file("engine_desktop.cpp"),
            nativeDir.file("aarch64-linux-gnu.cmake"), nativeDir.file("Dockerfile.appimage"),
        )
        outputs.dir(engineDir)
        val root = rootProject.projectDir.absolutePath
        workingDir = nativeDir.asFile
        commandLine(
            "sh", "-c",
            "docker build -q -t apogee-appimage -f Dockerfile.appimage . >/dev/null && " +
                "docker run --rm -u \$(id -u):\$(id -g) -v '$root':/src ${containerArgs("appimage")} -w /src/desktop/native apogee-appimage sh -c '" +
                "cmake -S . -B ${engineDir.asFile.path} -DCMAKE_BUILD_TYPE=Release $launcher " +
                "\"-DCMAKE_SHARED_LINKER_FLAGS=-static-libstdc++ -static-libgcc\" " +
                // FindJNI in 22.04's CMake wants AWT, which a headless JDK doesn't have.
                "\"-DJNI_INCLUDE_DIRS=/usr/lib/jvm/java-17-openjdk-amd64/include;/usr/lib/jvm/java-17-openjdk-amd64/include/linux\" " +
                "$cmakeArgs >/dev/null && " +
                "cmake --build ${engineDir.asFile.path} -j8'",
        )
    }
    val appDir = layout.buildDirectory.dir("appimage/$arch/Apogee.AppDir")
    val stage = tasks.register<Sync>("stageAppImage$cap") {
        dependsOn(engine)
        into(appDir)
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        from(tasks.named("jar")) { into("usr/lib/apogee/lib") }
        from(engineDir.file("libapogee_audio.so")) { into("usr/lib/apogee/native") }
        from(file("src/main/resources/apogee.png")) { into("usr/lib/apogee"); rename { "splash.png" } }
        from(file("appimage/AppRun")) { filePermissions { unix("rwxr-xr-x") } }
        from(file("deb/apogee.desktop"))
        from(file("src/main/resources/apogee.png"))
        from(rootProject.file("LICENSE")) { into("usr/share/doc/apogee") }
        // The runtime is unpacked here later and replaced each build. Pruning it deletes
        // java.base before the links into it, which fails the sync.
        preserve { include("usr/lib/apogee/jre/**") }
    }
    val out = layout.buildDirectory.file("appimage/apogee-$versionName-$appImageArch.AppImage")
    tasks.register("appImage$cap") {
        group = "distribution"
        description = "Builds apogee-$versionName-$appImageArch.AppImage"
        notCompatibleWithConfigurationCache("uses the build script's download and exec helpers")
        dependsOn(stage)
        val artifacts = runtime.incoming.artifacts.resolvedArtifacts
        val dir = appDir.get().asFile
        val cache = downloads.get().asFile
        val image = out.get().asFile
        inputs.files(runtime)
        inputs.dir(appDir)
        outputs.file(image)
        doLast {
            copyJars(artifacts.get(), File(dir, "usr/lib/apogee/lib"))
            // The Java runtime, unpacked under the name AppRun looks for.
            val jreDir = File(dir, "usr/lib/apogee/jre")
            jreDir.deleteRecursively()
            jreDir.mkdirs()
            runCommand("tar", "-xzf", fetch(jre, cache).path, "-C", jreDir.path, "--strip-components=1")
            val tool = fetch(appImageTool, cache).apply { setExecutable(true) }
            val runtimeFile = fetch(appImageRuntime, cache)
            image.delete()
            // Extract and run instead of mounting, so building doesn't need FUSE.
            runCommand(
                tool.path, "--no-appstream", "--runtime-file", runtimeFile.path, dir.path, image.path,
                env = mapOf("ARCH" to appImageArch, "APPIMAGE_EXTRACT_AND_RUN" to "1"),
            )
        }
    }
}

registerAppImage(
    "amd64", "x86_64", debAmd64Runtime,
    Download(
        "https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.12.1%2B1/OpenJDK21U-jre_x64_linux_hotspot_21.0.12.1_1.tar.gz",
        "2413149700df0f7d440500a84a8f764c535f21e5a5e87d38328b64eec2c5b500",
    ),
    Download(
        "https://github.com/AppImage/type2-runtime/releases/download/20251108/runtime-x86_64",
        "2fca8b443c92510f1483a883f60061ad09b46b978b2631c807cd873a47ec260d",
    ),
    "",
)
registerAppImage(
    "arm64", "aarch64", debArm64Runtime,
    Download(
        "https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.12.1%2B1/OpenJDK21U-jre_aarch64_linux_hotspot_21.0.12.1_1.tar.gz",
        "14be1f35ebdbd1f6e8d57eb911a3ffb74d6d9aa255abc5daf2b1302002cf2cf2",
    ),
    Download(
        "https://github.com/AppImage/type2-runtime/releases/download/20251108/runtime-aarch64",
        "00cbdfcf917cc6c0ff6d3347d59e0ca1f7f45a6df1a428a0d6d8a78664d87444",
    ),
    "-DCMAKE_TOOLCHAIN_FILE=aarch64-linux-gnu.cmake",
)

// --- Windows -------------------------------------------------------------------
//
// 64-bit Windows 10 and 11, cross-built here: the sound engine and launcher with MinGW-w64
// (native/Dockerfile.windows), the same jars as Linux but with Skia's and LWJGL's Windows natives,
// and Eclipse Temurin's Java runtime for Windows. As an installer and a portable zip.

val nativeWindows = nativeOutDir("build-windows")
val buildEngineWindows = tasks.register<Exec>("buildEngineWindows") {
    inputs.files(engineSources)
    inputs.files(
        nativeDir.file("CMakeLists.txt"), nativeDir.file("engine_desktop.cpp"),
        nativeDir.file("x86_64-w64-mingw32.cmake"), nativeDir.file("Dockerfile.windows"),
    )
    inputs.dir(layout.projectDirectory.dir("windows"))
    inputs.property("version", versionName)
    outputs.dir(nativeWindows)
    val root = rootProject.projectDir.absolutePath
    val version = versionName
    workingDir = nativeDir.asFile
    commandLine(
        "sh", "-c",
        "docker build -q -t apogee-windows -f Dockerfile.windows . >/dev/null && " +
            "docker run --rm -u \$(id -u):\$(id -g) -v '$root':/src ${containerArgs("windows")} -w /src/desktop/native apogee-windows sh -c '" +
            "J=/usr/lib/jvm/java-21-openjdk-amd64/include; " +
            "cmake -S . -B ${nativeWindows.asFile.path} -DCMAKE_BUILD_TYPE=Release -DCMAKE_TOOLCHAIN_FILE=x86_64-w64-mingw32.cmake $launcher " +
            "\"-DJNI_INCLUDE_DIRS=\$J;/src/desktop/native/win32\" -DAPOGEE_VERSION=$version >/dev/null && " +
            "cmake --build ${nativeWindows.asFile.path} -j8'",
    )
}

val windowsStage = layout.buildDirectory.dir("windows/Apogee")
val stageWindows = tasks.register<Sync>("stageWindowsX64") {
    dependsOn(buildEngineWindows)
    into(windowsStage)
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(nativeWindows.file("Apogee.exe"))
    from(nativeWindows.file("apogee_audio.dll")) { into("app") }
    from(tasks.named("jar")) { into("app/lib") }
    from(rootProject.file("LICENSE")) { rename { "LICENSE.txt" } }
}

tasks.register("windowsX64") {
    group = "distribution"
    description = "Builds apogee-$versionName-setup.exe and the portable zip for 64-bit Windows"
    notCompatibleWithConfigurationCache("uses the build script's download and exec helpers")
    dependsOn(stageWindows)
    val artifacts = windowsX64Runtime.incoming.artifacts.resolvedArtifacts
    val stage = windowsStage.get().asFile
    val out = layout.buildDirectory.dir("windows").get().asFile
    val cache = downloads.get().asFile
    val version = versionName
    val root = rootProject.projectDir.absolutePath
    val jre = Download(
        "https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.12.1%2B1/OpenJDK21U-jre_x64_windows_hotspot_21.0.12.1_1.zip",
        "d35f31e712f0fcf6ac5a093edc90204fbff22f720ba3950bd09d331d5e621636",
    )
    inputs.files(windowsX64Runtime)
    inputs.dir(windowsStage)
    outputs.files(File(out, "apogee-$version-setup.exe"), File(out, "apogee-$version-windows-x64.zip"))
    doLast {
        copyJars(artifacts.get(), File(stage, "app/lib"))
        val zip = fetch(jre, cache)
        val runtime = File(stage, "runtime")
        runtime.deleteRecursively()
        val unpacked = File(out, "jre-unpacked").apply { deleteRecursively(); mkdirs() }
        runCommand("unzip", "-q", zip.path, "-d", unpacked.path)
        unpacked.listFiles()!!.single().renameTo(runtime)
        unpacked.delete()
        val setup = "apogee-$version-setup.exe"
        val portable = "apogee-$version-windows-x64.zip"
        File(out, setup).delete()
        File(out, portable).delete()
        // The output folder at the same path inside the container.
        val inContainer = out.absolutePath
        runCommand(
            "docker", "run", "--rm", "-u", "${runCommand("id", "-u")}:${runCommand("id", "-g")}", "-v", "$root:/src", "-v", "$inContainer:$inContainer",
            "-w", "/src/desktop/windows", "apogee-windows", "sh", "-c",
            "makensis -V2 -DVERSION=$version -DSTAGE=$inContainer/Apogee -DOUT=$inContainer/$setup installer.nsi && " +
                "cd $inContainer && zip -qr $portable Apogee",
        )
    }
}

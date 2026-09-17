import org.gradle.internal.os.OperatingSystem
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.maven.publish)
}

group = rootProject.group
version = rootProject.version

// ==================== WASAPI GUIDs for the mingwX64 klib ====================
// wasapi_guids.c defines the Core Audio ids that the MinGW sysroot carries no
// import library for (see the file); the resulting archive is embedded into the
// wasapi cinterop below so that consumers of the klib can link.
//
// The MinGW cross-compiler is only used for that one file: on hosts without it
// the klib still builds, with the bindings only.
fun mingwTool(name: String, plainName: String? = null): String? {
    val candidates = buildList {
        System.getenv("PATH")?.split(File.pathSeparator).orEmpty().forEach {
            add(File(it, name))
            // On Windows the toolchain is native and has no triple prefix.
            if (OperatingSystem.current().isWindows && plainName != null) {
                add(File(it, plainName))
            }
        }
    }
    return candidates.firstOrNull { it.isFile && it.canExecute() }?.absolutePath
}

val mingwGcc = mingwTool("x86_64-w64-mingw32-gcc", "gcc")
val mingwAr = mingwTool("x86_64-w64-mingw32-ar", "ar")
val wasapiGuidsLibDir = layout.buildDirectory.dir("wasapi-guids")

val compileWasapiGuids = tasks.register<Exec>("compileWasapiGuids") {
    group = "build"
    description = "Compiles the WASAPI GUID definitions embedded into the mingwX64 cinterop."
    onlyIf { mingwGcc != null }
    val source = file("src/nativeInterop/cinterop/wasapi_guids.c")
    val objectFile = wasapiGuidsLibDir.get().asFile.resolve("wasapi_guids.o")
    inputs.file(source)
    outputs.file(objectFile)
    doFirst {
        wasapiGuidsLibDir.get().asFile.mkdirs()
        commandLine(mingwGcc!!, "-c", source.absolutePath, "-o", objectFile.absolutePath)
    }
}

val buildWasapiGuids = tasks.register<Exec>("buildWasapiGuids") {
    group = "build"
    description = "Archives the WASAPI GUID definitions embedded into the mingwX64 cinterop."
    onlyIf { mingwGcc != null && mingwAr != null }
    dependsOn(compileWasapiGuids)
    val archive = wasapiGuidsLibDir.get().asFile.resolve("libwasapi_guids.a")
    outputs.file(archive)
    doFirst {
        commandLine(
            mingwAr!!,
            "rcs",
            archive.absolutePath,
            wasapiGuidsLibDir.get().asFile.resolve("wasapi_guids.o").absolutePath,
        )
    }
}

kotlin {
    // ==================== JVM (desktop) ====================
    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
        }
        testRuns["test"].executionTask.configure {
            useJUnitPlatform()
        }
    }

    // ==================== Android (JVM) ====================
    android {
        namespace = "cn.enaium.audio"
        compileSdk = 36
        minSdk = 24

        withHostTest {}

        compilations.configureEach {
            compileTaskProvider.configure {
                compilerOptions {
                    jvmTarget.set(JvmTarget.JVM_21)
                }
            }
        }
    }

    // ==================== Native ====================
    macosArm64()
    macosX64()

    linuxX64()
    linuxArm64()

    mingwX64()

    androidNativeArm64()
    androidNativeArm32()
    androidNativeX64()
    androidNativeX86()

    // ==================== Per-target sources and cinterop ====================
    // There is no hierarchy template: every native target compiles the shared
    // src/nativeMain plus the directories of the OS family it belongs to.
    // Kotlin/Native resolves expect/actual declarations inside such a
    // compilation, which is what makes the shared directories possible without
    // intermediate source sets.
    targets.withType<KotlinNativeTarget> {
        val targetName = this.name

        compilations.getByName("main") {
            defaultSourceSet.kotlin.srcDir("src/nativeMain/kotlin")

            when {
                targetName.startsWith("macos") -> {
                    // Core Audio (AudioToolbox/CoreAudio) ships with the
                    // Kotlin/Native macOS platform libraries, so no cinterop is
                    // needed for this family.
                    defaultSourceSet.kotlin.srcDir("src/posixNativeMain/kotlin")
                    defaultSourceSet.kotlin.srcDir("src/macosMain/kotlin")
                }

                targetName.startsWith("linux") -> {
                    // ALSA has no bindings in the platform libraries: the
                    // function pointers are resolved from libasound with dlopen
                    // at runtime, using the vendored header for the types.
                    defaultSourceSet.kotlin.srcDir("src/posixNativeMain/kotlin")
                    defaultSourceSet.kotlin.srcDir("src/linuxMain/kotlin")
                    cinterops.create("alsa") {
                        defFile(project.file("src/nativeInterop/cinterop/alsa.def"))
                        includeDirs(project.file("src/nativeInterop/cinterop"))
                    }
                }

                targetName == "mingwX64" -> {
                    // WASAPI needs its own bindings: the MinGW platform
                    // libraries only cover the core Win32 APIs.
                    defaultSourceSet.kotlin.srcDir("src/mingwMain/kotlin")
                    cinterops.create("wasapi") {
                        defFile(project.file("src/nativeInterop/cinterop/wasapi.def"))
                        includeDirs(project.file("src/nativeInterop/cinterop"))
                        // The sysroot has no import library for the Core Audio
                        // ids, so the archive built from wasapi_guids.c rides
                        // along in the klib.
                        if (mingwGcc != null && mingwAr != null) {
                            extraOpts(
                                "-libraryPath", wasapiGuidsLibDir.get().asFile.absolutePath,
                                "-staticLibrary", "libwasapi_guids.a",
                            )
                        } else {
                            logger.warn(
                                "audio-io-kmp: no MinGW cross-compiler on PATH, the wasapi klib gets " +
                                    "no GUID definitions and consumers will fail to link",
                            )
                        }
                    }
                }

                else -> {
                    // androidNative: AAudio (API 26+) is resolved with dlopen
                    // as well, because the Kotlin/Native Android sysroot ships
                    // the stub libraries but no headers. The 16 KB page size
                    // flags that every consumer needs live in the def file.
                    defaultSourceSet.kotlin.srcDir("src/androidNativeMain/kotlin")
                    cinterops.create("aaudio") {
                        defFile(project.file("src/nativeInterop/cinterop/aaudio.def"))
                        includeDirs(project.file("src/nativeInterop/cinterop"))
                    }
                }
            }
        }
    }

    // The wasapi cinterop embeds the GUID archive, so it has to be built first.
    tasks.matching { it.name == "cinteropWasapiMingwX64" }.configureEach {
        dependsOn(buildWasapiGuids)
    }

    // ==================== Source sets ====================
    sourceSets {
        commonTest {
            dependencies {
                implementation(libs.kotlin.test)
            }
        }

        jvmTest {
            dependencies {
                implementation(libs.junit.jupiter)
                runtimeOnly(libs.junit.platform.launcher)
            }
        }
    }
}

// ==================== Publishing ====================
mavenPublishing {
    publishToMavenCentral(automaticRelease = true)
    signAllPublications()

    coordinates(
        groupId = group.toString(),
        artifactId = "audio-io-kmp",
        version = project.version.toString(),
    )

    pom {
        name.set("audio-io-kmp")
        description.set(
            "Kotlin Multiplatform audio I/O: configurable PCM capture and playback on the JVM " +
                    "(javax.sound), Android (AudioRecord/AudioTrack), Android Native (AAudio), " +
                    "Windows (WASAPI), Linux (ALSA) and macOS (Core Audio).",
        )
        url.set("https://github.com/Enaium/audio-io-kmp")
        inceptionYear.set("2026")

        licenses {
            license {
                name.set("MIT")
                url.set("https://opensource.org/license/mit")
                distribution.set("repo")
            }
        }

        developers {
            developer {
                id.set("Enaium")
            }
        }

        scm {
            url.set("https://github.com/Enaium/audio-io-kmp")
            connection.set("scm:git:git@github.com:Enaium/audio-io-kmp.git")
            developerConnection.set("scm:git:git@github.com:Enaium/audio-io-kmp.git")
        }

        issueManagement {
            system.set("GitHub")
            url.set("https://github.com/Enaium/audio-io-kmp/issues")
        }
    }
}

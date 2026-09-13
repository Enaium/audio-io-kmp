import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.maven.publish)
}

group = rootProject.group
version = rootProject.version

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

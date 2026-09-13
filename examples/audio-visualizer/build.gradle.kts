import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

// Kotlin/Native's own Android toolchain sysroot ships the NDK stub libraries
// (libEGL, libGLESv2, libaaudio, ...) that SDL3's Android drivers and the
// AAudio backend of audio-io-kmp reference at link time. Point -L at the
// per-ABI directory so the libmain.so link resolves them without needing an
// Android NDK installation.
fun konanAndroidLibDir(abi: String): String? {
    val konanData = System.getenv("KONAN_DATA_DIR")
        ?: providers.gradleProperty("konan.data.dir").getOrElse("${System.getProperty("user.home")}/.konan")
    val toolchain = File(konanData, "dependencies").listFiles()
        ?.firstOrNull { it.isDirectory && it.name.matches(Regex("target-toolchain-.*-android_ndk")) }
        ?: return null
    val triple = when (abi) {
        "arm64-v8a" -> "aarch64-linux-android"
        "armeabi-v7a" -> "arm-linux-androideabi"
        "x86_64" -> "x86_64-linux-android"
        "x86" -> "i686-linux-android"
        else -> return null
    }
    return "$toolchain/sysroot/usr/lib/$triple/26"
}

kotlin {
    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
        }
        mainRun {
            mainClass = "cn.enaium.audio.example.visualizer.Main_jvmKt"
        }
    }

    macosArm64 {
        binaries.executable()
    }

    linuxX64 {
        binaries.executable()
    }

    linuxArm64 {
        binaries.executable()
    }

    mingwX64 {
        binaries.executable()
    }

    // Android Native builds libmain.so with an exported SDL_main entry point;
    // the Android application module copies it into the APK and the SDL
    // activity calls it. The compiler-rt builtins embedded in the dependency
    // klibs overlap with Kotlin/Native's bundled libgcc on some ABIs (e.g.
    // __sync_* on armv7); allow duplicates so the first definition wins.
    androidNativeArm64 {
        binaries.sharedLib("main") {
            konanAndroidLibDir("arm64-v8a")?.let { linkerOpts("-L$it") }
            linkerOpts("-Wl,--allow-multiple-definition")
        }
    }
    androidNativeArm32 {
        binaries.sharedLib("main") {
            konanAndroidLibDir("armeabi-v7a")?.let { linkerOpts("-L$it") }
            linkerOpts("-Wl,--allow-multiple-definition")
        }
    }
    androidNativeX64 {
        binaries.sharedLib("main") {
            konanAndroidLibDir("x86_64")?.let { linkerOpts("-L$it") }
            linkerOpts("-Wl,--allow-multiple-definition")
        }
    }
    androidNativeX86 {
        binaries.sharedLib("main") {
            konanAndroidLibDir("x86")?.let { linkerOpts("-L$it") }
            linkerOpts("-Wl,--allow-multiple-definition")
        }
    }

    // The root build turns the automatic hierarchy template off (see
    // gradle.properties), so there is no `nativeMain`/`androidNativeMain`
    // source set: the shared directories, which hold the Kotlin/Native entry
    // points, are added to the matching compilations by hand. Without this the
    // Android targets would only look at src/androidNativeArm64Main and the
    // exported SDL_main symbol would be missing from libmain.so.
    targets.withType<KotlinNativeTarget> {
        val sources = compilations.getByName("main").defaultSourceSet.kotlin
        sources.srcDir("src/nativeMain/kotlin")
        if (name.startsWith("androidNative")) sources.srcDir("src/androidNativeMain/kotlin")
    }

    sourceSets {
        commonMain {
            dependencies {
                implementation(project(":audio-io-kmp"))
                implementation(libs.imgui.kmp)
                implementation(libs.sdl.kmp)
            }
        }
    }
}

// SDL3 (and every other windowing client of the JVM) must create its window on
// the first thread on macOS, otherwise video init fails with "No available
// video device". --enable-native-access silences the JVM warning of the native
// bindings the example draws through.
tasks.withType<JavaExec>().configureEach {
    if (org.gradle.internal.os.OperatingSystem.current().isMacOsX && name == "jvmRun") {
        jvmArgs("--enable-native-access=ALL-UNNAMED", "-XstartOnFirstThread")
    }
}

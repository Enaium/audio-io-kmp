import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.provider.Property
import org.jetbrains.kotlin.gradle.tasks.KotlinNativeLink

plugins {
    alias(libs.plugins.android.application)
}

// The example module links one libmain.so per Android ABI (with an exported
// SDL_main entry point); this module copies them into jniLibs and depends on
// the link tasks so the APK is always assembled from the current libraries.
val androidAbis = mapOf(
    "androidNativeArm64" to "arm64-v8a",
    "androidNativeArm32" to "armeabi-v7a",
    "androidNativeX64" to "x86_64",
    "androidNativeX86" to "x86",
)

val cxxSharedTriple = mapOf(
    "androidNativeArm64" to "aarch64-linux-android",
    "androidNativeArm32" to "arm-linux-androideabi",
    "androidNativeX64" to "x86_64-linux-android",
    "androidNativeX86" to "i686-linux-android",
)

/**
 * Copies the per-ABI `libmain.so` produced by the example module's
 * `linkMainDebugShared*` tasks into AGP's generated jniLibs directory, next to
 * the `libc++_shared.so` that Kotlin/Native's Android toolchain links against
 * (emulator images do not ship the shared C++ runtime themselves).
 */
abstract class PrepareJniLibsTask : DefaultTask() {

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Input
    abstract val abis: MapProperty<String, String>

    @get:Input
    abstract val cxxSharedTriples: MapProperty<String, String>

    /** `debug` or `release`: which Kotlin/Native binaries this variant packages. */
    @get:Input
    abstract val buildType: Property<String>

    @TaskAction
    fun run() {
        val bin = project.layout.projectDirectory.dir("../build/bin").asFile
        val binaries = "main${buildType.get().replaceFirstChar { it.uppercase() }}Shared"
        outputDir.get().asFile.deleteRecursively()

        /** `libc++_shared.so` from the Kotlin/Native Android toolchain, when present. */
        fun konanCxxShared(target: String): File? {
            val konanData = System.getenv("KONAN_DATA_DIR")
                ?: System.getProperty("user.home")?.let { File(it, ".konan").absolutePath }
                ?: return null
            val toolchain = File(konanData, "dependencies").listFiles()
                ?.firstOrNull { it.isDirectory && it.name.matches(Regex("target-toolchain-.*-android_ndk")) }
                ?: return null
            val triple = cxxSharedTriples.get()[target] ?: return null
            return File(toolchain, "sysroot/usr/lib/$triple/libc++_shared.so").takeIf { it.exists() }
        }

        /** `libc++_shared.so` from an installed NDK, used when the toolchain has none. */
        fun ndkCxxShared(target: String): File? {
            val sdkDir = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT") ?: return null
            val prebuiltDir = File(sdkDir, "ndk").listFiles()
                ?.filter { it.isDirectory && it.name.matches(Regex("\\d+(\\.\\d+)+")) }
                ?.maxByOrNull { it.name }
                ?.let { File(it, "toolchains/llvm/prebuilt") }
                ?: return null
            val host = when {
                System.getProperty("os.name").lowercase().contains("mac") -> "darwin-" + System.getProperty("os.arch")
                System.getProperty("os.name").lowercase().contains("linux") -> "linux-" + System.getProperty("os.arch")
                else -> null
            } ?: return null
            val triple = cxxSharedTriples.get()[target] ?: return null
            return File(prebuiltDir, "$host/sysroot/usr/lib/$triple/libc++_shared.so").takeIf { it.exists() }
        }

        abis.get().forEach { (target, abi) ->
            val source = File(bin, "$target/$binaries/libmain.so")
            if (!source.exists()) {
                throw GradleException("Expected $source - did the libmain.so link task fail?")
            }
            val destination = File(outputDir.get().asFile, abi)
            destination.mkdirs()
            source.copyTo(File(destination, "libmain.so"), overwrite = true)
            val cxxShared = konanCxxShared(target) ?: ndkCxxShared(target)
            if (cxxShared != null) {
                cxxShared.copyTo(File(destination, "libc++_shared.so"), overwrite = true)
            } else {
                logger.warn("No libc++_shared.so found for $abi; libmain.so may fail to load at runtime.")
            }
        }
    }
}

/**
 * Registers one jniLibs task per build type, wired to the matching
 * Kotlin/Native link tasks: a `release` APK packages the optimized
 * `mainReleaseShared/libmain.so`, a `debug` APK the unoptimized one.
 */
fun registerPrepareJniLibs(buildType: String): TaskProvider<PrepareJniLibsTask> {
    val capitalized = buildType.replaceFirstChar { it.uppercase() }
    val task = tasks.register<PrepareJniLibsTask>("prepareJniLibs$capitalized") {
        outputDir.set(layout.buildDirectory.dir("generated/jniLibs/$buildType"))
        abis.set(androidAbis)
        cxxSharedTriples.set(cxxSharedTriple)
        this.buildType.set(buildType)
        androidAbis.keys.forEach { target ->
            val linkTask = project(":examples:audio-visualizer").tasks.named(
                "linkMain${capitalized}Shared${target.replaceFirstChar { it.uppercase() }}",
            )
            dependsOn(linkTask)
            // Otherwise the task stays UP-TO-DATE after its first run and a
            // rebuilt libmain.so never reaches the APK.
            inputs.files(linkTask.flatMap { (it as KotlinNativeLink).outputFile })
        }
    }
    return task
}

val prepareDebugJniLibs = registerPrepareJniLibs("debug")
val prepareReleaseJniLibs = registerPrepareJniLibs("release")

android {
    namespace = "cn.enaium.audio.example.visualizer"
    compileSdk = 36

    defaultConfig {
        applicationId = "cn.enaium.audio.example.visualizer"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        ndk {
            abiFilters += androidAbis.values
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // The visualizer is an example, not a store release: the debug
            // signing key is used so a release build installs the same way a
            // debug one does, without provisioning a keystore.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs {
            // sdl-kmp's Android archive ships a per-ABI libsdl_jni.so for the
            // JVM bindings of SDL3. This app is native only - SDL3 and Dear
            // ImGui are already linked into libmain.so, and the activity loads
            // just that one library - so shipping it would only add a few
            // megabytes per ABI.
            excludes += "**/libsdl_jni.so"
        }
    }
}

// Hand the generated directory to AGP so the APK packaging picks up the
// libraries produced by the Kotlin/Native link tasks.
androidComponents {
    onVariants { variant ->
        val prepare = if (variant.buildType == "release") prepareReleaseJniLibs else prepareDebugJniLibs
        variant.sources.jniLibs?.addGeneratedSourceDirectory(prepare) { it.outputDir }
    }
}

dependencies {
    // The Android variant of sdl-kmp brings SDL3's Java layer
    // (org.libsdl.app.SDLActivity) into the APK; SDL3 itself is already
    // statically linked into libmain.so.
    implementation(libs.sdl.kmp)
}

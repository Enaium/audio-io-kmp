import org.gradle.internal.os.OperatingSystem
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

import org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsEnvSpec
import org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsPlugin

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

    // ==================== Web ====================
    // Both web runtimes drive the same Web Audio backend: the browser entry
    // point, the capture/playback streams and the format handling live in
    // src/webMain, only the JS interop declarations differ per runtime.
    js(IR) {
        browser()
        nodejs()
    }
    wasmJs {
        browser()
        nodejs()
    }

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
        // No hierarchy template on this project, so the intermediate web source
        // set that both web targets share is wired by hand.
        val webMain by creating {
            dependsOn(getByName("commonMain"))
        }
        val jsMain by getting {
            dependsOn(webMain)
        }
        val wasmJsMain by getting {
            dependsOn(webMain)
            dependencies {
                // Typed arrays (Float32Array) are part of the Kotlin/JS stdlib
                // but not of Kotlin/Wasm.
                implementation(libs.kotlinx.browser)
            }
        }

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
                    "Windows (WASAPI), Linux (ALSA), macOS (Core Audio) and the browser (Web Audio).",
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

// The Kotlin web toolchain wants to fetch its Node build from a repository this
// project does not resolve from. The Node on PATH (every CI image and developer
// machine has one) is used instead.
plugins.withType<NodeJsPlugin> {
    the<NodeJsEnvSpec>().download = false
}

// ==================== wasm/js: npm package ====================
// The web backends only declare the audioIo_* functions; the Web Audio graph
// and its ring buffers live in audio-io-kmp-web.js. Web pages need that file
// next to the Kotlin module, so it ships as an npm package
// (@enaium/audio-io-kmp) that the page loads before the module starts,
// instead of an asset inside a Maven jar.
val webNpmDir = layout.buildDirectory.dir("npm").get().asFile
val webGlue = project.file("src/webMain/resources/audio-io-kmp-web.js")
val webNpmVersion: String = version.toString()

tasks.register("assembleWebNpm") {
    group = "build"
    description = "Stages the @enaium/audio-io-kmp npm package in build/npm."
    inputs.file(webGlue)
    inputs.property("npmPackageVersion", webNpmVersion)
    outputs.dir(webNpmDir)
    doLast {
        webNpmDir.deleteRecursively()
        webNpmDir.mkdirs()
        webGlue.copyTo(webNpmDir.resolve("audio-io-kmp-web.js"), overwrite = true)
        webNpmDir.resolve("README.md").writeText(
            """
            # @enaium/audio-io-kmp

            Web Audio glue for the `audio-io-kmp` browser backend (the `js` and
            `wasmJs` targets). Load it before the Kotlin/Wasm or Kotlin/JS module;
            it defines the `audioIo_*` functions the backend calls.

            ```html
            <script src="audio-io-kmp-web.js"></script>
            ```

            or import it for its side effects from a bundler:

            ```js
            import "@enaium/audio-io-kmp";
            ```

            Capture and playback run on the page's thread, so the streams are
            polled: `read` returns what the capture callback buffered (0 when
            nothing yet) and `write` accepts what the playback ring has room for.
            Both directions keep a second of audio, so a slow frame neither drops
            captured frames nor starves the playback.

            https://github.com/Enaium/audio-io-kmp
            """.trimIndent() + "\n",
        )
        webNpmDir.resolve("package.json").writeText(
            """
            {
              "name": "@enaium/audio-io-kmp",
              "version": "$webNpmVersion",
              "description": "Web Audio glue for the audio-io-kmp browser backend",
              "license": "MIT",
              "main": "audio-io-kmp-web.js",
              "exports": {
                ".": "./audio-io-kmp-web.js",
                "./audio-io-kmp-web.js": "./audio-io-kmp-web.js",
                "./package.json": "./package.json"
              },
              "files": ["audio-io-kmp-web.js", "README.md"],
              "keywords": ["audio", "web-audio", "kotlin", "kotlin-wasm", "pcm"],
              "homepage": "https://github.com/Enaium/audio-io-kmp",
              "repository": {
                "type": "git",
                "url": "git+https://github.com/Enaium/audio-io-kmp.git"
              }
            }
            """.trimIndent() + "\n",
        )
    }
}

tasks.register<Exec>("publishWebNpm") {
    group = "publishing"
    description = "Publishes @enaium/audio-io-kmp to npm (token: npm.publish.key)."
    dependsOn("assembleWebNpm")
    workingDir = webNpmDir
    // The token lives in the user-level Gradle properties file
    // (~/.gradle/gradle.properties, next to mavenCentralUsername and the
    // signing.* entries); CI writes the same key there from a repository
    // secret. `-Pnpm.publish.key=<token>` overrides it for one-off runs.
    val npmKey = providers.gradleProperty("npm.publish.key")
    doFirst {
        val key = npmKey.orNull?.takeIf { it.isNotBlank() }
            ?: throw GradleException(
                "npm.publish.key is not set: add it to ~/.gradle/gradle.properties " +
                        "or pass -Pnpm.publish.key=<token>",
            )
        // NODE_AUTH_TOKEN is expanded by npm through the environment, so the
        // token never lands in the package.json/tarball; .npmrc is on npm's
        // never-publish list anyway.
        environment("NODE_AUTH_TOKEN", key)
        webNpmDir.resolve(".npmrc").writeText("//registry.npmjs.org/:_authToken=\${NODE_AUTH_TOKEN}\n")
    }
    commandLine("npm", "publish", "--access", "public", "--tag", "latest")
}

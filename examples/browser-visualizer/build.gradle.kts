import org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsEnvSpec
import org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsPlugin

plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    // The browser demo runs as Kotlin/Wasm: the same audio backend serves
    // Kotlin/JS, but a wasm module is what a modern page should load.
    wasmJs {
        browser()
        binaries.executable()
    }

    sourceSets {
        val wasmJsMain by getting {
            dependencies {
                implementation(project(":audio-io-kmp"))
                implementation(libs.kotlinx.browser)
            }
        }
    }
}

// ==================== The page ====================
// The page (index.html and the drawing glue) is assembled next to the wasm
// bundle rather than shipped as Kotlin resources: the webpack pipeline copies
// resources itself, which would collide with the finished page. The Web Audio
// glue of the library is copied in as well, so the page always has the
// implementation behind the backend's audioIo_* declarations.
val browserApp = tasks.register<Sync>("browserApp") {
    group = "application"
    description = "Assembles the browser page: wasm bundle, page glue and Web Audio glue."
    dependsOn("wasmJsBrowserDistribution")
    from(layout.buildDirectory.dir("dist/wasmJs/productionExecutable"))
    from(layout.projectDirectory.dir("web"))
    from(rootProject.file("audio-io-kmp/src/webMain/resources/audio-io-kmp-web.js"))
    into(layout.buildDirectory.dir("browserApp"))
}

// The Kotlin web toolchain wants to fetch its Node build from a repository this
// project does not resolve from. The Node on PATH (every CI image and developer
// machine has one) is used instead.
plugins.withType<NodeJsPlugin> {
    the<NodeJsEnvSpec>().download = false
}

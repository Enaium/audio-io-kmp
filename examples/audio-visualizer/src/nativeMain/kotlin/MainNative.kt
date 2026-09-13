@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

import cn.enaium.audio.example.visualizer.VisualizerApp
import kotlinx.cinterop.toKString
import platform.posix.getenv

/**
 * Native entry point (Kotlin/Native requires the executable entry in the
 * default package). Runs the visualizer until the window closes;
 * `AUDIO_VISUALIZER_FRAMES` limits the number of frames for headless CI runs.
 */
fun main() {
    val frames = getenv("AUDIO_VISUALIZER_FRAMES")?.toKString()?.toIntOrNull() ?: 0
    println("audio-io-kmp spectrum visualizer (frames=${if (frames <= 0) "unlimited" else frames})")
    VisualizerApp.run(frames)
}

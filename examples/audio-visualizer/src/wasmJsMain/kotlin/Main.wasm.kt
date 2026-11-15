package cn.enaium.audio.example.visualizer

import kotlinx.browser.window

/**
 * Browser entry point: the page loads the Emscripten SDL and imgui modules
 * before this module, then drives one frame per animation callback so the
 * canvas keeps painting while the audio backends are polled.
 */
fun main() {
    val session = VisualizerApp.start(0) ?: return
    fun tick() {
        if (!session.step()) {
            session.close()
            return
        }
        window.requestAnimationFrame { tick() }
    }
    window.requestAnimationFrame { tick() }
}

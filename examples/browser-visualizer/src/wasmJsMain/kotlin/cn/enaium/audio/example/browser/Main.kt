package cn.enaium.audio.example.browser

import kotlinx.browser.window

private val app = BrowserApp()

/**
 * Entry point of the page.
 *
 * The animation frame loop lives here rather than in JavaScript: the browser
 * hands Kotlin the frame timestamps, so nothing has to call back across the
 * language boundary. The buttons only set command bits that [BrowserApp.frame]
 * picks up, which also keeps the audio start right after the click that
 * unlocked the page.
 */
fun main() {
    webApp_setStatus("ready: press Start audio")
    webApp_start()
    var last = 0.0
    fun next(timestamp: Double) {
        val delta = if (last == 0.0) 16.0 else (timestamp - last).coerceIn(0.0, 250.0)
        last = timestamp
        app.frame(delta)
        window.requestAnimationFrame(::next)
    }
    window.requestAnimationFrame(::next)
}

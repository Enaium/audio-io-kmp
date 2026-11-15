package cn.enaium.audio.example.visualizer

import cn.enaium.audio.AudioSystem
import cn.enaium.audio.audioSystem
import cn.enaium.imgui.ImFontConfig
import cn.enaium.imgui.ImGui
import cn.enaium.imgui.ImGuiContext
import cn.enaium.imgui.backends.sdl.ImGuiSdlRendererBackend
import cn.enaium.imgui.backends.sdl.ImGuiSdlBackend
import cn.enaium.imgui.extensions.implot.ImPlot
import cn.enaium.imgui.extensions.implot.ImPlotContext
import cn.enaium.sdl.SDL
import cn.enaium.sdl.SDLColor
import cn.enaium.sdl.SDLEvent
import cn.enaium.sdl.SDLInitFlags
import cn.enaium.sdl.SDLRenderer
import cn.enaium.sdl.SDLWindow
import cn.enaium.sdl.SDLWindowEventType
import cn.enaium.sdl.SDLWindowFlags
import kotlin.time.TimeSource

/**
 * SDL3 + ImGui bootstrap and frame loop of the spectrum visualizer.
 *
 * The ImGui window drawn inside fills the whole viewport (see `VisualizerUi`),
 * so the SDL window is the frame the interface is drawn into.
 *
 * The audio backend is opened through the `audio-io-kmp` `audioSystem()` factory;
 * when that fails (headless runner without a sound card, no permission, ...) the
 * window still comes up and runs the requested number of frames with the error
 * shown in the UI.
 *
 * [start] opens everything and returns a [Session] whose frames the caller
 * drives, which is what the browser needs (its page loop must stay in charge).
 * [run] is the blocking loop of the desktop and native entry points.
 */
object VisualizerApp {

    /**
     * A running visualizer: [step] renders one frame, [close] releases
     * everything. Both are safe to call from the thread that opened it.
     */
    interface Session : AutoCloseable {

        /** Renders one frame; `false` once the frame limit or the window ends it. */
        fun step(): Boolean

        /** Releases the audio backend, ImGui, the window and SDL. Idempotent. */
        override fun close()
    }

    /**
     * Runs the example until [frames] frames were rendered (or always, when
     * [frames] is lower than one) or the window closes.
     *
     * [uiScale] multiplies the interface size: a display that reports its
     * window in physical pixels with a high pixel density (Android) passes its
     * display density here, every other platform keeps `1`.
     */
    fun run(frames: Int, uiScale: Float = 1f) {
        val session = start(frames, uiScale) ?: return
        try {
            while (session.step()) {
                // Frames are driven here so the same session serves the browser.
            }
        } finally {
            session.close()
        }
    }

    /**
     * Opens SDL, the window, ImGui and the audio backend.
     *
     * Returns `null` when not even the dummy video driver can be started; the
     * caller then has nothing to drive.
     */
    fun start(frames: Int, uiScale: Float = 1f): Session? {
        val limit = if (frames <= 0) Int.MAX_VALUE else frames

        SDL.setMainReady()
        // Fall back to the dummy video driver (headless CI runners, SSH
        // sessions) so SDL_Init itself never fails.
        if (!SDL.init(SDLInitFlags.VIDEO or SDLInitFlags.EVENTS)) {
            SDL.setHint("SDL_VIDEO_DRIVER", "dummy")
            if (SDL.init(SDLInitFlags.VIDEO or SDLInitFlags.EVENTS)) {
                println("video init fell back to the dummy driver — running headless")
            } else {
                error("SDL_Init failed: ${SDL.error()}")
            }
        }
        println("SDL ${SDL.version()} (${SDL.revision()})")
        println("Video driver: ${SDL.getCurrentVideoDriver()}")

        // An unavailable backend must not stop the demo: the UI degrades to the
        // "no capture device" state instead.
        val system: AudioSystem? = try {
            audioSystem()
        } catch (e: Exception) {
            println("audio backend unavailable: ${e.message ?: e.toString()}")
            null
        }
        val analyzer = SpectrumAnalyzer(system)

        val window = SDL.createWindow(
            title = "Audio Spectrum Visualizer",
            width = 1280,
            height = 800,
            flags = SDLWindowFlags.RESIZABLE or SDLWindowFlags.HIGH_PIXEL_DENSITY,
        )
        val renderer = SDL.createRenderer(window)
        val context = ImGui.createContext()
        return try {
            val imgui = ImGuiSdlBackend(window)
            val backend = ImGuiSdlRendererBackend(renderer)
            imgui.init()

            // On high-DPI displays the atlas is baked at
            // `sizePixels * rasterizerDensity` physical px while the
            // logical metrics stay `sizePixels`.
            val fonts = ImGui.getIO().fonts
            // Physical pixels per logical pixel. On a HiDPI desktop the
            // backend upscales every vertex by this factor; on Android
            // SDL hands the window out in physical pixels, so it is 1
            // and [uiScale] carries the display density instead.
            val density = maxOf(imgui.framebufferScale.x, imgui.framebufferScale.y, 1f)
            val scale = density * maxOf(uiScale, 0.5f)
            fonts.addFontDefault(
                ImFontConfig(
                    // Logical size; the atlas is rasterized at
                    // `sizePixels * rasterizerDensity` physical pixels.
                    sizePixels = 13f * scale,
                    // Never fold [uiScale] in here: that would rasterize
                    // the atlas at density squared (117 px glyphs on a
                    // 3x phone), which costs seconds of startup and a
                    // multi megapixel texture for no extra sharpness.
                    rasterizerDensity = density,
                ),
            )
            check(fonts.build()) { "font atlas build failed" }
            val texData = fonts.getTexDataAsRGBA32()
            fonts.setTexID(backend.uploadFontTexture(texData.pixels, texData.width, texData.height))

            val plotContext = ImPlot.createContext()
            ImPlot.setImGuiContext(ImGui.getCurrentContext() ?: error("no imgui context"))
            val ui = VisualizerUi(system, analyzer, renderer, backend, uiScale)
            ui.startup()
            SessionImpl(limit, ui, imgui, backend, renderer, window, analyzer, system, context, plotContext)
        } catch (e: Throwable) {
            runCatching { ImGui.destroyContext(context) }
            runCatching { analyzer.close() }
            runCatching { system?.close() }
            runCatching { renderer.close() }
            runCatching { window.close() }
            SDL.quit()
            throw e
        }
    }

    /** One running window: the frame loop state and everything it owns. */
    private class SessionImpl(
        private val limit: Int,
        private val ui: VisualizerUi,
        private val imgui: ImGuiSdlBackend,
        private val backend: ImGuiSdlRendererBackend,
        private val renderer: SDLRenderer,
        private val window: SDLWindow,
        private val analyzer: SpectrumAnalyzer,
        private val system: AudioSystem?,
        private val context: ImGuiContext,
        private val plotContext: ImPlotContext,
    ) : Session {

        private var running = true
        private var rendered = 0
        private var closed = false
        private val started = TimeSource.Monotonic.markNow()

        override fun step(): Boolean {
            if (closed || !running || rendered >= limit) return false

            // ---- events ----
            while (true) {
                val event = SDL.pollEvent() ?: break
                when (event) {
                    is SDLEvent.Quit -> running = false
                    is SDLEvent.Window ->
                        if (event.type == SDLWindowEventType.CLOSE_REQUESTED) running = false
                    else -> imgui.processEvent(event)
                }
            }
            if (!running) return false

            // ---- imgui frame ----
            imgui.newFrame()
            ui.draw(rendered)
            ImGui.render()

            renderer.drawColor = SDLColor(18, 18, 24, 255)
            renderer.clear()
            backend.renderDrawData(ImGui.getDrawData())
            renderer.present()
            rendered++
            return true
        }

        override fun close() {
            if (closed) return
            closed = true
            val elapsedMillis = started.elapsedNow().inWholeMilliseconds
            val rate = if (elapsedMillis > 0) rendered * 1000.0 / elapsedMillis else 0.0
            println("rendered $rendered frames in $elapsedMillis ms (${(rate * 10).toInt() / 10.0} fps)")
            runCatching { ui.close() }
            runCatching { ImPlot.destroyContext(plotContext) }
            runCatching { backend.close() }
            runCatching { analyzer.close() }
            runCatching { system?.close() }
            runCatching { ImGui.destroyContext(context) }
            runCatching { renderer.close() }
            runCatching { window.close() }
            SDL.quit()
        }
    }
}

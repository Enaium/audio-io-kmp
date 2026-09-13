package cn.enaium.audio.example.visualizer

import cn.enaium.audio.AudioSystem
import cn.enaium.audio.audioSystem
import cn.enaium.imgui.ImFontConfig
import cn.enaium.imgui.ImGui
import cn.enaium.imgui.backends.sdl.ImGuiSdlRendererBackend
import cn.enaium.imgui.backends.sdl.ImGuiSdlBackend
import cn.enaium.imgui.extensions.implot.ImPlot
import cn.enaium.sdl.SDL
import cn.enaium.sdl.SDLColor
import cn.enaium.sdl.SDLEvent
import cn.enaium.sdl.SDLInitFlags
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
 * [run] returns after [frames] rendered frames (or when the window is closed);
 * pass a limit lower than one for an endless interactive run.
 */
object VisualizerApp {

    /**
     * Runs the example. [frames] <= 0 renders until the window is closed.
     *
     * [uiScale] multiplies the interface size: a display that reports its
     * window in physical pixels with a high pixel density (Android) passes its
     * display density here, every other platform keeps `1`.
     */
    fun run(frames: Int, uiScale: Float = 1f) {
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

        SDL.createWindow(
            title = "Audio Spectrum Visualizer",
            width = 1280,
            height = 800,
            flags = SDLWindowFlags.RESIZABLE or SDLWindowFlags.HIGH_PIXEL_DENSITY,
        ).use { window ->
            SDL.createRenderer(window).use { renderer ->
                val context = ImGui.createContext()
                try {
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
                    try {
                        var running = true
                        var rendered = 0
                        val started = TimeSource.Monotonic.markNow()
                        while (running && rendered < limit) {
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

                            // ---- imgui frame ----
                            imgui.newFrame()
                            ui.draw(rendered)
                            ImGui.render()

                            renderer.drawColor = SDLColor(18, 18, 24, 255)
                            renderer.clear()
                            backend.renderDrawData(ImGui.getDrawData())
                            renderer.present()
                            rendered++
                        }
                        val elapsedMillis = started.elapsedNow().inWholeMilliseconds
                        val rate = if (elapsedMillis > 0) rendered * 1000.0 / elapsedMillis else 0.0
                        println("rendered $rendered frames in $elapsedMillis ms (${(rate * 10).toInt() / 10.0} fps)")
                    } finally {
                        ui.close()
                        ImPlot.destroyContext(plotContext)
                    }
                    backend.close()
                } finally {
                    analyzer.close()
                    runCatching { system?.close() }
                    ImGui.destroyContext(context)
                }
            }
        }
        SDL.quit()
    }
}

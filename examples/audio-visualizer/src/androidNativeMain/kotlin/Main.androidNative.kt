@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.experimental.ExperimentalNativeApi::class)

import cn.enaium.audio.example.visualizer.VisualizerApp
import cn.enaium.sdl.SDL
import kotlin.native.CName
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.toKString
import platform.posix.getenv

/**
 * Android entry point.
 *
 * The SDL activity of the APK loads `libmain.so` and calls its exported
 * `SDL_main` symbol on a dedicated SDL thread. SDL itself was already
 * initialized by the Java activity, so the example runs its frame loop until
 * the activity closes the window; the capture stream comes from AAudio through
 * the Android Native backend of `audio-io-kmp`, which needs the `RECORD_AUDIO`
 * permission the activity asks for before SDL starts.
 */
@CName("SDL_main")
fun sdlMain(argc: Int, argv: CPointer<CPointerVar<ByteVar>>?): Int {
    // The activity publishes the display density under this name so the
    // interface can scale itself to the screen; without it the text would be
    // desktop sized on a phone display.
    val density = getenv("AUDIO_VISUALIZER_DPI_SCALE")?.toKString()?.toFloatOrNull() ?: 1f
    VisualizerApp.run(frames = 0, uiScale = density)
    SDL.quit()
    return 0
}

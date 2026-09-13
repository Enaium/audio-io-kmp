package cn.enaium.audio.example.visualizer

import android.Manifest
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowInsets
import android.view.WindowInsetsController
import org.libsdl.app.SDLActivity

/**
 * Launcher activity hosting the spectrum visualizer.
 *
 * The activity is locked to landscape (the interface is a wide waveform,
 * spectrum and waterfall stack that needs the horizontal layout) and runs
 * immersive, so the plots get the whole screen.
 *
 * `SDLActivity` loads `libmain.so` and calls its exported `SDL_main` symbol on
 * a dedicated SDL thread; that library already contains SDL3, Dear ImGui and
 * the audio backend. The microphone permission is requested before SDL starts,
 * because the capture stream is opened from the native side.
 */
class MainActivity : SDLActivity() {

    override fun getLibraries(): Array<String> = arrayOf("main")

    private val recordAudioPermission: Int = 1

    private companion object {
        /** Environment variable the native interface reads its scale from. */
        const val DPI_SCALE_ENV = "AUDIO_VISUALIZER_DPI_SCALE"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // The native side bakes its font atlas and lays the interface out in
        // pixels, so it needs the display density: SDL reports the Android
        // window in physical pixels, which would render a phone screen with
        // desktop sized text. Set after super.onCreate, which is what loads
        // libmain.so and registers its JNI entry points, and before the SDL
        // thread starts in onResume.
        nativeSetenv(DPI_SCALE_ENV, resources.displayMetrics.density.toString())

        requestRecordAudioPermission()
    }

    override fun onResume() {
        super.onResume()
        // SDL sets the requested orientation itself; re-assert the landscape
        // lock after that so a device rotation never switches to portrait.
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        hideSystemBars()
    }

    override fun onConfigurationChanged(newConfiguration: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfiguration)
        // SDL asks for "user decides" (its orientation hint is empty) whenever
        // the surface changes, which on a phone means portrait. This interface
        // is a landscape one, so the lock is put back right after SDL had its
        // say.
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }

    /**
     * Keeps the activity in landscape.
     *
     * SDL requests the orientation itself through its command handler - with an
     * empty orientation hint it asks for "user decides", which on a phone means
     * portrait - and it does so after the activity callbacks, so a re-assert in
     * `onResume` is not enough. Every request is answered with the landscape
     * lock this interface is designed for.
     */
    override fun setRequestedOrientation(requestedOrientation: Int) {
        super.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Dialogs and the permission prompt clear the immersive flags; put them
        // back once the window has focus again.
        if (hasFocus) hideSystemBars()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // Nothing to do here: without the permission the visualizer opens its
        // window and shows the capture error instead of failing.
    }

    /** Asks for `RECORD_AUDIO` when it is missing and not permanently denied. */
    private fun requestRecordAudioPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) return
        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), recordAudioPermission)
    }

    /** Hides the status and navigation bars, re-asserted on every resume. */
    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val controller = window.insetsController
            if (controller != null) {
                controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
        // SDL's own fullscreen window style covers the API levels below 30.
        setWindowStyle(true)
    }
}

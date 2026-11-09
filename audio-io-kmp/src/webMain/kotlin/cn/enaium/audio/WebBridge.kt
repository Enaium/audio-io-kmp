package cn.enaium.audio

/**
 * JavaScript side of the browser backend.
 *
 * The functions map one to one onto the flat `audioIo_*` globals that
 * `audio-io-kmp-web.js` installs; that file owns the Web Audio graph and the
 * ring buffers, so both web runtimes only marshal frames across. Capture and
 * playback callbacks run on the page's thread, which is also where Kotlin
 * runs, so nothing here is re-entrant or has to be synchronised.
 */
internal expect object WebBridge {

    /** Number of devices the last enumeration found, inputs and outputs together. */
    fun deviceCount(): Int

    /** `0` for a capture device, `1` for a playback device. */
    fun deviceKind(index: Int): Int

    fun deviceId(index: Int): String

    fun deviceLabel(index: Int): String

    /** Device id the browser would pick for `0` (capture) or `1` (playback). */
    fun defaultDeviceId(kind: Int): String?

    /** Starts an asynchronous `enumerateDevices()`; [deviceCount] follows when it lands. */
    fun refreshDevices()

    /** Creates an `AudioContext` at [sampleRate]; `0` when the browser cannot. */
    fun createContext(sampleRate: Int): Int

    fun contextSampleRate(id: Int): Int

    /**
     * Opens the microphone into a ring inside the context. The permission
     * prompt is asynchronous; [captureStatus] reports when it resolved.
     */
    fun openCapture(id: Int, deviceId: String?, channels: Int, blockFrames: Int)

    /** Frames buffered by the capture callback. */
    fun captureAvailable(id: Int): Int

    /** Copies up to [maxFrames] interleaved frames into [destination]. */
    fun captureRead(id: Int, destination: FloatArray, maxFrames: Int): Int

    /** Opens the playback ring of the context; `0` when it is ready. */
    fun openPlayback(id: Int, deviceId: String?, channels: Int, blockFrames: Int): Int

    /** Frames the playback ring can still take. */
    fun playbackFree(id: Int): Int

    /** Copies up to [frames] interleaved frames of [source] into the playback ring. */
    fun playbackWrite(id: Int, source: FloatArray, frames: Int): Int

    /** Resumes the context; required once after creation by the autoplay policy. */
    fun start(id: Int)

    /** Suspends the context, keeping the streams configured. */
    fun suspend(id: Int)

    /** Releases the streams of the context and closes it. */
    fun close(id: Int)
}

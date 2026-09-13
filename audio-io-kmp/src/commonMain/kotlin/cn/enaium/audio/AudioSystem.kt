package cn.enaium.audio

/**
 * Entry point of a backend: device enumeration plus stream creation.
 *
 * One instance owns one backend. Nothing is shared between instances, so
 * several systems (for example ALSA and a virtual device) can coexist.
 *
 * ```kotlin
 * val system = audioSystem()
 * try {
 *     val input = system.openInput(AudioFormat.SPEECH)
 *     input.start()
 *     val buffer = AudioBuffer(input.format, input.bufferFrames)
 *     while (running) {
 *         val frames = input.read(buffer)
 *         if (frames < 0) break
 *         process(buffer.shorts())
 *     }
 *     input.close()
 * } finally {
 *     system.close()
 * }
 * ```
 */
interface AudioSystem : AutoCloseable {

    /** Backend name, for example `"JavaSound"` or `"Core Audio"`. */
    val name: String

    /** Every capture endpoint the backend knows about. */
    fun inputDevices(): List<AudioDevice>

    /** Every playback endpoint the backend knows about. */
    fun outputDevices(): List<AudioDevice>

    /** The endpoint the system records from by default, or `null` when there is none. */
    fun defaultInputDevice(): AudioDevice?

    /** The endpoint the system plays to by default, or `null` when there is none. */
    fun defaultOutputDevice(): AudioDevice?

    /**
     * Opens a capture stream.
     *
     * @param format layout of the PCM the stream will produce. Backends accept
     *   a format they can deliver natively and throw [AudioException]
     *   otherwise; `input.format` always reports what actually runs.
     * @param device endpoint from [inputDevices], or `null` for the default.
     * @param bufferFrames frames of internal buffering, `0` for the backend
     *   default. Smaller values lower latency, larger values survive scheduling
     *   hiccups.
     */
    fun openInput(format: AudioFormat, device: AudioDevice? = null, bufferFrames: Int = 0): AudioInput

    /**
     * Opens a playback stream.
     *
     * @param format layout of the PCM the stream expects.
     * @param device endpoint from [outputDevices], or `null` for the default.
     * @param bufferFrames frames of internal buffering, `0` for the backend
     *   default.
     */
    fun openOutput(format: AudioFormat, device: AudioDevice? = null, bufferFrames: Int = 0): AudioOutput

    /** Releases backend resources. Open streams must be closed by their owner first. */
    override fun close()
}

/**
 * The backend of the current platform: `javax.sound.sampled` on the JVM,
 * `AudioRecord`/`AudioTrack` on Android, AAudio on Android Native, WASAPI on
 * Windows, ALSA on Linux and Core Audio on macOS.
 *
 * The returned instance is not cached: each call opens a fresh backend, and the
 * caller owns and closes it.
 */
expect fun audioSystem(): AudioSystem

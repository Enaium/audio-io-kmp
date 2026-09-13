package cn.enaium.audio

/** Lifecycle state of an [AudioStream]. */
enum class AudioState {
    /** Created and configured, no audio is moving yet. */
    OPEN,

    /** The device is running. */
    STARTED,

    /** Stopped, but it can be started again. */
    STOPPED,

    /** Released. Every other operation now fails. */
    CLOSED,
}

/**
 * Common lifecycle of a capture or playback stream.
 *
 * ```
 * open    -> OPEN
 * start   -> STARTED    (data flows)
 * stop    -> STOPPED    (devices stay reserved, start() resumes)
 * close   -> CLOSED     (device released, close() is idempotent)
 * ```
 *
 * `stop()` and `close()` unblock any thread that is waiting inside
 * `read`/`write`, which then returns `-1` so a capture loop can end without a
 * separate signal.
 */
interface AudioStream : AutoCloseable {

    /** Layout the device was opened with. */
    val format: AudioFormat

    /** Endpoint the stream runs on, or `null` when the system default is used. */
    val device: AudioDevice?

    /**
     * Frames the backend buffers internally.
     *
     * For a capture stream this is the amount of audio the driver may hold
     * before it starts dropping frames; for playback it is how much can be
     * queued ahead of the speaker.
     */
    val bufferFrames: Int

    /** Current lifecycle state. */
    val state: AudioState

    /** `true` while the stream has not been closed. */
    val isOpen: Boolean get() = state != AudioState.CLOSED

    /** `true` while audio is flowing. */
    val isStarted: Boolean get() = state == AudioState.STARTED

    /** Starts the device. Calling it on a started stream does nothing. */
    fun start()

    /** Stops the device, keeping it open. Calling it on a stopped stream does nothing. */
    fun stop()

    /** Stops and releases the device. Idempotent. */
    override fun close()
}

/**
 * Capture stream: PCM comes out of the device.
 */
interface AudioInput : AudioStream {

    /**
     * Blocks until at least one frame was captured, then fills up to
     * `buffer.capacityFrames` frames into [buffer].
     *
     * @return frames written into [buffer], or `-1` when the stream was
     *   stopped or closed while waiting.
     */
    fun read(buffer: AudioBuffer): Int

    /**
     * Fills whatever the device has ready right now into [buffer].
     *
     * @return frames written, `0` when no frame was buffered.
     */
    fun readNonBlocking(buffer: AudioBuffer): Int

    /** Frames the device has buffered for the next [read]. */
    fun available(): Int
}

/**
 * Playback stream: PCM goes into the device.
 */
interface AudioOutput : AudioStream {

    /**
     * Blocks until the whole of [buffer] was handed to the device, draining it
     * in as few calls as the backend allows.
     *
     * @return frames consumed, or `-1` when the stream was stopped or closed
     *   while waiting.
     */
    fun write(buffer: AudioBuffer): Int

    /**
     * Hands as much of [buffer] to the device as fits right now.
     *
     * @return frames consumed, `0` when the device buffer is full.
     */
    fun writeNonBlocking(buffer: AudioBuffer): Int

    /** Frames that can be accepted by [writeNonBlocking] without blocking. */
    fun available(): Int
}

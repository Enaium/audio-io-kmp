package cn.enaium.audio

/**
 * Browser backend over the Web Audio API.
 *
 * The page drives the clock: `ScriptProcessorNode` delivers captured blocks and
 * asks for playback blocks while the animation frames run. Audio therefore
 * moves only while the page is executing JavaScript, so the streams cannot
 * block the way the native backends do — `read` returns the frames that are
 * buffered (0 when there are none) and `write` accepts what the playback ring
 * has room for. Both are meant to be polled, for example once per rendered
 * frame; [AudioInput.read] only returns `-1` once the stream was stopped.
 *
 * Frames cross the JavaScript boundary as interleaved floats and are converted
 * to and from the stream's PCM format on this side, so any sample format works.
 * The sample rate has to match the rate the browser runs its audio graph at,
 * which is what `AudioContext` is created with: a browser that cannot provide
 * the requested rate makes [openInput] / [openOutput] throw [AudioException].
 *
 * The page has to load `audio-io-kmp-web.js` before the module starts; without
 * it the `audioIo_*` globals the bridge calls do not exist.
 */
internal class WebAudioSystem : AudioSystem {

    override val name: String = "Web Audio"

    private var closed = false

    override fun inputDevices(): List<AudioDevice> = devices(INPUT_KIND, AudioDeviceType.INPUT)

    override fun outputDevices(): List<AudioDevice> = devices(OUTPUT_KIND, AudioDeviceType.OUTPUT)

    override fun defaultInputDevice(): AudioDevice? = inputDevices().firstOrNull { it.isDefault }

    override fun defaultOutputDevice(): AudioDevice? = outputDevices().firstOrNull { it.isDefault }

    override fun openInput(format: AudioFormat, device: AudioDevice?, bufferFrames: Int): AudioInput {
        check(!closed) { "audio system is closed" }
        val block = blockFrames(bufferFrames)
        val context = createContext(format)
        try {
            WebBridge.openCapture(context, device?.id, format.channelCount, block)
            return WebAudioInput(context, format, device, block)
        } catch (e: Throwable) {
            WebBridge.close(context)
            throw e
        }
    }

    override fun openOutput(format: AudioFormat, device: AudioDevice?, bufferFrames: Int): AudioOutput {
        check(!closed) { "audio system is closed" }
        val block = blockFrames(bufferFrames)
        val context = createContext(format)
        try {
            if (WebBridge.openPlayback(context, device?.id, format.channelCount, block) != 0) {
                throw AudioException("the browser cannot open a playback stream for $format")
            }
            return WebAudioOutput(context, format, device, block)
        } catch (e: Throwable) {
            WebBridge.close(context)
            throw e
        }
    }

    override fun close() {
        closed = true
    }

    private fun createContext(format: AudioFormat): Int {
        val context = WebBridge.createContext(format.sampleRate)
        if (context == 0) throw AudioException("this browser cannot create an AudioContext")
        val rate = WebBridge.contextSampleRate(context)
        if (rate != format.sampleRate) {
            WebBridge.close(context)
            throw AudioException("the browser audio graph runs at $rate Hz, not ${format.sampleRate} Hz")
        }
        return context
    }

    private fun devices(kind: Int, type: AudioDeviceType): List<AudioDevice> {
        WebBridge.refreshDevices()
        val default = WebBridge.defaultDeviceId(kind)
        val result = ArrayList<AudioDevice>()
        for (index in 0 until WebBridge.deviceCount()) {
            if (WebBridge.deviceKind(index) != kind) continue
            val id = WebBridge.deviceId(index)
            result += AudioDevice(
                id = id,
                name = WebBridge.deviceLabel(index),
                type = type,
                isDefault = default != null && id == default,
            )
        }
        return result
    }

    private companion object {
        const val INPUT_KIND = 0
        const val OUTPUT_KIND = 1

        /** Block size used when the caller does not ask for one. */
        const val DEFAULT_BLOCK_FRAMES = 1024
    }
}

/** Capture stream over the JavaScript ring of one `AudioContext`. */
internal class WebAudioInput(
    private val context: Int,
    override val format: AudioFormat,
    override val device: AudioDevice?,
    override val bufferFrames: Int,
) : AudioInput {

    override var state: AudioState = AudioState.OPEN
        private set

    private val channels = format.channelCount
    private val scratch = FloatArray(bufferFrames * channels)

    override fun start() {
        check(state != AudioState.CLOSED) { "stream is closed" }
        if (state == AudioState.STARTED) return
        WebBridge.start(context)
        state = AudioState.STARTED
    }

    override fun stop() {
        if (state != AudioState.STARTED) return
        state = AudioState.STOPPED
        WebBridge.suspend(context)
    }

    override fun read(buffer: AudioBuffer): Int {
        require(buffer.format == format) { "format mismatch: $format != ${buffer.format}" }
        if (state == AudioState.CLOSED) return -1
        val frames = WebBridge.captureRead(context, scratch, minOf(buffer.capacityFrames, bufferFrames))
        if (frames <= 0) {
            buffer.frameCount = 0
            // A stopped stream reads as ended; a started one simply has no
            // frames yet, the caller polls again on the next frame.
            return if (state == AudioState.STARTED) 0 else -1
        }
        val data = buffer.data
        val size = format.sampleFormat.bytesPerSample
        for (frame in 0 until frames) {
            for (channel in 0 until channels) {
                Pcm.putFloat(data, (frame * channels + channel) * size, format.sampleFormat, scratch[frame * channels + channel])
            }
        }
        buffer.frameCount = frames
        return frames
    }

    override fun readNonBlocking(buffer: AudioBuffer): Int = read(buffer)

    override fun available(): Int = WebBridge.captureAvailable(context)

    override fun close() {
        if (state == AudioState.CLOSED) return
        state = AudioState.CLOSED
        WebBridge.close(context)
    }
}

/** Playback stream over the JavaScript ring of one `AudioContext`. */
internal class WebAudioOutput(
    private val context: Int,
    override val format: AudioFormat,
    override val device: AudioDevice?,
    override val bufferFrames: Int,
) : AudioOutput {

    override var state: AudioState = AudioState.OPEN
        private set

    private val channels = format.channelCount
    private val floats = FloatArray(bufferFrames * channels)

    override fun start() {
        check(state != AudioState.CLOSED) { "stream is closed" }
        if (state == AudioState.STARTED) return
        WebBridge.start(context)
        state = AudioState.STARTED
    }

    override fun stop() {
        if (state != AudioState.STARTED) return
        state = AudioState.STOPPED
        WebBridge.suspend(context)
    }

    override fun write(buffer: AudioBuffer): Int {
        require(buffer.format == format) { "format mismatch: $format != ${buffer.format}" }
        if (state == AudioState.CLOSED) return -1
        val frames = minOf(buffer.frameCount, bufferFrames)
        val samples = buffer.toFloats(floats, frames * channels)
        return WebBridge.playbackWrite(context, floats, samples / channels)
    }

    override fun writeNonBlocking(buffer: AudioBuffer): Int = write(buffer)

    override fun available(): Int = WebBridge.playbackFree(context)

    override fun close() {
        if (state == AudioState.CLOSED) return
        state = AudioState.CLOSED
        WebBridge.close(context)
    }
}

/**
 * Rounds the requested buffering to the block sizes `ScriptProcessorNode`
 * accepts: a power of two between 256 and 16384 frames. The JavaScript glue
 * applies the same rule so both sides agree on the ring layout.
 */
internal fun blockFrames(requested: Int): Int {
    var wanted = if (requested > 0) requested else 1024
    if (wanted < 256) wanted = 256
    if (wanted > 16384) wanted = 16384
    var size = 256
    while (size * 2 <= wanted && size < 16384) size *= 2
    return size
}

actual fun audioSystem(): AudioSystem = WebAudioSystem()

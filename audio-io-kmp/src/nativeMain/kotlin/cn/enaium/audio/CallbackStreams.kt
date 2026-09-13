package cn.enaium.audio

import kotlinx.cinterop.ExperimentalForeignApi

/**
 * What a platform backend has to provide for a callback driven stream: the
 * device side of the lifecycle. The buffering, blocking and state machine live
 * in [CallbackAudioInput] / [CallbackAudioOutput] so that every backend behaves
 * the same way.
 */
internal interface DeviceControl {

    /** Hands the device to the driver. The callback may start firing at any time. */
    fun startDevice()

    /** Stops the driver. The callback must not fire afterwards. */
    fun stopDevice()

    /** Stops and releases the device. */
    fun closeDevice()

    /** Drops whatever the device still holds. */
    fun flushDevice()
}

/**
 * Capture stream over a device that pushes audio into a [PcmRing] from its own
 * callback thread.
 *
 * The callback fills the ring and signals; [read] takes frames out of it and
 * only blocks while the ring is empty, so the calling thread is never tied to
 * the callback thread.
 */
@OptIn(ExperimentalForeignApi::class)
internal class CallbackAudioInput(
    override val format: AudioFormat,
    override val device: AudioDevice?,
    override val bufferFrames: Int,
    private val ring: PcmRing,
    private val signal: RtSignal,
    private val control: DeviceControl,
) : AudioInput {

    override var state: AudioState = AudioState.OPEN
        private set

    override fun start() {
        check(state != AudioState.CLOSED) { "stream is closed" }
        if (state == AudioState.STARTED) return
        control.startDevice()
        state = AudioState.STARTED
    }

    override fun stop() {
        if (state != AudioState.STARTED) return
        state = AudioState.STOPPED
        control.stopDevice()
        control.flushDevice()
        signal.signal()
    }

    override fun read(buffer: AudioBuffer): Int {
        require(buffer.format == format) { "format mismatch: $format != ${buffer.format}" }
        while (true) {
            buffer.usePointer { pointer ->
                val frames = ring.read(pointer, buffer.capacityFrames)
                if (frames > 0) {
                    buffer.frameCount = frames
                    return frames
                }
            }
            if (state != AudioState.STARTED) {
                buffer.frameCount = 0
                return -1
            }
            signal.await()
        }
    }

    override fun readNonBlocking(buffer: AudioBuffer): Int {
        require(buffer.format == format) { "format mismatch: $format != ${buffer.format}" }
        return buffer.usePointer { pointer ->
            val frames = ring.read(pointer, buffer.capacityFrames)
            buffer.frameCount = frames
            frames
        }
    }

    override fun available(): Int = ring.availableToRead

    override fun close() {
        if (state == AudioState.CLOSED) return
        state = AudioState.CLOSED
        control.closeDevice()
        ring.close()
        signal.close()
    }
}

/**
 * Playback stream over a device that pulls audio out of a [PcmRing] in its own
 * callback thread.
 *
 * [write] blocks until the frames reached the ring; the callback drains it and
 * signals. If the callback runs out of data it plays silence and counts an
 * underrun, which `underruns` reports.
 */
@OptIn(ExperimentalForeignApi::class)
internal class CallbackAudioOutput(
    override val format: AudioFormat,
    override val device: AudioDevice?,
    override val bufferFrames: Int,
    private val ring: PcmRing,
    private val signal: RtSignal,
    private val control: DeviceControl,
) : AudioOutput {

    override var state: AudioState = AudioState.OPEN
        private set

    /** Frames the callback had to replace with silence. */
    var underruns: Long = 0

    override fun start() {
        check(state != AudioState.CLOSED) { "stream is closed" }
        if (state == AudioState.STARTED) return
        control.startDevice()
        state = AudioState.STARTED
    }

    override fun stop() {
        if (state != AudioState.STARTED) return
        state = AudioState.STOPPED
        control.stopDevice()
        control.flushDevice()
        signal.signal()
    }

    override fun write(buffer: AudioBuffer): Int {
        require(buffer.format == format) { "format mismatch: $format != ${buffer.format}" }
        var written = 0
        while (written < buffer.frameCount) {
            val frames = buffer.usePointer { pointer ->
                ring.write(pointer.byteOffset(format.framesToBytes(written)), buffer.frameCount - written)
            }
            written += frames
            if (written >= buffer.frameCount) break
            if (state != AudioState.STARTED) return -1
            signal.await()
        }
        return written
    }

    override fun writeNonBlocking(buffer: AudioBuffer): Int {
        require(buffer.format == format) { "format mismatch: $format != ${buffer.format}" }
        return buffer.usePointer { pointer ->
            ring.write(pointer, buffer.frameCount)
        }
    }

    override fun available(): Int = ring.availableToWrite

    override fun close() {
        if (state == AudioState.CLOSED) return
        state = AudioState.CLOSED
        control.closeDevice()
        ring.close()
        signal.close()
    }
}

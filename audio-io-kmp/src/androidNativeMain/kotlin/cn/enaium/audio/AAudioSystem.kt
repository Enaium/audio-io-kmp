@file:OptIn(ExperimentalForeignApi::class)

package cn.enaium.audio

import cnames.structs.AAudioStream
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.invoke
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value

/** One nanosecond, the unit AAudio uses for its timeouts. */
private const val NANOS_PER_MILLI: Long = 1_000_000

/** How long one blocking read or write waits before it rechecks the stream state. */
private const val BLOCKING_SLICE_MILLIS: Long = 100

/** How long `start` waits for the stream to reach the started state. */
private const val START_TIMEOUT_NANOS: Long = 1_000 * NANOS_PER_MILLI

/**
 * Backend built on AAudio, the native audio API of Android 8.0 (API 26) and
 * newer.
 *
 * AAudio exposes no device enumeration, so the system reports one default
 * endpoint per direction. A caller that knows a device id from the platform can
 * pass it as an [AudioDevice.id] built from that number - it is forwarded to
 * `AAudioStreamBuilder_setDeviceId` - and `default` (or `null`) asks for the
 * system default.
 *
 * Streams run in shared mode with the low latency performance mode, which is
 * what makes AAudio use the fast mixer path when the device supports it.
 */
class AAudioSystem : AudioSystem {

    override val name: String = "AAudio"

    private var closed = false

    override fun inputDevices(): List<AudioDevice> = listOf(defaultDevice(AudioDeviceType.INPUT))

    override fun outputDevices(): List<AudioDevice> = listOf(defaultDevice(AudioDeviceType.OUTPUT))

    override fun defaultInputDevice(): AudioDevice? = inputDevices().firstOrNull()

    override fun defaultOutputDevice(): AudioDevice? = outputDevices().firstOrNull()

    override fun openInput(format: AudioFormat, device: AudioDevice?, bufferFrames: Int): AudioInput {
        checkOpen()
        val frames = if (bufferFrames > 0) bufferFrames else format.defaultBufferFrames()
        val stream = openStream(format, device, capture = true, bufferFrames = frames)
        return AAudioInput(format, device, streamBufferFrames(stream, frames), stream)
    }

    override fun openOutput(format: AudioFormat, device: AudioDevice?, bufferFrames: Int): AudioOutput {
        checkOpen()
        val frames = if (bufferFrames > 0) bufferFrames else format.defaultBufferFrames()
        val stream = openStream(format, device, capture = false, bufferFrames = frames)
        return AAudioOutput(format, device, streamBufferFrames(stream, frames), stream)
    }

    override fun close() {
        closed = true
    }

    private fun checkOpen() {
        check(!closed) { "audio system is closed" }
    }

    private fun defaultDevice(type: AudioDeviceType): AudioDevice = AudioDevice(
        id = DEFAULT_DEVICE_ID,
        name = if (type == AudioDeviceType.INPUT) "AAudio default input" else "AAudio default output",
        type = type,
        isDefault = true,
    )

    private fun openStream(
        format: AudioFormat,
        device: AudioDevice?,
        capture: Boolean,
        bufferFrames: Int,
    ): CPointer<AAudioStream> {
        val api = AAudio.api()
        val aaudioFormat = format.aaudioFormat() ?: throw AudioException(
            "AAudio cannot carry ${format.sampleFormat}; supported formats are PCM_S16, PCM_S24, PCM_S32 and PCM_F32",
        )
        return AAudio.withBuilder { builder ->
            api.builderSetDirection.required("AAudioStreamBuilder_setDirection")(
                builder,
                if (capture) AAUDIO_DIRECTION_INPUT else AAUDIO_DIRECTION_OUTPUT,
            )
            api.builderSetSharingMode.required("AAudioStreamBuilder_setSharingMode")(
                builder,
                AAUDIO_SHARING_MODE_SHARED,
            )
            api.builderSetPerformanceMode.required("AAudioStreamBuilder_setPerformanceMode")(
                builder,
                AAUDIO_PERFORMANCE_MODE_LOW_LATENCY,
            )
            api.builderSetFormat.required("AAudioStreamBuilder_setFormat")(builder, aaudioFormat)
            api.builderSetChannelCount.required("AAudioStreamBuilder_setChannelCount")(
                builder,
                format.channelCount,
            )
            api.builderSetSampleRate.required("AAudioStreamBuilder_setSampleRate")(builder, format.sampleRate)
            api.builderSetBufferCapacityInFrames.required("AAudioStreamBuilder_setBufferCapacityInFrames")(
                builder,
                bufferFrames * CAPACITY_PERIODS,
            )
            deviceId(device)?.let {
                api.builderSetDeviceId.required("AAudioStreamBuilder_setDeviceId")(builder, it)
            }

            memScoped {
                val holder = alloc<CPointerVar<AAudioStream>>()
                AAudio.check(
                    api.builderOpenStream.required("AAudioStreamBuilder_openStream")(builder, holder.ptr),
                    "AAudioStreamBuilder_openStream($format)",
                )
                val stream = holder.value
                    ?: throw AudioException("AAudioStreamBuilder_openStream returned no stream")
                // The requested buffer is honoured as far as the device allows;
                // AAudio answers with what it actually uses.
                api.streamSetBufferSizeInFrames.required("AAudioStream_setBufferSizeInFrames")(stream, bufferFrames)
                stream
            }
        }
    }

    private fun streamBufferFrames(stream: CPointer<AAudioStream>, fallback: Int): Int {
        val api = AAudio.api()
        val frames = api.streamGetBufferSizeInFrames.required("AAudioStream_getBufferSizeInFrames")(stream)
        return if (frames > 0) frames else fallback
    }

    /** The numeric device id of [device], or `null` for the system default. */
    private fun deviceId(device: AudioDevice?): Int? {
        val id = device?.id ?: return null
        if (id == DEFAULT_DEVICE_ID) return null
        return id.toIntOrNull()
            ?: throw AudioException("AAudio expects a numeric device id, got \"$id\"")
    }

    private companion object {
        /** Identifier of the synthetic default endpoint. */
        const val DEFAULT_DEVICE_ID = "default"

        /** Buffer capacity relative to the requested frame count. */
        const val CAPACITY_PERIODS = 4
    }
}

/**
 * Blocking capture on an AAudio stream.
 *
 * `AAudioStream_read` never waits when the timeout is zero, so the blocking
 * contract is built from repeated reads with a bounded timeout: the call returns
 * as soon as one frame arrived, and a stopped or closed stream is reported as
 * `-1` even while the caller is parked in a read.
 */
internal class AAudioInput(
    override val format: AudioFormat,
    override val device: AudioDevice?,
    override val bufferFrames: Int,
    private val stream: CPointer<AAudioStream>,
) : AudioInput {

    override var state: AudioState = AudioState.OPEN
        private set

    override fun start() {
        check(state != AudioState.CLOSED) { "stream is closed" }
        if (state == AudioState.STARTED) return
        startStream(stream)
        state = AudioState.STARTED
    }

    override fun stop() {
        if (state != AudioState.STARTED) return
        state = AudioState.STOPPED
        AAudio.api().streamRequestStop.required("AAudioStream_requestStop")(stream)
    }

    override fun read(buffer: AudioBuffer): Int {
        require(buffer.format == format) { "format mismatch: $format != ${buffer.format}" }
        if (state != AudioState.STARTED) return -1
        return buffer.data.usePinned { pinned ->
            var frames = 0
            while (true) {
                if (frames == buffer.capacityFrames) break
                val read = AAudio.api().streamRead.required("AAudioStream_read")(
                    stream,
                    pinned.addressOf(0).byteOffset(format.framesToBytes(frames)),
                    buffer.capacityFrames - frames,
                    BLOCKING_SLICE_MILLIS * NANOS_PER_MILLI,
                )
                when {
                    read > 0 -> frames += read
                    read == 0 -> if (frames > 0) break
                    else -> {
                        if (state != AudioState.STARTED) break
                        throw AudioException("AAudioStream_read failed: ${AAudio.resultText(read)}")
                    }
                }
                if (state != AudioState.STARTED) break
            }
            if (frames > 0) {
                buffer.frameCount = frames
                frames
            } else {
                buffer.frameCount = 0
                -1
            }
        }
    }

    override fun readNonBlocking(buffer: AudioBuffer): Int {
        require(buffer.format == format) { "format mismatch: $format != ${buffer.format}" }
        return buffer.data.usePinned { pinned ->
            val frames = AAudio.api().streamRead.required("AAudioStream_read")(
                stream,
                pinned.addressOf(0),
                buffer.capacityFrames,
                0,
            )
            buffer.frameCount = if (frames > 0) frames else 0
            buffer.frameCount
        }
    }

    /**
     * Frames the stream holds, as an upper bound: AAudio cannot report how much
     * of the buffer is filled at this instant.
     */
    override fun available(): Int = bufferFrames

    override fun close() {
        if (state == AudioState.CLOSED) return
        state = AudioState.CLOSED
        AAudio.api().streamClose.required("AAudioStream_close")(stream)
    }
}

/**
 * Blocking playback on an AAudio stream.
 *
 * `AAudioStream_write` accepts as many frames as the buffer has room for, so
 * [write] loops until the whole buffer reached the device and reports `-1` when
 * the stream was stopped underneath it.
 */
internal class AAudioOutput(
    override val format: AudioFormat,
    override val device: AudioDevice?,
    override val bufferFrames: Int,
    private val stream: CPointer<AAudioStream>,
) : AudioOutput {

    override var state: AudioState = AudioState.OPEN
        private set

    override fun start() {
        check(state != AudioState.CLOSED) { "stream is closed" }
        if (state == AudioState.STARTED) return
        startStream(stream)
        state = AudioState.STARTED
    }

    override fun stop() {
        if (state != AudioState.STARTED) return
        state = AudioState.STOPPED
        AAudio.api().streamRequestStop.required("AAudioStream_requestStop")(stream)
    }

    override fun write(buffer: AudioBuffer): Int {
        require(buffer.format == format) { "format mismatch: $format != ${buffer.format}" }
        if (state != AudioState.STARTED) return -1
        if (buffer.frameCount == 0) return 0
        return buffer.data.usePinned { pinned ->
            var written = 0
            while (written < buffer.frameCount) {
                val frames = AAudio.api().streamWrite.required("AAudioStream_write")(
                    stream,
                    pinned.addressOf(0).byteOffset(format.framesToBytes(written)),
                    buffer.frameCount - written,
                    BLOCKING_SLICE_MILLIS * NANOS_PER_MILLI,
                )
                when {
                    frames > 0 -> written += frames
                    frames == 0 -> Unit
                    else -> {
                        if (state != AudioState.STARTED) return@usePinned -1
                        throw AudioException("AAudioStream_write failed: ${AAudio.resultText(frames)}")
                    }
                }
                if (state != AudioState.STARTED) return@usePinned -1
            }
            written
        }
    }

    override fun writeNonBlocking(buffer: AudioBuffer): Int {
        require(buffer.format == format) { "format mismatch: $format != ${buffer.format}" }
        if (buffer.frameCount == 0) return 0
        return buffer.data.usePinned { pinned ->
            val frames = AAudio.api().streamWrite.required("AAudioStream_write")(
                stream,
                pinned.addressOf(0),
                buffer.frameCount,
                0,
            )
            if (frames > 0) frames else 0
        }
    }

    /** Frames that fit into the stream buffer, as an upper bound. */
    override fun available(): Int = bufferFrames

    override fun close() {
        if (state == AudioState.CLOSED) return
        state = AudioState.CLOSED
        AAudio.api().streamClose.required("AAudioStream_close")(stream)
    }
}

/** Requests the start of a stream and waits, bounded, for it to run. */
private fun startStream(stream: CPointer<AAudioStream>) {
    val api = AAudio.api()
    AAudio.check(
        api.streamRequestStart.required("AAudioStream_requestStart")(stream),
        "AAudioStream_requestStart",
    )
    val getState = api.streamGetState.required("AAudioStream_getState")
    val wait = api.streamWaitForStateChange.required("AAudioStream_waitForStateChange")
    var remaining = START_TIMEOUT_NANOS
    memScoped {
        val next = alloc<IntVar>()
        var current = getState(stream)
        while (current != AAUDIO_STATE_STARTED && current != AAUDIO_STATE_CLOSED && remaining > 0) {
            // A timeout is not fatal: reads and writes wait for the device
            // themselves, this only avoids a long stall when a device refuses
            // to start.
            val started = wait(stream, current, next.ptr, remaining)
            remaining -= SLICE_NANOS
            if (started != AAUDIO_OK) break
            current = next.value
        }
    }
}

/** One slice of the start timeout. */
private const val SLICE_NANOS: Long = BLOCKING_SLICE_MILLIS * NANOS_PER_MILLI

/** The AAudio backend of this platform. */
actual fun audioSystem(): AudioSystem = AAudioSystem()

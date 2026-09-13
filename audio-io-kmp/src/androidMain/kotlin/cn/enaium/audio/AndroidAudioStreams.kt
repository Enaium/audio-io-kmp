package cn.enaium.audio

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build

import android.media.AudioFormat as AndroidPcmFormat

/** Buffering the backend asks for when the caller does not pick an amount. */
private const val DEFAULT_BUFFER_MILLIS: Int = 20

/** Frames that fill [DEFAULT_BUFFER_MILLIS] of this format, with a floor that keeps tiny rates usable. */
private fun AudioFormat.androidDefaultBufferFrames(): Int =
    maxOf(millisToFrames(DEFAULT_BUFFER_MILLIS.toDouble()), 64)

/** Bytes to hand to `setBufferSizeInBytes` for a requested [bufferFrames], `0` meaning the backend default. */
private fun AudioFormat.androidBufferBytes(bufferFrames: Int): Int =
    framesToBytes(if (bufferFrames > 0) bufferFrames else androidDefaultBufferFrames())

/**
 * Turns a platform failure into an [AudioException].
 *
 * For capture the message also names `RECORD_AUDIO`: Android reports a denied
 * permission either as a [SecurityException] or, more commonly, as an
 * [IllegalStateException] because it leaves the recorder uninitialized.
 */
private fun platformFailure(action: String, cause: RuntimeException, capture: Boolean): AudioException {
    val permission = if (!capture) {
        ""
    } else if (cause is SecurityException) {
        "; the app must hold the RECORD_AUDIO permission"
    } else {
        "; this usually means the app does not hold the RECORD_AUDIO permission"
    }
    return AudioException("cannot $action: ${cause.message}$permission", cause)
}

/** Mask of the two layouts Android can capture, or an [AudioException] naming the format. */
private fun inputChannelMask(format: AudioFormat): Int = when (format.channelCount) {
    1 -> AndroidPcmFormat.CHANNEL_IN_MONO
    2 -> AndroidPcmFormat.CHANNEL_IN_STEREO
    else -> throw AudioException("Android captures 1 or 2 channels, not ${format.channelCount} ($format)")
}

/** Mask of the two layouts Android can play, or an [AudioException] naming the format. */
private fun outputChannelMask(format: AudioFormat): Int = when (format.channelCount) {
    1 -> AndroidPcmFormat.CHANNEL_OUT_MONO
    2 -> AndroidPcmFormat.CHANNEL_OUT_STEREO
    else -> throw AudioException("Android plays 1 or 2 channels, not ${format.channelCount} ($format)")
}

/** Android encoding of a sample layout, or an [AudioException] naming the format. */
private fun encodingOf(format: AudioFormat): Int = when (format.sampleFormat) {
    SampleFormat.PCM_U8 -> AndroidPcmFormat.ENCODING_PCM_8BIT
    SampleFormat.PCM_S16 -> AndroidPcmFormat.ENCODING_PCM_16BIT
    SampleFormat.PCM_F32 -> AndroidPcmFormat.ENCODING_PCM_FLOAT
    SampleFormat.PCM_S32 ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            AndroidPcmFormat.ENCODING_PCM_32BIT
        } else {
            throw AudioException(
                "$format needs Android 12 (API 31): ENCODING_PCM_32BIT is not available on API ${Build.VERSION.SDK_INT}",
            )
        }
    SampleFormat.PCM_S24 -> throw AudioException("Android has no packed 24 bit encoding, cannot use $format")
    SampleFormat.PCM_F64 -> throw AudioException("Android has no 64 bit float encoding, cannot use $format")
}

/**
 * Capture stream backed by an [AudioRecord].
 *
 * The record is opened by [open], which maps the [AudioFormat] onto an Android
 * encoding and validates it against `AudioRecord.getMinBufferSize`, so a
 * stream only exists for a format the device can actually capture.
 *
 * `AudioRecord.read` with `READ_BLOCKING` parks the calling thread until the
 * driver produced audio. A [stop] from another thread makes it return early;
 * [read] then reports `-1` for a stream that is no longer started, which is how
 * a capture loop ends without a separate signal.
 *
 * Constructing an [AudioRecord] without `RECORD_AUDIO` usually does not throw:
 * Android hands back an uninitialized recorder instead. [open] checks for that
 * and fails with an [AudioException] that names the permission, and
 * [start]/[stop] translate the `IllegalStateException`/`SecurityException` of a
 * denied permission the same way. Capture is the only part of this backend that
 * needs a permission.
 */
internal class AndroidAudioInput private constructor(
    override val format: AudioFormat,
    override val device: AudioDevice?,
    private val record: AudioRecord,
    override val bufferFrames: Int,
) : AudioInput {

    override var state: AudioState = AudioState.OPEN
        private set

    override fun start() {
        check(state != AudioState.CLOSED) { "stream is closed" }
        if (state == AudioState.STARTED) return
        try {
            record.startRecording()
        } catch (e: IllegalStateException) {
            throw platformFailure("start capture of $format", e, capture = true)
        } catch (e: SecurityException) {
            throw platformFailure("start capture of $format", e, capture = true)
        }
        state = AudioState.STARTED
    }

    override fun stop() {
        if (state != AudioState.STARTED) return
        // The state flips before the driver is stopped, so a thread blocked in
        // read() sees STOPPED and reports -1 even if it wakes up before
        // AudioRecord.stop() returns.
        state = AudioState.STOPPED
        try {
            record.stop()
        } catch (e: IllegalStateException) {
            throw platformFailure("stop capture of $format", e, capture = true)
        }
    }

    override fun read(buffer: AudioBuffer): Int {
        val bytes = try {
            record.read(buffer.data, 0, format.framesToBytes(buffer.capacityFrames), AudioRecord.READ_BLOCKING)
        } catch (e: IllegalStateException) {
            throw platformFailure("read $format", e, capture = true)
        }
        if (bytes <= 0) {
            buffer.frameCount = 0
            return if (state == AudioState.STARTED) 0 else -1
        }
        buffer.frameCount = format.bytesToFrames(bytes)
        return buffer.frameCount
    }

    override fun readNonBlocking(buffer: AudioBuffer): Int {
        val bytes = try {
            record.read(buffer.data, 0, format.framesToBytes(buffer.capacityFrames), AudioRecord.READ_NON_BLOCKING)
        } catch (e: IllegalStateException) {
            throw platformFailure("read $format", e, capture = true)
        }
        buffer.frameCount = if (bytes > 0) format.bytesToFrames(bytes) else 0
        return buffer.frameCount
    }

    /**
     * Upper bound of the frames the driver can hold for the next [read].
     *
     * `AudioRecord` has no query for how many frames are already captured and
     * waiting (it offers nothing like `DataLine.available()`), so this reports
     * the buffer size of the running stream, and `0` while the stream is not
     * started. Polling [readNonBlocking] while this is positive is safe: the
     * call never blocks and returns the frames that were actually ready.
     */
    override fun available(): Int = if (state == AudioState.STARTED) record.bufferSizeInFrames else 0

    override fun close() {
        if (state == AudioState.CLOSED) return
        state = AudioState.CLOSED
        runCatching { record.stop() }
        runCatching { record.release() }
    }

    internal companion object {

        /**
         * Builds and configures the recorder.
         *
         * A recorder that comes back uninitialized, or a failure while it is
         * being routed to [preferredDevice], releases it again before the
         * [AudioException] leaves this method.
         */
        fun open(
            format: AudioFormat,
            device: AudioDevice?,
            preferredDevice: AudioDeviceInfo?,
            bufferFrames: Int,
        ): AndroidAudioInput {
            val channelMask = inputChannelMask(format)
            val encoding = encodingOf(format)
            val minBytes = AudioRecord.getMinBufferSize(format.sampleRate, channelMask, encoding)
            if (minBytes < 0) {
                throw AudioException(
                    "Android cannot capture $format (AudioRecord.getMinBufferSize returned $minBytes)",
                )
            }
            val bytes = maxOf(format.androidBufferBytes(bufferFrames), minBytes)
            val record = try {
                AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.DEFAULT)
                    .setAudioFormat(
                        AndroidPcmFormat.Builder()
                            .setSampleRate(format.sampleRate)
                            .setChannelMask(channelMask)
                            .setEncoding(encoding)
                            .build(),
                    )
                    .setBufferSizeInBytes(bytes)
                    .build()
            } catch (e: IllegalStateException) {
                throw platformFailure("open capture of $format", e, capture = true)
            } catch (e: SecurityException) {
                throw platformFailure("open capture of $format", e, capture = true)
            } catch (e: UnsupportedOperationException) {
                throw AudioException("Android cannot capture $format: ${e.message}", e)
            }
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                runCatching { record.release() }
                throw platformFailure(
                    "open capture of $format",
                    IllegalStateException("Android left the recorder uninitialized"),
                    capture = true,
                )
            }
            try {
                if (preferredDevice != null && !record.setPreferredDevice(preferredDevice)) {
                    throw AudioException(
                        "Android refused to route capture to ${preferredDevice.productName} " +
                            "(id ${preferredDevice.id})",
                    )
                }
            } catch (e: RuntimeException) {
                runCatching { record.release() }
                throw e
            }
            val frames = record.bufferSizeInFrames.takeIf { it > 0 } ?: format.androidDefaultBufferFrames()
            return AndroidAudioInput(format, device, record, frames)
        }
    }
}

/**
 * Playback stream backed by an [AudioTrack].
 *
 * The track is opened in `MODE_STREAM` by [open], which maps the [AudioFormat]
 * onto an Android encoding and validates it against
 * `AudioTrack.getMinBufferSize`.
 *
 * [write] loops until the whole [AudioBuffer] was accepted by the track, so
 * callers never see a partial write. A [stop] from another thread pauses and
 * flushes the track, which makes a blocked `AudioTrack.write` return early;
 * [write] then reports `-1` for a stream that is no longer started.
 */
internal class AndroidAudioOutput private constructor(
    override val format: AudioFormat,
    override val device: AudioDevice?,
    private val track: AudioTrack,
    override val bufferFrames: Int,
) : AudioOutput {

    override var state: AudioState = AudioState.OPEN
        private set

    override fun start() {
        check(state != AudioState.CLOSED) { "stream is closed" }
        if (state == AudioState.STARTED) return
        try {
            track.play()
        } catch (e: IllegalStateException) {
            throw platformFailure("start playback of $format", e, capture = false)
        }
        state = AudioState.STARTED
    }

    override fun stop() {
        if (state != AudioState.STARTED) return
        // As on the capture side, the state flips first so a blocked write()
        // reports -1 instead of returning a partial count.
        state = AudioState.STOPPED
        try {
            track.pause()
            track.flush()
        } catch (e: IllegalStateException) {
            throw platformFailure("stop playback of $format", e, capture = false)
        }
    }

    override fun write(buffer: AudioBuffer): Int {
        var written = 0
        val total = format.framesToBytes(buffer.frameCount)
        while (written < total) {
            val n = try {
                track.write(buffer.data, written, total - written, AudioTrack.WRITE_BLOCKING)
            } catch (e: IllegalStateException) {
                throw platformFailure("write $format", e, capture = false)
            }
            if (n <= 0) {
                return if (state == AudioState.STARTED) format.bytesToFrames(written) else -1
            }
            written += n
        }
        return format.bytesToFrames(written)
    }

    override fun writeNonBlocking(buffer: AudioBuffer): Int {
        val total = format.framesToBytes(buffer.frameCount)
        if (total <= 0) return 0
        val n = try {
            track.write(buffer.data, 0, total, AudioTrack.WRITE_NON_BLOCKING)
        } catch (e: IllegalStateException) {
            throw platformFailure("write $format", e, capture = false)
        }
        return if (n > 0) format.bytesToFrames(n) else 0
    }

    /**
     * Always `0`.
     *
     * `AudioTrack` has no free-space query: the playback head only counts frames
     * the hardware consumed, so "buffer size minus queued frames" is wrong as
     * soon as the track is paused or flushed, and the driver can still refuse a
     * non-blocking write during an internal transition. Since no number greater
     * than zero can be guaranteed, this reports zero free space and leaves the
     * decision to [writeNonBlocking], which returns what the driver accepted.
     */
    override fun available(): Int = 0

    override fun close() {
        if (state == AudioState.CLOSED) return
        state = AudioState.CLOSED
        runCatching { track.stop() }
        runCatching { track.release() }
    }

    internal companion object {

        /**
         * Builds and configures the track.
         *
         * A track that comes back uninitialized, or a failure while it is being
         * routed to [preferredDevice], releases it again before the
         * [AudioException] leaves this method.
         */
        fun open(
            format: AudioFormat,
            device: AudioDevice?,
            preferredDevice: AudioDeviceInfo?,
            bufferFrames: Int,
        ): AndroidAudioOutput {
            val channelMask = outputChannelMask(format)
            val encoding = encodingOf(format)
            val minBytes = AudioTrack.getMinBufferSize(format.sampleRate, channelMask, encoding)
            if (minBytes < 0) {
                throw AudioException(
                    "Android cannot play $format (AudioTrack.getMinBufferSize returned $minBytes)",
                )
            }
            val bytes = maxOf(format.androidBufferBytes(bufferFrames), minBytes)
            val track = try {
                AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build(),
                    )
                    .setAudioFormat(
                        AndroidPcmFormat.Builder()
                            .setSampleRate(format.sampleRate)
                            .setChannelMask(channelMask)
                            .setEncoding(encoding)
                            .build(),
                    )
                    .setBufferSizeInBytes(bytes)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
            } catch (e: IllegalStateException) {
                throw platformFailure("open playback of $format", e, capture = false)
            } catch (e: SecurityException) {
                throw platformFailure("open playback of $format", e, capture = false)
            } catch (e: UnsupportedOperationException) {
                throw AudioException("Android cannot play $format: ${e.message}", e)
            }
            if (track.state != AudioTrack.STATE_INITIALIZED) {
                runCatching { track.release() }
                throw AudioException("Android did not initialize the track for $format")
            }
            try {
                if (preferredDevice != null && !track.setPreferredDevice(preferredDevice)) {
                    throw AudioException(
                        "Android refused to route playback to ${preferredDevice.productName} " +
                            "(id ${preferredDevice.id})",
                    )
                }
            } catch (e: RuntimeException) {
                runCatching { track.release() }
                throw e
            }
            val frames = track.bufferSizeInFrames.takeIf { it > 0 } ?: format.androidDefaultBufferFrames()
            return AndroidAudioOutput(format, device, track, frames)
        }
    }
}

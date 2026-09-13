package cn.enaium.audio

import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine
import javax.sound.sampled.TargetDataLine

/**
 * Capture stream backed by a `TargetDataLine`.
 *
 * `TargetDataLine.read` blocks until the driver filled the requested bytes; a
 * `stop()` from another thread makes it return early, which [read] reports as
 * `-1` so a capture loop terminates deterministically.
 */
internal class JvmAudioInput(
    override val format: AudioFormat,
    override val device: AudioDevice?,
    private val line: TargetDataLine,
) : AudioInput {

    override val bufferFrames: Int by lazy { line.bufferSize / format.frameSizeBytes }

    override var state: AudioState = AudioState.OPEN
        private set

    override fun start() {
        check(state != AudioState.CLOSED) { "stream is closed" }
        if (state == AudioState.STARTED) return
        line.start()
        state = AudioState.STARTED
    }

    override fun stop() {
        if (state != AudioState.STARTED) return
        line.stop()
        state = AudioState.STOPPED
    }

    override fun read(buffer: AudioBuffer): Int {
        val bytes = line.read(buffer.data, 0, format.framesToBytes(buffer.capacityFrames))
        if (bytes <= 0) {
            buffer.frameCount = 0
            return if (state == AudioState.STARTED) 0 else -1
        }
        buffer.frameCount = format.bytesToFrames(bytes)
        return buffer.frameCount
    }

    override fun readNonBlocking(buffer: AudioBuffer): Int {
        val availableBytes = line.available()
        if (availableBytes <= 0) {
            buffer.frameCount = 0
            return 0
        }
        val bytes = line.read(buffer.data, 0, minOf(availableBytes, format.framesToBytes(buffer.capacityFrames)))
        buffer.frameCount = if (bytes > 0) format.bytesToFrames(bytes) else 0
        return buffer.frameCount
    }

    override fun available(): Int = line.available() / format.frameSizeBytes

    override fun close() {
        if (state == AudioState.CLOSED) return
        state = AudioState.CLOSED
        release(line)
    }
}

/**
 * Playback stream backed by a `SourceDataLine`.
 *
 * `write` blocks until every byte of the buffer reached the driver, so callers
 * never have to loop over partial writes themselves.
 */
internal class JvmAudioOutput(
    override val format: AudioFormat,
    override val device: AudioDevice?,
    private val line: SourceDataLine,
) : AudioOutput {

    override val bufferFrames: Int by lazy { line.bufferSize / format.frameSizeBytes }

    override var state: AudioState = AudioState.OPEN
        private set

    override fun start() {
        check(state != AudioState.CLOSED) { "stream is closed" }
        if (state == AudioState.STARTED) return
        line.start()
        state = AudioState.STARTED
    }

    override fun stop() {
        if (state != AudioState.STARTED) return
        line.stop()
        state = AudioState.STOPPED
    }

    override fun write(buffer: AudioBuffer): Int {
        var written = 0
        val total = format.framesToBytes(buffer.frameCount)
        while (written < total) {
            val n = line.write(buffer.data, written, total - written)
            if (n <= 0) return if (state == AudioState.STARTED) format.bytesToFrames(written) else -1
            written += n
        }
        return format.bytesToFrames(written)
    }

    override fun writeNonBlocking(buffer: AudioBuffer): Int {
        val space = minOf(line.available(), format.framesToBytes(buffer.frameCount))
        if (space <= 0) return 0
        val n = line.write(buffer.data, 0, space)
        return if (n > 0) format.bytesToFrames(n) else 0
    }

    override fun available(): Int = line.available() / format.frameSizeBytes

    override fun close() {
        if (state == AudioState.CLOSED) return
        state = AudioState.CLOSED
        release(line)
    }
}

/** Stops and closes a line, tolerating a line the driver already dropped. */
private fun release(line: DataLine) {
    runCatching { line.stop() }
    runCatching { line.flush() }
    runCatching { line.close() }
}

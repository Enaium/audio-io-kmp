package cn.enaium.audio.example.visualizer

import cn.enaium.audio.AudioBuffer
import cn.enaium.audio.AudioFormat
import cn.enaium.audio.AudioOutput
import cn.enaium.audio.AudioSystem
import cn.enaium.sdl.SDL
import cn.enaium.sdl.SDLThread
import cn.enaium.sdl.SDLThreads
import kotlin.concurrent.Volatile

/**
 * Plays a recorded buffer back through an output stream.
 *
 * The samples are interleaved floats in `-1..1`, which is how
 * [SpectrumAnalyzer.recordedSnapshot] keeps the recording; they are converted
 * back to the sample format the audio was captured in when a chunk is written.
 * [start] opens the output, spawns a writer thread and
 * returns; that thread blocks in [AudioOutput.write] and hands the samples to
 * the device chunk by chunk. [playedFrames], [isPlaying] and [error] are
 * written by the writer thread and read by the UI; [stop] unblocks it by
 * stopping the stream.
 *
 * Playback and capture are independent: both can run at the same time, and a
 * backend that refuses full duplex reports that through [error].
 */
class Playback(private val system: AudioSystem?) : AutoCloseable {

    /** `true` while the writer thread runs. */
    @Volatile
    var isPlaying: Boolean = false
        private set

    /** Frames handed to the device so far. */
    @Volatile
    var playedFrames: Long = 0
        private set

    /** Frames of the current (or last) playback, `0` before the first one. */
    @Volatile
    var totalFrames: Long = 0
        private set

    /** Format of the current (or last) playback, `null` before the first one. */
    @Volatile
    var format: AudioFormat? = null
        private set

    /** Message of the last failed start or write, or `null`. */
    @Volatile
    var error: String? = null
        private set

    private var output: AudioOutput? = null
    private var thread: SDLThread? = null

    @Volatile
    private var keepWriting = false

    /**
     * Plays the first [frames] frames of the interleaved float [samples] in
     * `-1..1`.
     *
     * [format] is the format the audio was recorded with, so it plays back the
     * way it was captured; the float samples are converted to it on the fly.
     *
     * @return `false` (and sets [error]) when nothing could be started; a
     *   previous playback is stopped either way.
     */
    fun start(format: AudioFormat, samples: FloatArray, frames: Int): Boolean {
        stop()
        val system = system ?: return fail("no audio backend is available")
        if (frames <= 0) return fail("nothing to play")
        return try {
            // A small device buffer keeps the frames that are accepted but not
            // played yet short: the writer waits exactly one buffer at the end.
            val stream = system.openOutput(format, bufferFrames = OUTPUT_BUFFER_FRAMES)
            val channels = format.channelCount
            // One device buffer per write keeps the writer thread blocked for
            // a few milliseconds instead of spinning.
            val chunk = minOf(maxOf(stream.bufferFrames, MIN_CHUNK_FRAMES), MAX_CHUNK_FRAMES)
            output = stream
            this.format = format
            totalFrames = frames.toLong()
            playedFrames = 0
            error = null
            stream.start()
            keepWriting = true
            isPlaying = true
            val writer = SDLThreads.createThread("audio-visualizer-playback") {
                try {
                    write(samples, frames, channels, chunk)
                } finally {
                    isPlaying = false
                    closeOutput()
                }
            }
            if (writer == null) {
                keepWriting = false
                isPlaying = false
                closeOutput()
                return fail("cannot create the playback thread")
            }
            thread = writer
            true
        } catch (e: Exception) {
            closeOutput()
            fail("cannot play: ${e.message ?: e.toString()}")
        }
    }

    /** Stops the writer thread, waiting for the write in flight. */
    fun stop() {
        keepWriting = false
        val writer = thread
        thread = null
        // Unblocks a thread sitting in AudioOutput.write, which then returns -1.
        runCatching { output?.stop() }
        writer?.wait()
        closeOutput()
        isPlaying = false
    }

    override fun close() {
        stop()
    }

    private fun write(samples: FloatArray, frames: Int, channels: Int, chunk: Int) {
        val stream = output ?: return
        val buffer = AudioBuffer(stream.format, chunk)
        val slice = FloatArray(chunk * channels)
        var offset = 0
        while (keepWriting && offset < frames) {
            val count = minOf(chunk, frames - offset)
            samples.copyInto(slice, 0, offset * channels, (offset + count) * channels)
            buffer.clear()
            buffer.putFloats(slice, count * channels)
            val written = try {
                stream.write(buffer)
            } catch (e: Exception) {
                fail("playback failed: ${e.message ?: e.toString()}")
                break
            }
            if (written <= 0) break
            offset += written
            playedFrames = offset.toLong()
        }
        // `write` returns once the frames are queued, so the device still holds
        // up to one buffer that closing would discard. It drains at the sample
        // rate, which makes that wait exact.
        if (keepWriting) {
            val tailMillis = stream.bufferFrames * 1000L / stream.format.sampleRate
            if (tailMillis > 0) SDL.delay(tailMillis.toInt())
        }
    }

    /**
     * Releases the stream. Both [stop] and the end of the writer thread call
     * this; the reference is cleared before closing, so whoever arrives second
     * sees `null` and does nothing.
     */
    private fun closeOutput() {
        val stream = output ?: return
        output = null
        runCatching { stream.close() }
    }

    private fun fail(message: String): Boolean {
        error = message
        return false
    }

    private companion object {
        /** Smallest chunk written per call. */
        const val MIN_CHUNK_FRAMES = 512

        /** Largest chunk written per call; bounds the writer's scratch buffer. */
        const val MAX_CHUNK_FRAMES = 4096

        /** Device buffer requested from the output, in frames (~21 ms at 48 kHz). */
        const val OUTPUT_BUFFER_FRAMES = 1024
    }
}

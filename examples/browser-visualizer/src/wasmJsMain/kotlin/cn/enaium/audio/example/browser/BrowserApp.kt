package cn.enaium.audio.example.browser

import cn.enaium.audio.AudioBuffer
import cn.enaium.audio.AudioFormat
import cn.enaium.audio.AudioInput
import cn.enaium.audio.AudioOutput
import cn.enaium.audio.AudioSystem
import cn.enaium.audio.SampleFormat
import cn.enaium.audio.audioSystem
import org.khronos.webgl.Float32Array
import org.khronos.webgl.get
import org.khronos.webgl.set
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.round
import kotlin.math.sqrt
import kotlin.time.TimeSource

/**
 * Browser front end of the spectrum visualizer.
 *
 * Audio moves only while the page runs JavaScript: the capture and playback
 * callbacks of the Web Audio backend fire on the page thread, so this class
 * polls the streams once per animation frame instead of reading from a thread.
 * That is also why the analysis window is a plain ring of mono history: every
 * frame appends what was captured and draws the last *window* frames.
 *
 * The recording keeps the interleaved samples of the capture stream in memory
 * (up to [MAX_RECORD_SECONDS]) and plays them back through an output stream of
 * the same format, in the same way the desktop example does.
 */
internal class BrowserApp {

    private var system: AudioSystem? = null
    private var input: AudioInput? = null
    private var inputBuffer: AudioBuffer? = null
    private var channels = 1
    private var sampleRate = 0

    // ==================== recording ====================

    private var recording = false
    private var recorded = FloatArray(0)
    private var recordedFrames = 0
    private var recordedChannels = 1
    private var recordedRate = 0

    private var output: AudioOutput? = null
    private var playbackBuffer: AudioBuffer? = null
    private var playbackOffset = 0
    private var playbackChannels = 1

    // ==================== analysis ====================

    private var windowFrames = 0
    private var history = FloatArray(0)
    private var historyFrames = 0
    private var historyWrite = 0L
    private var hann = FloatArray(0)
    private var fftScratch = FloatArray(0)
    private var samples = FloatArray(0)
    private var magnitudeDb = FloatArray(0)
    private var waveformValues = Float32Array(0)
    private var spectrumValues = Float32Array(0)

    private var lastWaterfall = TimeSource.Monotonic.markNow()
    private var lastStatus = TimeSource.Monotonic.markNow()
    private var lastFrameMark = TimeSource.Monotonic.markNow()
    private var framesSinceMark = 0
    private var frameRate = 0.0

    /** Opens the capture stream; runs inside the click so the browser allows it. */
    fun startAudio() {
        closeAudio()
        try {
            val created = audioSystem()
            val stream = created.openInput(FORMAT, null, BLOCK_FRAMES)
            stream.start()
            system = created
            input = stream
            channels = stream.format.channelCount
            sampleRate = stream.format.sampleRate
            val buffer = AudioBuffer(stream.format, maxOf(stream.bufferFrames, BLOCK_FRAMES))
            inputBuffer = buffer
            floats = FloatArray(buffer.capacityFrames * channels)
            capturedSinceMark = 0
            captureMark = TimeSource.Monotonic.markNow()
            webApp_setStatus("Capturing from the default input: ${stream.format}")
        } catch (e: Throwable) {
            webApp_setStatus("audio unavailable: ${e.message ?: e.toString()}")
        }
    }

    private fun stopAudio() {
        closeAudio()
        webApp_setStatus("Audio stopped")
    }

    private fun closeAudio() {
        stopPlayback()
        runCatching { input?.close() }
        input = null
        inputBuffer = null
        runCatching { system?.close() }
        system = null
    }

    /** One animation frame. [millis] is the time since the previous frame. */
    fun frame(millis: Double) {
        framesSinceMark++
        val elapsed = lastFrameMark.elapsedNow().inWholeMilliseconds
        if (elapsed >= 500) {
            frameRate = framesSinceMark * 1000.0 / elapsed
            framesSinceMark = 0
            lastFrameMark = TimeSource.Monotonic.markNow()
        }

        val command = webApp_takeCommand()
        if (command and COMMAND_START != 0) startAudio()
        if (command and COMMAND_STOP != 0) stopAudio()
        if (command and COMMAND_RECORD != 0) toggleRecording()
        if (command and COMMAND_PLAY != 0) togglePlayback()
        if (command and COMMAND_CLEAR != 0) webApp_clearWaterfall()

        ensureWindow()
        pollInput()
        measureCapture()
        pumpPlayback()
        draw()
        reportStatus()
    }

    // ==================== capture ====================

    private fun pollInput() {
        val input = input ?: return
        val buffer = inputBuffer ?: return
        // The scratch has to cover a full block: the conversion writes into it
        // and only then knows how many frames arrived.
        val needed = buffer.capacityFrames * channels
        if (floats.size < needed) floats = FloatArray(needed)
        var blocks = 0
        while (blocks < MAX_BLOCKS_PER_FRAME) {
            val frames = input.readNonBlocking(buffer)
            if (frames <= 0) break
            blocks++
            val samples = buffer.toFloats(floats, frames * channels)
            appendHistory(floats, samples)
            if (recording) appendRecording(floats, samples)
            capturedSinceMark += frames
        }
    }

    private var floats = FloatArray(0)
    private var capturedSinceMark = 0L
    private var capturedFrames = 0L
    private var captureRate = 0.0

    private fun measureCapture() {
        capturedFrames += capturedSinceMark
        val elapsed = captureMark.elapsedNow().inWholeMilliseconds
        if (elapsed >= 500) {
            captureRate = capturedSinceMark * 1000.0 / elapsed
            capturedSinceMark = 0
            captureMark = TimeSource.Monotonic.markNow()
        }
    }

    private var captureMark = TimeSource.Monotonic.markNow()

    private fun appendHistory(samples: FloatArray, count: Int) {
        val frames = count / channels
        if (frames <= 0) return
        if (historyFrames == 0) return
        for (frame in 0 until frames) {
            var sum = 0f
            for (channel in 0 until channels) sum += samples[frame * channels + channel]
            history[(historyWrite % historyFrames).toInt()] = sum / channels
            historyWrite++
        }
    }

    // ==================== recording ====================

    private fun toggleRecording() {
        if (recording) {
            recording = false
            webApp_setRecording(false)
            return
        }
        if (input == null || sampleRate <= 0) {
            webApp_setStatus("start the audio first")
            return
        }
        if (floats.size < BLOCK_FRAMES * channels) return
        recordedFrames = 0
        recordedChannels = channels
        recordedRate = sampleRate
        val capacity = minOf(
            sampleRate * MAX_RECORD_SECONDS,
            MAX_RECORD_SAMPLES / channels,
        )
        if (recorded.size < capacity * channels) recorded = FloatArray(capacity * channels)
        recording = true
        webApp_setRecording(true)
    }

    private fun appendRecording(samples: FloatArray, count: Int) {
        if (recordedChannels != channels) return
        val capacityFrames = recorded.size / recordedChannels
        val free = capacityFrames - recordedFrames
        val frames = minOf(count / channels, free)
        if (frames <= 0) {
            recording = false
            webApp_setRecording(false)
            return
        }
        samples.copyInto(recorded, recordedFrames * recordedChannels, 0, frames * channels)
        recordedFrames += frames
        if (recordedFrames >= capacityFrames) {
            recording = false
            webApp_setRecording(false)
        }
    }

    private fun togglePlayback() {
        if (output != null) {
            stopPlayback()
            return
        }
        if (recordedFrames <= 0) {
            webApp_setStatus("nothing recorded yet")
            return
        }
        val system = system ?: run {
            webApp_setStatus("start the audio first")
            return
        }
        try {
            val format = AudioFormat(recordedRate, recordedChannels, SampleFormat.PCM_S16)
            val stream = system.openOutput(format, null, BLOCK_FRAMES)
            stream.start()
            output = stream
            playbackChannels = recordedChannels
            playbackOffset = 0
            val buffer = AudioBuffer(format, maxOf(stream.bufferFrames, BLOCK_FRAMES))
            playbackBuffer = buffer
            if (floats.size < buffer.capacityFrames * playbackChannels) {
                floats = FloatArray(buffer.capacityFrames * playbackChannels)
            }
            webApp_setPlayback(true)
        } catch (e: Throwable) {
            webApp_setStatus("playback unavailable: ${e.message ?: e.toString()}")
        }
    }

    private fun pumpPlayback() {
        val stream = output ?: return
        val buffer = playbackBuffer ?: return
        var written = 0
        while (playbackOffset < recordedFrames && written < MAX_BLOCKS_PER_FRAME) {
            val frames = minOf(buffer.capacityFrames, recordedFrames - playbackOffset)
            val samples = frames * playbackChannels
            for (index in 0 until samples) {
                floats[index] = recorded[playbackOffset * playbackChannels + index]
            }
            buffer.putFloats(floats, samples)
            val accepted = stream.write(buffer)
            if (accepted <= 0) break
            playbackOffset += accepted
            written++
        }
        // The ring is empty again once the device took everything.
        if (playbackOffset >= recordedFrames && stream.available() >= stream.bufferFrames) {
            stopPlayback()
        }
    }

    private fun stopPlayback() {
        val stream = output ?: return
        output = null
        playbackBuffer = null
        playbackChannels = 1
        playbackOffset = 0
        runCatching { stream.close() }
        webApp_setPlayback(false)
    }

    // ==================== analysis ====================

    private fun ensureWindow() {
        val requested = webApp_windowSize().coerceIn(MIN_WINDOW, MAX_WINDOW)
        if (requested == windowFrames) return
        windowFrames = requested
        historyFrames = maxOf(requested, 4096)
        history = FloatArray(historyFrames)
        historyWrite = 0
        hann = Fft.hannWindow(requested)
        fftScratch = FloatArray(2 * requested)
        samples = FloatArray(requested)
        magnitudeDb = FloatArray(Fft.binCount(requested))
        waveformValues = Float32Array(requested)
        spectrumValues = Float32Array(Fft.binCount(requested))
    }

    /** Copies the last [windowFrames] mono samples, zero filling the start. */
    private fun fillWaveform() {
        val start = historyWrite - windowFrames
        var peak = 0f
        for (index in 0 until windowFrames) {
            val at = start + index
            val value = if (at < 0) 0f else history[(at % historyFrames).toInt()]
            waveformValues[index] = value
            if (abs(value) > peak) peak = abs(value)
        }
        for (index in 0 until windowFrames) samples[index] = waveformValues[index]
        level = peak
        val written = Fft.magnitudesDb(samples, hann, fftScratch)
        for (bin in 0 until written) {
            magnitudeDb[bin] = fftScratch[bin]
            val normalized = ((fftScratch[bin] - Fft.MIN_DB) / -Fft.MIN_DB).coerceIn(0f, 1f)
            spectrumValues[bin] = normalized
        }
    }

    private var level = 0f

    // ==================== drawing ====================

    private fun draw() {
        fillWaveform()
        webApp_begin()
        val scale = webApp_scale()
        val width = webApp_width().toFloat()
        val height = webApp_height().toFloat()
        val left = PANEL_WIDTH * scale + 20f * scale
        val right = 20f * scale
        val plotWidth = (width - left - right).coerceAtLeast(1f)
        val top = 26f * scale
        val gap = 34f * scale
        val titleHeight = 18f * scale
        val plotHeight = ((height - top - 2 * gap - 3 * titleHeight - 12f * scale) / 3f).coerceAtLeast(40f)

        var y = top
        webApp_title("Waveform  ${windowFrames} frames (${decimals(windowMillis(), 1)} ms)", left, y, 13f * scale)
        y += titleHeight
        webApp_waveform(waveformValues, windowFrames, left, y, plotWidth, plotHeight)
        y += plotHeight + gap

        webApp_title("Spectrum  ${magnitudeDb.size} bins, peak ${decimals(db(level), 1)} dBFS", left, y, 13f * scale)
        y += titleHeight
        webApp_spectrum(spectrumValues, spectrumValues.length, left, y, plotWidth, plotHeight)
        y += plotHeight + gap

        webApp_title("Waterfall  64 rows", left, y, 13f * scale)
        y += titleHeight
        if (lastWaterfall.elapsedNow().inWholeMilliseconds >= WATERFALL_INTERVAL) {
            webApp_waterfallRow(spectrumValues, spectrumValues.length)
            lastWaterfall = TimeSource.Monotonic.markNow()
        }
        webApp_waterfall(left, y, plotWidth, plotHeight)
    }

    private fun reportStatus() {
        if (lastStatus.elapsedNow().inWholeMilliseconds < STATUS_INTERVAL) return
        lastStatus = TimeSource.Monotonic.markNow()
        val state = when {
            input == null -> "audio stopped"
            recording -> "recording ${decimals(recordedMillis() / 1000.0, 2)} s"
            output != null -> "playing ${decimals(playbackMillis() / 1000.0, 2)} s"
            else -> "capturing ${decimals(captureRate, 0)} frames/s · ${decimals(frameRate, 0)} fps"
        }
        webApp_setStatus("$state  ·  recorded ${decimals(recordedMillis() / 1000.0, 2)} s")
    }

    private fun windowMillis(): Double = windowFrames * 1000.0 / (sampleRate.takeIf { it > 0 } ?: FORMAT.sampleRate)

    private fun recordedMillis(): Double = recordedFrames * 1000.0 / (recordedRate.takeIf { it > 0 } ?: 1)

    private fun playbackMillis(): Double = playbackOffset * 1000.0 / (recordedRate.takeIf { it > 0 } ?: 1)

    private fun db(value: Float): Double =
        if (value <= 1e-5f) Fft.MIN_DB.toDouble() else 20.0 * log10(value.toDouble())

    private fun decimals(value: Double, digits: Int): String {
        var factor = 1.0
        repeat(digits) { factor *= 10.0 }
        return (round(value * factor) / factor).toString()
    }

    private companion object {
        val FORMAT = AudioFormat(48000, 1, SampleFormat.PCM_S16)
        const val BLOCK_FRAMES = 1024
        const val MIN_WINDOW = 256
        const val MAX_WINDOW = 16384
        const val MAX_RECORD_SECONDS = 30
        const val MAX_RECORD_SAMPLES = 4 * 1024 * 1024
        const val MAX_BLOCKS_PER_FRAME = 32
        const val PANEL_WIDTH = 280f
        const val WATERFALL_INTERVAL = 40L
        const val STATUS_INTERVAL = 250L

        const val COMMAND_START = 1
        const val COMMAND_STOP = 2
        const val COMMAND_RECORD = 4
        const val COMMAND_PLAY = 8
        const val COMMAND_CLEAR = 16
    }
}

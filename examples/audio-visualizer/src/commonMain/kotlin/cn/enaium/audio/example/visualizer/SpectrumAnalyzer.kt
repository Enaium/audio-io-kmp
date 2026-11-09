package cn.enaium.audio.example.visualizer

import cn.enaium.audio.AudioBuffer
import cn.enaium.audio.AudioDevice
import cn.enaium.audio.AudioFormat
import cn.enaium.audio.AudioInput
import cn.enaium.audio.AudioRingBuffer
import cn.enaium.audio.AudioSystem
import cn.enaium.sdl.SDLThread
import cn.enaium.sdl.SDLThreads
import kotlin.concurrent.Volatile
import kotlin.math.sqrt
import kotlin.time.TimeSource

/**
 * Capture path of the visualizer, split in two halves that only meet inside the
 * ring buffer:
 *
 * * the audio side ([readOnce] / [capture]) blocks on the [AudioInput] and pushes
 *   whole frames into an [AudioRingBuffer]; it runs on a background thread,
 * * the UI side ([update]) drains that ring every rendered frame into a rolling
 *   mono history, from which [waveform] and [spectrum] are derived.
 *
 * The ring buffer is single producer / single consumer by contract, so the two
 * halves never touch the same memory. The counters are written by the capture
 * thread and read by the UI thread for display only, which is why they are
 * [Volatile] but not otherwise synchronised.
 *
 * A capture stream is optional: when no device can be opened [open] fails, the
 * error is kept in [error] and every read accessor keeps returning silence, so
 * a headless run can still render the UI.
 */
class SpectrumAnalyzer(
    private val system: AudioSystem?,
    /** Initial analysis window in frames; must be a power of two. */
    initialWindow: Int = DEFAULT_FFT_SIZE,
) {

    init {
        require(Fft.isPowerOfTwo(initialWindow) && initialWindow >= MIN_WINDOW) {
            "the window must be a power of two >= $MIN_WINDOW: $initialWindow"
        }
    }

    /**
     * Frames of the analysis window: the FFT length, the length of [waveform]
     * and the span [peak] and [rms] are measured over. Changed through
     * [setWindow].
     */
    var fftSize: Int = initialWindow
        private set

    /** Bins [spectrum] reports, `fftSize / 2`. */
    var binCount: Int = Fft.binCount(initialWindow)
        private set

    // ==================== capture stream (UI thread) ====================

    private var input: AudioInput? = null
    private var ring: AudioRingBuffer? = null
    private var readBuffer: AudioBuffer? = null
    private var drainScratch = FloatArray(0)
    private var thread: SDLThread? = null

    /** Format the open capture stream runs with, or `null` when nothing is open. */
    val format: AudioFormat? get() = input?.format

    /** Endpoint the open capture stream runs on, or `null` for the system default. */
    val device: AudioDevice? get() = input?.device

    /** Frames the backend buffers internally, `0` when no stream is open. */
    val bufferFrames: Int get() = input?.bufferFrames ?: 0

    /** Samples per second of the open stream, `0` when nothing is open. */
    val sampleRate: Int get() = format?.sampleRate ?: 0

    // ==================== analysis (UI thread) ====================

    /** Frames of mono history kept for the waveform and the FFT window. */
    private var historyFrames: Int = maxOf(fftSize, MIN_HISTORY_FRAMES)
    private var history = FloatArray(historyFrames)
    private var historyWrite = 0L
    private var window = Fft.hannWindow(fftSize)
    private var scratch = FloatArray(2 * fftSize)
    private var samples = FloatArray(fftSize)
    private var bins = FloatArray(binCount)

    /**
     * Changes the analysis window. The FFT, the waveform and the level meters
     * are rebuilt for [frames]; the open stream and its ring buffer are kept,
     * so a capture keeps running across the change.
     *
     * @param frames window length in frames, a power of two >= [MIN_WINDOW].
     */
    fun setWindow(frames: Int) {
        require(Fft.isPowerOfTwo(frames) && frames >= MIN_WINDOW) {
            "the window must be a power of two >= $MIN_WINDOW: $frames"
        }
        if (frames == fftSize) return
        fftSize = frames
        binCount = Fft.binCount(frames)
        historyFrames = maxOf(frames, MIN_HISTORY_FRAMES)
        history = FloatArray(historyFrames)
        window = Fft.hannWindow(frames)
        scratch = FloatArray(2 * frames)
        samples = FloatArray(frames)
        bins = FloatArray(binCount)
        resetAnalysis()
    }

    /** Highest absolute sample of the last analysed window. */
    var peak: Float = 0f
        private set

    /** Root mean square of the last analysed window. */
    var rms: Float = 0f
        private set

    // ==================== statistics ====================

    /** Frames the capture thread has read from the device. */
    @Volatile
    var capturedFrames: Long = 0
        private set

    /** Frames the capture thread had to drop because the ring was full. */
    @Volatile
    var overruns: Long = 0
        private set

    /** UI frames that found the ring empty while capturing. */
    @Volatile
    var underruns: Long = 0
        private set

    /** Frames per second measured on the capture thread. */
    @Volatile
    var captureRate: Double = 0.0
        private set

    /** `true` while the capture thread is running. */
    @Volatile
    var isCapturing: Boolean = false
        private set

    /** Message of the last failed open/start, or `null`. */
    @Volatile
    var error: String? = null
        private set

    /** Frames currently waiting in the ring for [update]. */
    val bufferedFrames: Int get() = ring?.availableToRead ?: 0

    // ==================== recording (UI thread) ====================

    /**
     * `true` while [update] appends the frames it drains to the recording.
     * Only the UI thread reads or writes the recording, the capture thread
     * never touches it.
     */
    var isRecording: Boolean = false
        private set

    /** Format the recording was captured with, `null` while nothing is recorded. */
    var recordedFormat: AudioFormat? = null
        private set

    /** Frames in the recording. */
    val recordedFrames: Int get() = recordedFrameCount

    /** Duration of the recording. */
    val recordedMillis: Double get() = recordedFormat?.framesToMillis(recordedFrameCount) ?: 0.0

    /** `true` when the recording hit its limit and stopped by itself. */
    val recordingFull: Boolean get() = recordedCapacityFrames > 0 && recordedFrameCount >= recordedCapacityFrames

    private var recordedFrameCount = 0
    private var recordedCapacityFrames = 0
    private var recorded = FloatArray(0)

    @Volatile
    private var captureRequested = false

    private var rateMark = TimeSource.Monotonic.markNow()
    private var rateFrames = 0L

    init {
        resetAnalysis()
    }

    // ==================== lifecycle ====================

    /**
     * Opens (or reopens) the capture stream. Any previous stream is stopped and
     * closed first; on failure [error] describes why and the previous stream is
     * gone.
     *
     * @param device endpoint from [AudioSystem.inputDevices], or `null` for the
     *   system default.
     * @param bufferFrames device buffering in frames, `0` for the backend default.
     */
    fun open(format: AudioFormat, device: AudioDevice? = null, bufferFrames: Int = 0): Boolean {
        stopCapture()
        closeInput()
        val system = system ?: return fail("no audio backend is available")
        return try {
            val stream = system.openInput(format, device, bufferFrames)
            val ringFrames = maxOf(fftSize * 4, RING_FRAMES)
            ring = AudioRingBuffer(stream.format, ringFrames)
            drainScratch = FloatArray(ringFrames * stream.format.channelCount)
            readBuffer = AudioBuffer(stream.format, minOf(maxOf(stream.bufferFrames, fftSize), ringFrames))
            input = stream
            error = null
            // The recording is interleaved with the stream's channel count, so
            // a new stream invalidates it.
            clearRecording()
            resetAnalysis()
            true
        } catch (e: Exception) {
            fail("cannot open ${device?.name ?: "the default capture device"}: ${e.message ?: e.toString()}")
        }
    }

    /**
     * Starts the device and the reader thread. Returns `false` (and fills
     * [error]) when no stream is open or the platform refuses to start it.
     */
    fun startCapture(): Boolean {
        if (isCapturing) return true
        val stream = input ?: return fail("no capture stream is open")
        error = null
        try {
            stream.start()
        } catch (e: Exception) {
            return fail("cannot start capture: ${e.message ?: e.toString()}")
        }
        captureRequested = true
        rateMark = TimeSource.Monotonic.markNow()
        rateFrames = 0
        // Set before the thread starts so an immediate failure on the reader
        // side is not overwritten with a stale "capturing" state.
        isCapturing = true
        // SDL is the only thread primitive reachable from common code and it
        // covers every target of this example, so the reader thread comes from
        // there instead of a per-platform `Thread` / `pthread_create`.
        val reader = SDLThreads.createThread("audio-visualizer-capture") { capture { captureRequested } }
        if (reader == null) {
            captureRequested = false
            isCapturing = false
            runCatching { stream.stop() }
            return fail("cannot create the capture thread")
        }
        thread = reader
        return true
    }

    /**
     * Stops the device and waits for the reader thread to finish. Safe to call
     * when nothing is running.
     */
    fun stopCapture() {
        captureRequested = false
        val reader = thread
        thread = null
        if (reader != null) {
            // Unblocks a thread sitting in AudioInput.read, which then returns -1.
            runCatching { input?.stop() }
            reader.wait()
        }
        isCapturing = false
    }

    /** Stops capture and releases the stream. */
    fun close() {
        stopCapture()
        closeInput()
    }

    private fun closeInput() {
        val stream = input ?: return
        input = null
        ring = null
        readBuffer = null
        drainScratch = FloatArray(0)
        runCatching { stream.close() }
    }

    private fun fail(message: String): Boolean {
        error = message
        return false
    }

    // ==================== audio thread side ====================

    /**
     * Performs one blocking read and hands the frames to the ring.
     *
     * @return frames read (may be `0` when the device has nothing ready yet), or
     *   `-1` when the stream was stopped or closed.
     */
    fun readOnce(): Int {
        val stream = input ?: return -1
        val buffer = readBuffer ?: return -1
        val frames = stream.read(buffer)
        if (frames <= 0) return frames
        val written = ring?.write(buffer, frames) ?: 0
        if (written < frames) overruns += (frames - written).toLong()
        capturedFrames += frames.toLong()
        measureCaptureRate(frames)
        return frames
    }

    /**
     * Reader loop: consumes the device until [continueLoop] turns `false` or the
     * stream stops delivering. Runs on the background thread, never returns on
     * its own while audio flows.
     */
    fun capture(continueLoop: () -> Boolean) {
        while (continueLoop()) {
            val frames = try {
                readOnce()
            } catch (e: Exception) {
                fail("capture failed: ${e.message ?: e.toString()}")
                break
            }
            if (frames < 0) break
        }
        isCapturing = false
    }

    private fun measureCaptureRate(frames: Int) {
        rateFrames += frames.toLong()
        val elapsed = rateMark.elapsedNow().inWholeMilliseconds
        if (elapsed >= RATE_WINDOW_MILLIS) {
            captureRate = rateFrames * 1000.0 / elapsed
            rateFrames = 0
            rateMark = TimeSource.Monotonic.markNow()
        }
    }

    // ==================== UI thread side ====================

    /**
     * Drains every frame the capture thread buffered into the rolling mono
     * history and refreshes the level meters and the spectrum. Call once per
     * rendered frame.
     *
     * @return frames appended to the history.
     */
    fun update(): Int {
        val ring = ring ?: return consumeIdle()
        val channels = ring.format.channelCount
        val available = ring.availableToRead
        if (available <= 0) return consumeIdle()
        val frames = ring.read(drainScratch)
        if (frames <= 0) return consumeIdle()
        for (frame in 0 until frames) {
            var sum = 0f
            for (channel in 0 until channels) sum += drainScratch[frame * channels + channel]
            history[(historyWrite % historyFrames).toInt()] = sum / channels
            historyWrite++
        }
        if (isRecording) appendToRecording(frames)
        measureLevels()
        analyze()
        return frames
    }

    /**
     * Starts recording into a buffer of up to [MAX_RECORD_SECONDS] seconds (and
     * [MAX_RECORD_SAMPLES] samples) at the open stream's rate. What was recorded
     * before is discarded.
     *
     * @return `false` (and fills [error]) when no stream is open.
     */
    fun startRecording(): Boolean {
        val format = format ?: return fail("no capture stream is open")
        val byTime = format.millisToFrames(MAX_RECORD_SECONDS * 1000.0)
        val byMemory = MAX_RECORD_SAMPLES / format.channelCount
        recordedCapacityFrames = minOf(byTime, byMemory).coerceAtLeast(1)
        recordedFrameCount = 0
        val samples = recordedCapacityFrames * format.channelCount
        if (recorded.size < samples) recorded = FloatArray(samples)
        recordedFormat = format
        isRecording = true
        return true
    }

    /** Stops appending to the recording; what was recorded stays available. */
    fun stopRecording() {
        isRecording = false
    }

    /** Discards the recording. */
    fun clearRecording() {
        isRecording = false
        recordedFrameCount = 0
        recordedCapacityFrames = 0
        recordedFormat = null
    }

    /**
     * A copy of the recorded frames as interleaved floats in `-1..1`. Played
     * back as `PCM_F32` with the channel count and rate of [recordedFormat].
     */
    fun recordedSnapshot(): FloatArray {
        val channels = recordedFormat?.channelCount ?: 1
        return recorded.copyOf(recordedFrameCount * channels)
    }

    private fun appendToRecording(frames: Int) {
        val channels = recordedFormat?.channelCount ?: return
        val take = minOf(frames, recordedCapacityFrames - recordedFrameCount)
        if (take <= 0) return
        drainScratch.copyInto(recorded, recordedFrameCount * channels, 0, take * channels)
        recordedFrameCount += take
        if (recordedFrameCount >= recordedCapacityFrames) isRecording = false
    }

    private fun consumeIdle(): Int {
        if (isCapturing) underruns++
        measureLevels()
        analyze()
        return 0
    }

    /**
     * Copies the most recent [destination] samples in chronological order,
     * zero filling the part of the window that was never captured.
     */
    fun waveform(destination: FloatArray): Int {
        val count = destination.size
        val start = historyWrite - count
        for (i in 0 until count) {
            val index = start + i
            destination[i] = if (index < 0) 0f else history[(index % historyFrames).toInt()]
        }
        return count
    }

    /**
     * The dB magnitudes of the last analysed window, [binCount] entries,
     * low frequency first. The array is reused between frames.
     */
    fun spectrum(): FloatArray = bins

    private fun analyze() {
        waveform(samples)
        val written = Fft.magnitudesDb(samples, window, scratch)
        for (bin in 0 until written) bins[bin] = scratch[bin]
    }

    private fun measureLevels() {
        val count = minOf(fftSize.toLong(), historyWrite).toInt()
        if (count <= 0) {
            peak = 0f
            rms = 0f
            return
        }
        var highest = 0f
        var sumSquares = 0.0
        for (i in 0 until count) {
            val index = historyWrite - 1 - i
            val value = history[(index % historyFrames).toInt()]
            val level = if (value < 0f) -value else value
            if (level > highest) highest = level
            sumSquares += value.toDouble() * value
        }
        peak = highest
        rms = sqrt(sumSquares / count).toFloat()
    }

    private fun resetAnalysis() {
        history.fill(0f)
        historyWrite = 0
        samples.fill(0f)
        scratch.fill(0f)
        bins.fill(Fft.MIN_DB)
        peak = 0f
        rms = 0f
        capturedFrames = 0
        overruns = 0
        underruns = 0
        captureRate = 0.0
    }

    companion object {
        /** Analysis window the example opens with: 1024 frames at 48 kHz is ~21 ms. */
        const val DEFAULT_FFT_SIZE: Int = 1024

        /** Shortest analysis window, four times the waveform resolution of the UI. */
        const val MIN_WINDOW: Int = 64

        /** Longest recording in seconds. */
        const val MAX_RECORD_SECONDS: Double = 30.0

        /** Upper bound of the recording memory: 16 MiB of float samples. */
        const val MAX_RECORD_SAMPLES: Int = 4 * 1024 * 1024

        /** Ring capacity in frames; several FFT windows so UI hiccups are absorbed. */
        private const val RING_FRAMES: Int = 8192

        /** Mono history long enough for the waveform and the FFT window. */
        private const val MIN_HISTORY_FRAMES: Int = 4096

        private const val RATE_WINDOW_MILLIS: Long = 500
    }
}

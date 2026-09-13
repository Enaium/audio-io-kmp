package cn.enaium.audio.example.visualizer

import cn.enaium.audio.AudioDevice
import cn.enaium.audio.AudioFormat
import cn.enaium.audio.AudioSystem
import cn.enaium.audio.SampleFormat
import cn.enaium.imgui.ImGui
import cn.enaium.imgui.ImGuiChildFlags
import cn.enaium.imgui.ImGuiCond
import cn.enaium.imgui.ImGuiWindowFlags
import cn.enaium.imgui.backends.sdl.ImGuiSdlRendererBackend
import cn.enaium.imgui.ImVec2
import cn.enaium.imgui.ImVec4
import cn.enaium.imgui.extensions.implot.ImPlot
import cn.enaium.imgui.extensions.implot.ImPlotColormap
import cn.enaium.imgui.extensions.implot.ImPlotCond
import cn.enaium.imgui.extensions.implot.ImPlotFlags
import cn.enaium.imgui.extensions.implot.ImPlotItemFlags
import cn.enaium.imgui.extensions.implot.ImPlotSpec
import cn.enaium.sdl.SDLRenderer
import kotlin.math.log10
import kotlin.math.round
import kotlin.time.TimeSource

/**
 * ImGui / ImPlot front end of the visualizer.
 *
 * The left pane configures the capture stream (device, sample rate, sample
 * format, channels and device buffering) and shows the statistics; the right
 * pane stacks the waveform, the spectrum and the waterfall. Everything is drawn
 * from the buffers [SpectrumAnalyzer] keeps up to date, so the window renders
 * even when no capture device could be opened.
 */
class VisualizerUi(
    private val system: AudioSystem?,
    private val analyzer: SpectrumAnalyzer,
    /** Renderer the waterfall texture is created with. */
    renderer: SDLRenderer,
    /** Backend the waterfall texture is registered with. */
    backend: ImGuiSdlRendererBackend,
    /** Interface scale of the display, `1` on a desktop monitor. */
    private val uiScale: Float = 1f,
) : AutoCloseable {

    /** Width of the controls pane, scaled to the display. */
    private val controlsWidth: Float = CONTROLS_WIDTH * uiScale

    /** Height of the buttons of the controls pane, scaled to the display. */
    private val buttonSize: ImVec2 = ImVec2(90f * uiScale, 0f)

    /** Smallest height a plot is allowed to shrink to, scaled to the display. */
    private val minimumPlotHeight: Float = 90f * uiScale

    private val devices: List<AudioDevice> =
        runCatching { system?.inputDevices().orEmpty() }.getOrDefault(emptyList())

    /**
     * Index 0 is the system default (`device = null`, the path every backend
     * resolves itself); the enumerated devices follow.
     */
    private val deviceLabels: Array<String> =
        (listOf(SYSTEM_DEFAULT_LABEL) + devices.map { it.name }).toTypedArray()
    private val deviceIndex = IntArray(1)

    private val formatLabels: Array<String> = SampleFormat.entries.map { it.name }.toTypedArray()
    private val formatIndex = IntArray(1) { SampleFormat.entries.indexOf(SampleFormat.PCM_S16).coerceAtLeast(0) }

    private var rates: List<Int> = DEFAULT_SAMPLE_RATES
    private var rateLabels: Array<String> = emptyArray()
    private val rateIndex = IntArray(1)

    private var channels: List<Int> = DEFAULT_CHANNELS
    private var channelLabels: Array<String> = emptyArray()
    private val channelIndex = IntArray(1)

    private val bufferFrames = IntArray(1) { DEFAULT_BUFFER_FRAMES }
    private val colormapIndex = IntArray(1) { ImPlotColormap.VIRIDIS }
    private var colormapLabels: Array<String> = emptyArray()
    private val freezeWaterfall = BooleanArray(1)

    /** Set when a control changed something the open stream would not pick up. */
    private var dirty = true

    private val waveform = FloatArray(WAVEFORM_POINTS)
    private val bins = analyzer.binCount
    private val heat = FloatArray(WATERFALL_ROWS * WATERFALL_COLUMNS)
    private val waterfall = WaterfallTexture(
        renderer = renderer,
        backend = backend,
        columns = WATERFALL_COLUMNS,
        rows = WATERFALL_ROWS,
        scaleMin = Fft.MIN_DB,
        scaleMax = 0f,
    )
    private var waterfallDirty = true

    private var frameRate = 0.0
    private var frameMark = TimeSource.Monotonic.markNow()
    private var framesSinceMark = 0

    init {
        heat.fill(Fft.MIN_DB)
        waterfallDirty = true
        refreshDeviceOptions()
    }

    /**
     * Prints the capture path that is about to be used and opens it, starting
     * capture when a stream could be opened. Called once, after the ImPlot
     * context exists.
     */
    fun startup() {
        colormapLabels = Array(ImPlot.getColormapCount()) { ImPlot.getColormapName(it) }
        if (colormapIndex[0] !in colormapLabels.indices) colormapIndex[0] = ImPlotColormap.VIRIDIS
        println("Audio backend: ${system?.name ?: "unavailable"}")
        selectDefaultDevice()
        applySelection()
        if (analyzer.format != null) analyzer.startCapture()
    }

    /** Renders one frame. Call between [ImGui.newFrame] and [ImGui.render]. */
    fun draw(frame: Int) {
        // Drain the capture ring first: every panel below reads the result.
        val appended = analyzer.update()
        if (appended > 0 && !freezeWaterfall[0]) pushWaterfallRow()
        updateFrameRate()
        // A selection made while stopped is applied as soon as the stream is idle.
        if (dirty && !analyzer.isCapturing) applySelection()

        // The main window always covers the whole ImGui viewport: position and
        // size are re-applied every frame, so the layout follows the window on
        // every resize, and the decoration is dropped because the OS window
        // already provides it.
        ImGui.setNextWindowPos(ImVec2(0f, 0f), ImGuiCond.ALWAYS)
        ImGui.setNextWindowSize(ImGui.getIO().displaySize, ImGuiCond.ALWAYS)
        if (ImGui.begin(WINDOW_TITLE, flags = MAIN_WINDOW_FLAGS)) {
            if (frame == 0) {
                val size = ImGui.getWindowSize()
                println("Interface: ${size.x.toInt()}x${size.y.toInt()} px")
            }
            if (ImGui.beginChild("##controls", ImVec2(controlsWidth, 0f), ImGuiChildFlags.BORDERS)) {
                drawControls()
                ImGui.separator()
                drawStats(frame)
            }
            ImGui.endChild()

            ImGui.sameLine()
            if (ImGui.beginChild("##plots", ImVec2(0f, 0f), ImGuiChildFlags.BORDERS)) {
                val available = ImGui.getContentRegionAvail()
                val plotHeight = maxOf((available.y - 3f * ImGui.getTextLineHeightWithSpacing()) / 3f, minimumPlotHeight)
                drawWaveform(plotHeight)
                drawSpectrum(plotHeight)
                drawWaterfall(plotHeight)
            }
            ImGui.endChild()
        }
        ImGui.end()
    }

    override fun close() {
        analyzer.close()
        waterfall.close()
    }

    // ==================== controls ====================

    /**
     * Selects the endpoint the backend reports as default, or the system
     * default when the backend cannot tell.
     */
    private fun selectDefaultDevice() {
        // Index 0 is the system default, the enumerated devices follow it.
        val default = devices.indexOfFirst { it.isDefault }
        deviceIndex[0] = if (default >= 0 && default + 1 < deviceLabels.size) default + 1 else 0
    }

    /**
     * Rebuilds the sample rate and channel choices from the selected device.
     *
     * A device that reports what it supports limits the choices to that; the
     * system default keeps the values every platform can usually deliver.
     */
    private fun refreshDeviceOptions() {
        val device = devices.getOrNull(deviceIndex[0] - 1)

        rates = device?.sampleRates?.takeIf { it.isNotEmpty() } ?: DEFAULT_SAMPLE_RATES
        rateLabels = rates.map { it.toString() }.toTypedArray()
        rateIndex[0] = rates.indexOf(PREFERRED_SAMPLE_RATE).takeIf { it >= 0 }
            ?: rates.indexOfFirst { it >= PREFERRED_SAMPLE_RATE }.takeIf { it >= 0 }
            ?: (rates.size - 1).coerceAtLeast(0)

        channels = device?.channelCounts?.takeIf { it.isNotEmpty() } ?: DEFAULT_CHANNELS
        channelLabels = channels.map { it.toString() }.toTypedArray()
        channelIndex[0] = channels.indexOf(1).takeIf { it >= 0 } ?: 0
    }

    /**
     * Resolves the selection into an [AudioFormat] and opens the stream,
     * stopping whatever was running first. Prints the path that ended up being
     * used, which is what a headless run reports.
     */
    private fun applySelection() {
        val device = devices.getOrNull(deviceIndex[0] - 1)
        val format = AudioFormat(
            sampleRate = rates.getOrElse(rateIndex[0]) { PREFERRED_SAMPLE_RATE },
            channelCount = channels.getOrElse(channelIndex[0]) { 1 },
            sampleFormat = SampleFormat.entries[formatIndex[0]],
        )
        val opened = analyzer.open(format, device, bufferFrames[0])
        // An open that failed stays dirty so the next Start retries it.
        dirty = !opened
        if (!opened) return
        println("Capture device: ${device?.name ?: SYSTEM_DEFAULT_LABEL}")
        println("Capture format: $format")
        println("Capture stream: ${analyzer.format}, ${analyzer.bufferFrames} frames of device buffering")
    }

    private fun drawControls() {
        ImGui.text("Backend: ${system?.name ?: "unavailable"}")
        ImGui.separatorText("Capture")

        ImGui.setNextItemWidth(160f * uiScale)
        if (ImGui.combo("Device", deviceIndex, deviceLabels)) {
            refreshDeviceOptions()
            dirty = true
        }
        ImGui.setNextItemWidth(160f * uiScale)
        if (ImGui.combo("Sample rate", rateIndex, rateLabels)) dirty = true
        ImGui.setNextItemWidth(160f * uiScale)
        if (ImGui.combo("Sample format", formatIndex, formatLabels)) dirty = true
        ImGui.setNextItemWidth(160f * uiScale)
        if (ImGui.combo("Channels", channelIndex, channelLabels)) dirty = true
        ImGui.setNextItemWidth(160f * uiScale)
        if (ImGui.sliderInt("Buffer frames", bufferFrames, 0, MAX_BUFFER_FRAMES, bufferFormat())) dirty = true

        ImGui.spacing()
        ImGui.beginDisabled(analyzer.isCapturing)
        if (ImGui.button("Start", buttonSize)) {
            // Retries the open when there is nothing live, so a device that
            // appeared after launch can still be picked up.
            if (dirty || analyzer.format == null) applySelection()
            if (analyzer.format != null) analyzer.startCapture()
        }
        ImGui.endDisabled()
        ImGui.sameLine()
        ImGui.beginDisabled(!analyzer.isCapturing)
        if (ImGui.button("Stop", buttonSize)) analyzer.stopCapture()
        ImGui.endDisabled()
        if (dirty && analyzer.isCapturing) ImGui.textDisabled("stop to apply the changes")

        ImGui.separatorText("Waterfall")
        ImGui.setNextItemWidth(160f * uiScale)
        if (ImGui.combo("Colormap", colormapIndex, colormapLabels)) {
            if (colormapIndex[0] !in colormapLabels.indices) colormapIndex[0] = 0
            // The texture is re-colored from the lookup table on the next update.
            waterfallDirty = true
        }
        ImGui.checkbox("Freeze", freezeWaterfall)
        if (ImGui.button("Clear", buttonSize)) {
            heat.fill(Fft.MIN_DB)
            waterfallDirty = true
            freezeWaterfall[0] = false
        }

        val error = analyzer.error
        if (error != null) {
            ImGui.spacing()
            ImGui.textColored(ERROR_COLOR, "Capture error")
            ImGui.textWrapped(error)
        }
    }

    private fun drawStats(frame: Int) {
        ImGui.separatorText("Stats")
        ImGui.text("State: ${captureState()}")
        ImGui.text("Capture thread: ${if (analyzer.isCapturing) "running" else "stopped"}")
        ImGui.text("Frames captured: ${analyzer.capturedFrames}")
        ImGui.text("Buffered: ${analyzer.bufferedFrames} frames")
        ImGui.text("Overruns: ${analyzer.overruns}   Underruns: ${analyzer.underruns}")
        ImGui.text("Capture rate: ${decimals(analyzer.captureRate, 1)} frames/s")
        ImGui.text("Render rate: ${decimals(frameRate, 1)} fps (frame $frame)")

        ImGui.spacing()
        ImGui.progressBar(analyzer.peak, ImVec2(-1f, 0f), "peak ${decimals(analyzer.peak.toDouble(), 3)}")
        ImGui.progressBar(analyzer.rms, ImVec2(-1f, 0f), "rms ${decimals(analyzer.rms.toDouble(), 3)}")
        ImGui.text("Peak: ${decimals(db(analyzer.peak), 1)} dBFS")
        ImGui.text("RMS: ${decimals(db(analyzer.rms), 1)} dBFS")
        ImGui.text("FFT: ${analyzer.fftSize} points, $bins bins")
    }

    // ==================== plots ====================

    private fun drawWaveform(height: Float) {
        ImGui.separatorText("Waveform")
        if (ImPlot.beginPlot("##waveform", ImVec2(-1f, height), PLOT_FLAGS)) {
            val samples = analyzer.waveform(waveform)
            val millisPerSample = if (analyzer.sampleRate > 0) 1000.0 / analyzer.sampleRate else 1.0
            ImPlot.setupAxes("time (ms)", "level")
            ImPlot.setupAxesLimits(0.0, maxOf(samples * millisPerSample, 1.0), -1.0, 1.0, ImPlotCond.ALWAYS)
            ImPlot.plotLine(
                "waveform",
                waveform,
                xScale = millisPerSample,
                spec = ImPlotSpec(lineColor = WAVE_COLOR, lineWeight = 1.5f),
            )
            ImPlot.endPlot()
        }
    }

    private fun drawSpectrum(height: Float) {
        ImGui.separatorText("Spectrum")
        if (ImPlot.beginPlot("##spectrum", ImVec2(-1f, height), PLOT_FLAGS)) {
            val nyquist = if (analyzer.sampleRate > 0) analyzer.sampleRate / 2.0 else bins.toDouble()
            ImPlot.setupAxes("frequency (Hz)", "magnitude (dB)")
            ImPlot.setupAxesLimits(0.0, nyquist, Fft.MIN_DB.toDouble(), 0.0, ImPlotCond.ALWAYS)
            ImPlot.plotLine(
                "magnitude",
                analyzer.spectrum(),
                xScale = nyquist / bins,
                spec = ImPlotSpec(lineColor = SPECTRUM_COLOR, lineWeight = 1.5f),
            )
            ImPlot.endPlot()
        }
    }

    private fun drawWaterfall(height: Float) {
        ImGui.separatorText("Waterfall")
        if (ImPlot.beginPlot("##waterfall", ImVec2(-1f, height), PLOT_FLAGS or ImPlotFlags.NO_LEGEND)) {
            val nyquist = if (analyzer.sampleRate > 0) analyzer.sampleRate / 2.0 else bins.toDouble()
            ImPlot.setupAxes("frequency (Hz)", "history")
            ImPlot.setupAxesLimits(0.0, nyquist, 0.0, WATERFALL_ROWS.toDouble(), ImPlotCond.ALWAYS)
            if (waterfallDirty) {
                waterfall.update(heat, colormapIndex[0])
                waterfallDirty = false
            }
            // One texture instead of one cell per value: see [WaterfallTexture].
            ImPlot.plotImage(
                "waterfall",
                textureId = waterfall.textureId,
                xMin = 0.0,
                yMin = 0.0,
                xMax = nyquist,
                yMax = WATERFALL_ROWS.toDouble(),
                spec = ImPlotSpec(flags = ImPlotItemFlags.NO_LEGEND),
            )
            ImPlot.endPlot()
        }
    }

    private fun pushWaterfallRow() {
        for (i in heat.size - 1 downTo WATERFALL_COLUMNS) heat[i] = heat[i - WATERFALL_COLUMNS]
        val spectrum = analyzer.spectrum()
        if (spectrum.isEmpty()) return
        for (column in 0 until WATERFALL_COLUMNS) {
            val from = column * spectrum.size / WATERFALL_COLUMNS
            val to = minOf(((column + 1) * spectrum.size + WATERFALL_COLUMNS - 1) / WATERFALL_COLUMNS, spectrum.size)
            var peak = spectrum[minOf(from, spectrum.size - 1)]
            for (bin in from until to) {
                if (spectrum[bin] > peak) peak = spectrum[bin]
            }
            heat[column] = peak
        }
        waterfallDirty = true
    }

    private fun updateFrameRate() {
        framesSinceMark++
        val elapsed = frameMark.elapsedNow().inWholeMilliseconds
        if (elapsed >= RATE_WINDOW_MILLIS) {
            frameRate = framesSinceMark * 1000.0 / elapsed
            framesSinceMark = 0
            frameMark = TimeSource.Monotonic.markNow()
        }
    }

    private fun captureState(): String = when {
        analyzer.isCapturing -> "capturing"
        analyzer.format != null -> "stopped"
        else -> "no capture device"
    }

    private fun bufferFormat(): String = if (bufferFrames[0] == 0) "default" else "%d"

    private fun db(level: Float): Double =
        if (level <= 1e-5f) Fft.MIN_DB.toDouble() else 20.0 * log10(level.toDouble())

    /** Rounds [value] to [digits] decimals for the label; common code has no `String.format`. */
    private fun decimals(value: Double, digits: Int): String {
        var factor = 1.0
        repeat(digits) { factor *= 10.0 }
        return (round(value * factor) / factor).toString()
    }

    private companion object {
        const val WINDOW_TITLE = "Audio Spectrum Visualizer"
        const val CONTROLS_WIDTH = 300f
        const val WAVEFORM_POINTS = 512
        const val WATERFALL_ROWS = 64

        /**
         * Columns of the waterfall. The FFT has one bin per few hertz, far more
         * than a spectrogram needs: every cell costs four vertices in the render
         * backend, so the row is reduced to this width before it is stored.
         */
        const val WATERFALL_COLUMNS = 128
        const val MAX_BUFFER_FRAMES = 8192
        const val DEFAULT_BUFFER_FRAMES = 1024
        const val PREFERRED_SAMPLE_RATE = 48000
        const val RATE_WINDOW_MILLIS = 500L
        const val SYSTEM_DEFAULT_LABEL = "(system default)"

        const val PLOT_FLAGS = ImPlotFlags.NO_MENU or ImPlotFlags.NO_BOX_SELECT

        /**
         * Flags of the main window: it fills the viewport, so it has no title
         * bar, cannot be moved or resized by the user and never collapses.
         */
        const val MAIN_WINDOW_FLAGS = ImGuiWindowFlags.NO_TITLE_BAR or
                ImGuiWindowFlags.NO_RESIZE or
                ImGuiWindowFlags.NO_MOVE or
                ImGuiWindowFlags.NO_COLLAPSE or
                ImGuiWindowFlags.NO_BRING_TO_FRONT_ON_FOCUS


        val DEFAULT_SAMPLE_RATES = listOf(8000, 16000, 22050, 32000, 44100, 48000, 88200, 96000, 192000)
        val DEFAULT_CHANNELS = listOf(1, 2)

        val WAVE_COLOR = ImVec4(0.35f, 0.85f, 1f, 1f)
        val SPECTRUM_COLOR = ImVec4(1f, 0.72f, 0.25f, 1f)
        val ERROR_COLOR = ImVec4(1f, 0.42f, 0.42f, 1f)
    }
}

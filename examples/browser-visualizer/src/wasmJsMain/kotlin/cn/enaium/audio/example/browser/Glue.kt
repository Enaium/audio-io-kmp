package cn.enaium.audio.example.browser

import org.khronos.webgl.Float32Array

/**
 * The flat `webApp_*` functions `browser-visualizer.js` installs.
 *
 * Drawing happens in JavaScript: Kotlin only measures and hands over arrays, so
 * the visualizer does not need a canvas binding for every call.
 */
/** Wires the page controls; called once from `main`. */
internal external fun webApp_start()

internal external fun webApp_begin()

internal external fun webApp_title(text: String, x: Float, y: Float, size: Float)

internal external fun webApp_waveform(
    values: Float32Array,
    count: Int,
    x: Float,
    y: Float,
    width: Float,
    height: Float,
)

internal external fun webApp_spectrum(
    values: Float32Array,
    count: Int,
    x: Float,
    y: Float,
    width: Float,
    height: Float,
)

internal external fun webApp_waterfallRow(values: Float32Array, count: Int)

internal external fun webApp_waterfall(x: Float, y: Float, width: Float, height: Float)

internal external fun webApp_clearWaterfall()

internal external fun webApp_takeCommand(): Int

internal external fun webApp_windowSize(): Int

internal external fun webApp_setRecording(value: Boolean)

internal external fun webApp_setPlayback(value: Boolean)

internal external fun webApp_setStatus(text: String)

internal external fun webApp_width(): Int

internal external fun webApp_height(): Int

internal external fun webApp_scale(): Float

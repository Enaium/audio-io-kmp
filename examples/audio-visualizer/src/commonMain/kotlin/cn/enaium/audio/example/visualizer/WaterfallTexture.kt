package cn.enaium.audio.example.visualizer

import cn.enaium.imgui.ImTextureID
import cn.enaium.imgui.backends.sdl.ImGuiSdlRendererBackend
import cn.enaium.imgui.backends.sdl.toImTextureID
import cn.enaium.imgui.extensions.implot.ImPlot
import cn.enaium.sdl.SDLBlendMode
import cn.enaium.sdl.SDLPixelFormat
import cn.enaium.sdl.SDLRenderer
import cn.enaium.sdl.SDLScaleMode
import cn.enaium.sdl.SDLTexture
import cn.enaium.sdl.SDLTextureAccess
import kotlin.math.roundToInt

/**
 * The waterfall as a texture.
 *
 * A spectrogram is a dense scalar field: drawing it as one ImPlot heatmap cell
 * per value costs four vertices per cell in the render backend, which dominates
 * the frame time of a full window. The same data as a small texture is two
 * triangles per frame, so the resolution can be higher than a heatmap of the
 * same cost would allow.
 *
 * The class owns the texture, keeps the pixel buffer that mirrors the heat
 * matrix and turns values into colors through a lookup table sampled from the
 * ImPlot colormap, so the image matches what ImPlot would have drawn.
 */
class WaterfallTexture(
    renderer: SDLRenderer,
    /** Backend that owns the texture and draws the commands referencing it. */
    private val backend: ImGuiSdlRendererBackend,
    /** Columns of the spectrum that become the width of the image. */
    val columns: Int,
    /** Rows of history that become the height of the image. */
    val rows: Int,
    /** Lowest value of the color scale. */
    private val scaleMin: Float,
    /** Highest value of the color scale. */
    private val scaleMax: Float,
) : AutoCloseable {

    private val texture: SDLTexture = renderer.createTexture(
        format = SDLPixelFormat.RGBA32,
        access = SDLTextureAccess.STREAMING,
        width = columns,
        height = rows,
    ).apply {
        // Cells are magnified into a full width plot, so linear scaling reads
        // better than the blocky nearest neighbor.
        scaleMode = SDLScaleMode.LINEAR
        blendMode = SDLBlendMode.BLEND
    }

    /** The id to pass to `ImPlot.plotImage`. */
    val textureId: ImTextureID = texture.toImTextureID()

    init {
        backend.registerTexture(texture)
    }

    /** `columns * rows` RGBA pixels, row 0 being the newest row. */
    private val pixels = ByteArray(columns * rows * 4)

    private var colormap = -1
    private val colors = ByteArray(COLOR_STEPS * 4)

    /**
     * Copies [heat] (`rows * columns` values, row major, row 0 first) into the
     * texture.
     *
     * [colormapIndex] is the ImPlot colormap to sample; the lookup table is
     * rebuilt only when it changes.
     */
    fun update(heat: FloatArray, colormapIndex: Int) {
        if (colormapIndex != colormap) {
            buildColorTable(colormapIndex)
            colormap = colormapIndex
        }
        val span = scaleMax - scaleMin
        for (index in 0 until minOf(heat.size, columns * rows)) {
            val level = if (span <= 0f) 0 else ((heat[index] - scaleMin) / span * (COLOR_STEPS - 1)).roundToInt()
            val color = minOf(maxOf(level, 0), COLOR_STEPS - 1) * 4
            val pixel = index * 4
            pixels[pixel] = colors[color]
            pixels[pixel + 1] = colors[color + 1]
            pixels[pixel + 2] = colors[color + 2]
            pixels[pixel + 3] = colors[color + 3]
        }
        texture.update(rect = null, pixels = pixels, pitch = columns * 4)
    }

    override fun close() {
        backend.unregisterTexture(texture)
    }

    /** Samples the colormap once per level so the per pixel work is a table lookup. */
    private fun buildColorTable(colormapIndex: Int) {
        for (step in 0 until COLOR_STEPS) {
            val color = ImPlot.sampleColormap(step.toFloat() / (COLOR_STEPS - 1), colormapIndex)
            colors[step * 4] = channel(color.x)
            colors[step * 4 + 1] = channel(color.y)
            colors[step * 4 + 2] = channel(color.z)
            colors[step * 4 + 3] = channel(color.w)
        }
    }

    private fun channel(value: Float): Byte = (value.coerceIn(0f, 1f) * 255f).roundToInt().toByte()

    private companion object {
        /** Levels the colormap is sampled at. */
        const val COLOR_STEPS = 256
    }
}

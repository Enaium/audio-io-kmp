package cn.enaium.audio.example.visualizer

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Iterative radix-2 FFT and the windowing helpers the spectrum visualizer needs.
 *
 * Everything works in place on plain `FloatArray`s and allocates nothing, so one
 * set of buffers can be reused for every analysed frame. There is no dependency
 * outside `kotlin.math` and the results are deterministic for a given input.
 */
object Fft {

    /** Floor used by [magnitudesDb]; digital silence maps here instead of `-Inf`. */
    const val MIN_DB: Float = -100f

    /** `true` when [value] is a positive power of two. */
    fun isPowerOfTwo(value: Int): Boolean = value > 0 && (value and (value - 1)) == 0

    /** Number of single sided bins [magnitudesDb] produces for [fftSize] samples. */
    fun binCount(fftSize: Int): Int {
        require(isPowerOfTwo(fftSize) && fftSize >= 2) { "fftSize must be a power of two >= 2: $fftSize" }
        return fftSize / 2
    }

    /**
     * In-place iterative radix-2 decimation-in-time FFT of [n] complex points
     * packed into [data]: the real part in `data[0, n)` and the imaginary part in
     * `data[n, 2n)`.
     *
     * The twiddle factors are recomputed stage by stage instead of being read
     * from a table, which keeps the transform allocation free.
     */
    fun fft(data: FloatArray, n: Int) {
        require(data.size >= 2 * n) { "data must hold 2 * $n floats: ${data.size}" }
        require(isPowerOfTwo(n) && n >= 2) { "FFT length must be a power of two >= 2: $n" }
        val im = n

        // Bit reversal permutation: the butterflies below combine adjacent
        // blocks, which needs the input in bit reversed order.
        var reversed = 0
        for (index in 1 until n) {
            var bit = n shr 1
            while (reversed and bit != 0) {
                reversed = reversed xor bit
                bit = bit shr 1
            }
            reversed = reversed or bit
            if (index < reversed) {
                val swapRe = data[index]
                data[index] = data[reversed]
                data[reversed] = swapRe
                val swapIm = data[im + index]
                data[im + index] = data[im + reversed]
                data[im + reversed] = swapIm
            }
        }

        var length = 2
        while (length <= n) {
            val half = length shr 1
            val angle = -2.0 * PI / length
            val stepRe = cos(angle).toFloat()
            val stepIm = sin(angle).toFloat()
            var start = 0
            while (start < n) {
                var wRe = 1f
                var wIm = 0f
                for (k in 0 until half) {
                    val even = start + k
                    val odd = even + half
                    val evenRe = data[even]
                    val evenIm = data[im + even]
                    val oddRe = data[odd]
                    val oddIm = data[im + odd]
                    val productRe = oddRe * wRe - oddIm * wIm
                    val productIm = oddRe * wIm + oddIm * wRe
                    data[even] = evenRe + productRe
                    data[im + even] = evenIm + productIm
                    data[odd] = evenRe - productRe
                    data[im + odd] = evenIm - productIm
                    val nextRe = wRe * stepRe - wIm * stepIm
                    wIm = wRe * stepIm + wIm * stepRe
                    wRe = nextRe
                }
                start += length
            }
            length = length shl 1
        }
    }

    /**
     * Symmetric Hann window of [size] coefficients:
     * `0.5 - 0.5 * cos(2 * pi * i / (size - 1))`.
     */
    fun hannWindow(size: Int): FloatArray {
        require(size >= 2) { "window size must be at least 2: $size" }
        val window = FloatArray(size)
        val step = 2.0 * PI / (size - 1)
        for (i in 0 until size) window[i] = (0.5 - 0.5 * cos(step * i)).toFloat()
        return window
    }

    /**
     * Single sided magnitude spectrum of [samples] in dB.
     *
     * [samples] is windowed with [window] and transformed inside [out], which is
     * used as scratch and must hold at least `2 * samples.size` floats: the real
     * part is transformed in the first half and the imaginary part in the
     * second. On return the first `samples.size / 2` entries of [out] hold the
     * magnitudes in dB, normalised so a full scale sine reads `0 dB` and floored
     * at [MIN_DB].
     *
     * @return the number of bins written, always `samples.size / 2`.
     */
    fun magnitudesDb(samples: FloatArray, window: FloatArray, out: FloatArray): Int {
        val n = samples.size
        val bins = binCount(n)
        require(window.size >= n) { "window must cover $n samples: ${window.size}" }
        require(out.size >= 2 * n) { "out must hold at least ${2 * n} floats: ${out.size}" }

        var windowSum = 0f
        for (i in 0 until n) {
            out[i] = samples[i] * window[i]
            out[n + i] = 0f
            windowSum += window[i]
        }
        if (windowSum <= 0f) windowSum = 1f
        fft(out, n)

        for (bin in 0 until bins) {
            val re = out[bin]
            val im = out[n + bin]
            val magnitude = sqrt(re * re + im * im)
            // The window costs half of the coherent gain; doubling folds the
            // negative frequencies of a real signal back onto their positive
            // bin. DC has no mirror image, so it is left alone.
            val amplitude = magnitude * (if (bin == 0) 1f else 2f) / windowSum
            val db = 20f * log10(if (amplitude < 1e-5f) 1e-5f else amplitude)
            out[bin] = if (db < MIN_DB) MIN_DB else db
        }
        return bins
    }
}

package cn.enaium.audio

/**
 * A chunk of interleaved PCM: a fixed capacity plus the number of frames that
 * currently hold valid samples.
 *
 * The buffer is deliberately free of any cursor: [AudioInput.read] fills it and
 * sets [frameCount], [AudioOutput.write] consumes [frameCount] frames, and the
 * `shorts`/`floats`/... accessors convert as many samples as the caller asks
 * for. Counts are expressed in *samples* (frames times channels) for the typed
 * accessors and in *bytes* for the byte sized ones.
 *
 * [data] is the raw backing store. Backends read and write it directly so a
 * blocking device call never needs an intermediate copy; it must never be
 * resized or replaced.
 */
class AudioBuffer(
    /** Layout of the samples stored in this buffer. */
    val format: AudioFormat,
    /** Frames the buffer can hold. */
    val capacityFrames: Int,
) {
    init {
        require(capacityFrames > 0) { "capacityFrames must be positive: $capacityFrames" }
    }

    /** Raw little-endian PCM storage, `capacityFrames * format.frameSizeBytes` bytes. */
    val data: ByteArray = ByteArray(format.framesToBytes(capacityFrames))

    /**
     * Frames that hold valid samples.
     *
     * Producers (device callbacks, file readers, generators) set this after
     * filling [data]; consumers read it to know how much is available.
     */
    var frameCount: Int = 0
        set(value) {
            require(value in 0..capacityFrames) {
                "frameCount must be between 0 and $capacityFrames: $value"
            }
            field = value
        }

    /** Size of the backing store in bytes. */
    val capacityBytes: Int get() = data.size

    /** Bytes occupied by the valid frames. */
    val byteCount: Int get() = format.framesToBytes(frameCount)

    /** Samples occupied by the valid frames, all channels included. */
    val sampleCount: Int get() = frameCount * format.channelCount

    /** Samples the buffer can hold, all channels included. */
    val capacitySamples: Int get() = capacityFrames * format.channelCount

    /** Playback duration of the valid frames in milliseconds. */
    val durationMillis: Double get() = format.framesToMillis(frameCount)

    /** `true` when no frame is valid. */
    val isEmpty: Boolean get() = frameCount == 0

    /** Zeroes the backing store and invalidates every frame. */
    fun clear() {
        data.fill(0)
        frameCount = 0
    }

    // ==================== reading the valid frames ====================

    /**
     * The first [count] bytes of the valid frames.
     *
     * [count] is clamped to the valid region, so asking for a fixed number of
     * bytes is always safe: a partial device read simply yields fewer bytes.
     */
    fun bytes(count: Int = byteCount): ByteArray = data.copyOf(minOf(count, byteCount))

    /** The first [count] samples of the valid frames converted to `Short`. */
    fun shorts(count: Int = sampleCount): ShortArray {
        val n = minOf(count, sampleCount)
        val result = ShortArray(n)
        for (i in 0 until n) result[i] = Pcm.toShort(data, i * format.sampleFormat.bytesPerSample, format.sampleFormat)
        return result
    }

    /** The first [count] samples of the valid frames converted to `Int`. */
    fun ints(count: Int = sampleCount): IntArray {
        val n = minOf(count, sampleCount)
        val result = IntArray(n)
        for (i in 0 until n) result[i] = Pcm.toInt(data, i * format.sampleFormat.bytesPerSample, format.sampleFormat)
        return result
    }

    /** The first [count] samples of the valid frames converted to `Float` in `-1..1`. */
    fun floats(count: Int = sampleCount): FloatArray {
        val n = minOf(count, sampleCount)
        val result = FloatArray(n)
        for (i in 0 until n) result[i] = Pcm.toFloat(data, i * format.sampleFormat.bytesPerSample, format.sampleFormat)
        return result
    }

    /** The first [count] samples of the valid frames converted to `Double` in `-1..1`. */
    fun doubles(count: Int = sampleCount): DoubleArray {
        val n = minOf(count, sampleCount)
        val result = DoubleArray(n)
        for (i in 0 until n) result[i] = Pcm.toDouble(data, i * format.sampleFormat.bytesPerSample, format.sampleFormat)
        return result
    }

    /** Converts the valid frames into [destination] without allocating. Returns the samples written. */
    fun toShorts(destination: ShortArray, count: Int = destination.size): Int {
        val n = minOf(count, destination.size, sampleCount)
        for (i in 0 until n) destination[i] = Pcm.toShort(data, i * format.sampleFormat.bytesPerSample, format.sampleFormat)
        return n
    }

    /** Converts the valid frames into [destination] without allocating. Returns the samples written. */
    fun toFloats(destination: FloatArray, count: Int = destination.size): Int {
        val n = minOf(count, destination.size, sampleCount)
        for (i in 0 until n) destination[i] = Pcm.toFloat(data, i * format.sampleFormat.bytesPerSample, format.sampleFormat)
        return n
    }

    // ==================== filling the buffer ====================

    /** Copies up to [count] bytes into the buffer and returns the frames written. */
    fun putBytes(source: ByteArray, count: Int = source.size): Int {
        val n = minOf(count, source.size, capacityBytes)
        source.copyInto(data, 0, 0, n)
        frameCount = format.bytesToFrames(n)
        return frameCount
    }

    /** Converts the first [count] samples of [source] into the buffer and returns the frames written. */
    fun putShorts(source: ShortArray, count: Int = source.size): Int {
        val n = minOf(count, source.size, capacitySamples)
        val size = format.sampleFormat.bytesPerSample
        for (i in 0 until n) Pcm.putShort(data, i * size, format.sampleFormat, source[i])
        frameCount = n / format.channelCount
        return frameCount
    }

    /** Converts the first [count] samples of [source] into the buffer and returns the frames written. */
    fun putFloats(source: FloatArray, count: Int = source.size): Int {
        val n = minOf(count, source.size, capacitySamples)
        val size = format.sampleFormat.bytesPerSample
        for (i in 0 until n) Pcm.putFloat(data, i * size, format.sampleFormat, source[i])
        frameCount = n / format.channelCount
        return frameCount
    }

    /** Converts the first [count] samples of [source] into the buffer and returns the frames written. */
    fun putInts(source: IntArray, count: Int = source.size): Int {
        val n = minOf(count, source.size, capacitySamples)
        val size = format.sampleFormat.bytesPerSample
        for (i in 0 until n) Pcm.putInt(data, i * size, format.sampleFormat, source[i])
        frameCount = n / format.channelCount
        return frameCount
    }

    /** Converts the first [count] samples of [source] into the buffer and returns the frames written. */
    fun putDoubles(source: DoubleArray, count: Int = source.size): Int {
        val n = minOf(count, source.size, capacitySamples)
        val size = format.sampleFormat.bytesPerSample
        for (i in 0 until n) Pcm.putDouble(data, i * size, format.sampleFormat, source[i])
        frameCount = n / format.channelCount
        return frameCount
    }

    /** Copies raw frames from [source], which must use the same format. Returns the frames copied. */
    fun copyFrom(source: AudioBuffer, frames: Int = minOf(source.frameCount, capacityFrames)): Int {
        require(source.format == format) { "format mismatch: $format != ${source.format}" }
        val n = minOf(frames, source.frameCount, capacityFrames)
        source.data.copyInto(data, 0, 0, format.framesToBytes(n))
        frameCount = n
        return n
    }

    /** A new buffer holding a copy of [frames] frames starting at [startFrame]. */
    fun slice(startFrame: Int, frames: Int = frameCount - startFrame): AudioBuffer {
        require(startFrame >= 0 && startFrame <= frameCount) { "startFrame out of range: $startFrame" }
        require(frames >= 0 && startFrame + frames <= frameCount) { "frame range out of bounds: $startFrame + $frames" }
        val result = AudioBuffer(format, maxOf(frames, 1))
        result.frameCount = frames
        data.copyInto(result.data, 0, format.framesToBytes(startFrame), format.framesToBytes(startFrame + frames))
        return result
    }

    /** A buffer of [frames] frames with the same format. */
    fun sameFormat(frames: Int): AudioBuffer = AudioBuffer(format, frames)

    override fun toString(): String = "AudioBuffer($format, $frameCount/$capacityFrames frames)"

    companion object {
        /** A buffer of [frames] frames in [format]. */
        fun of(format: AudioFormat, frames: Int): AudioBuffer = AudioBuffer(format, frames)

        /** A buffer holding [samples] interleaved samples in [format]. */
        fun ofSamples(format: AudioFormat, samples: ShortArray): AudioBuffer =
            AudioBuffer(format, maxOf(samples.size / format.channelCount, 1)).apply { putShorts(samples) }
    }
}

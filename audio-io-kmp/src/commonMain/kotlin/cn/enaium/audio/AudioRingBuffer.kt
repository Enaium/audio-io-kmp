package cn.enaium.audio

/**
 * Fixed size single producer / single consumer ring buffer of PCM frames.
 *
 * The buffer itself is not thread safe: one thread may only call the write side
 * (`write`, `availableToWrite`) and one thread — typically a different one —
 * may only call the read side (`read`, `availableToRead`, `skip`). That is
 * exactly the shape of a capture thread feeding a rendering or processing
 * thread, and it is what lets the audio callback stay allocation free.
 *
 * Byte level access is frame aligned: a write of a partial frame is rejected
 * rather than silently splitting a sample across the wrap-around point.
 */
class AudioRingBuffer(
    /** Layout of the samples stored in this ring buffer. */
    val format: AudioFormat,
    /** Ring capacity in frames. */
    val capacityFrames: Int,
) {
    init {
        require(capacityFrames > 0) { "capacityFrames must be positive: $capacityFrames" }
    }

    private val frameSize = format.frameSizeBytes
    private val storage = ByteArray(format.framesToBytes(capacityFrames))

    /** Absolute frame index the next read starts at. */
    private var readFrame = 0L

    /** Absolute frame index the next write starts at. */
    private var writeFrame = 0L

    /** Frames that can be read. */
    val availableToRead: Int get() = (writeFrame - readFrame).toInt()

    /** Frames that can be written before the ring is full. */
    val availableToWrite: Int get() = capacityFrames - availableToRead

    /** `true` when no frame is buffered. */
    val isEmpty: Boolean get() = availableToRead == 0

    /** `true` when the ring holds [capacityFrames] frames. */
    val isFull: Boolean get() = availableToWrite == 0

    /** Playback duration of the buffered frames in milliseconds. */
    val bufferedMillis: Double get() = format.framesToMillis(availableToRead)

    /** Drops every buffered frame. Both sides must be quiesced. */
    fun clear() {
        readFrame = 0
        writeFrame = 0
        storage.fill(0)
    }

    // ==================== write side ====================

    /**
     * Copies [count] bytes of [source] into the ring and returns the frames
     * written, which is capped by [availableToWrite].
     */
    fun write(source: ByteArray, offset: Int = 0, count: Int = source.size - offset): Int {
        require(count % frameSize == 0) { "count must be frame aligned: $count % $frameSize" }
        val frames = minOf(count / frameSize, availableToWrite)
        var remaining = frames
        var sourceOffset = offset
        while (remaining > 0) {
            val target = (writeFrame % capacityFrames).toInt()
            val chunk = minOf(remaining, capacityFrames - target)
            source.copyInto(storage, target * frameSize, sourceOffset, sourceOffset + chunk * frameSize)
            writeFrame += chunk
            remaining -= chunk
            sourceOffset += chunk * frameSize
        }
        return frames
    }

    /** Writes [frames] frames of interleaved [source] samples. */
    fun write(source: ShortArray, frames: Int = source.size / format.channelCount): Int {
        val size = format.sampleFormat.bytesPerSample
        val n = minOf(frames, source.size / format.channelCount, availableToWrite)
        for (frame in 0 until n) {
            val target = ((writeFrame + frame) % capacityFrames).toInt() * frameSize
            for (channel in 0 until format.channelCount) {
                Pcm.putShort(storage, target + channel * size, format.sampleFormat, source[frame * format.channelCount + channel])
            }
        }
        writeFrame += n
        return n
    }

    /** Writes [frames] frames of interleaved [source] samples, values in `-1..1`. */
    fun write(source: FloatArray, frames: Int = source.size / format.channelCount): Int {
        val size = format.sampleFormat.bytesPerSample
        val n = minOf(frames, source.size / format.channelCount, availableToWrite)
        for (frame in 0 until n) {
            val target = ((writeFrame + frame) % capacityFrames).toInt() * frameSize
            for (channel in 0 until format.channelCount) {
                Pcm.putFloat(storage, target + channel * size, format.sampleFormat, source[frame * format.channelCount + channel])
            }
        }
        writeFrame += n
        return n
    }

    /** Writes up to [frames] frames of [buffer]. */
    fun write(buffer: AudioBuffer, frames: Int = buffer.frameCount): Int {
        require(buffer.format == format) { "format mismatch: $format != ${buffer.format}" }
        val n = minOf(frames, buffer.frameCount, availableToWrite)
        var remaining = n
        while (remaining > 0) {
            val target = (writeFrame % capacityFrames).toInt()
            val chunk = minOf(remaining, capacityFrames - target)
            buffer.data.copyInto(
                storage,
                target * frameSize,
                format.framesToBytes(n - remaining),
                format.framesToBytes(n - remaining + chunk),
            )
            writeFrame += chunk
            remaining -= chunk
        }
        return n
    }

    // ==================== read side ====================

    /**
     * Copies at most [count] bytes out of the ring into [destination] and
     * returns the frames read, capped by [availableToRead].
     */
    fun read(destination: ByteArray, offset: Int = 0, count: Int = destination.size - offset): Int {
        require(count % frameSize == 0) { "count must be frame aligned: $count % $frameSize" }
        val frames = minOf(count / frameSize, availableToRead)
        var remaining = frames
        var targetOffset = offset
        while (remaining > 0) {
            val source = (readFrame % capacityFrames).toInt()
            val chunk = minOf(remaining, capacityFrames - source)
            storage.copyInto(destination, targetOffset, source * frameSize, (source + chunk) * frameSize)
            readFrame += chunk
            remaining -= chunk
            targetOffset += chunk * frameSize
        }
        return frames
    }

    /** Reads [frames] frames into interleaved [destination] samples. */
    fun read(destination: ShortArray, frames: Int = destination.size / format.channelCount): Int {
        val size = format.sampleFormat.bytesPerSample
        val n = minOf(frames, destination.size / format.channelCount, availableToRead)
        for (frame in 0 until n) {
            val source = ((readFrame + frame) % capacityFrames).toInt() * frameSize
            for (channel in 0 until format.channelCount) {
                destination[frame * format.channelCount + channel] =
                    Pcm.toShort(storage, source + channel * size, format.sampleFormat)
            }
        }
        readFrame += n
        return n
    }

    /** Reads [frames] frames into interleaved [destination] samples, values in `-1..1`. */
    fun read(destination: FloatArray, frames: Int = destination.size / format.channelCount): Int {
        val size = format.sampleFormat.bytesPerSample
        val n = minOf(frames, destination.size / format.channelCount, availableToRead)
        for (frame in 0 until n) {
            val source = ((readFrame + frame) % capacityFrames).toInt() * frameSize
            for (channel in 0 until format.channelCount) {
                destination[frame * format.channelCount + channel] =
                    Pcm.toFloat(storage, source + channel * size, format.sampleFormat)
            }
        }
        readFrame += n
        return n
    }

    /** Fills up to [frames] frames of [buffer] from the ring and returns the frames read. */
    fun read(buffer: AudioBuffer, frames: Int = buffer.capacityFrames): Int {
        require(buffer.format == format) { "format mismatch: $format != ${buffer.format}" }
        val n = minOf(frames, buffer.capacityFrames, availableToRead)
        var remaining = n
        while (remaining > 0) {
            val source = (readFrame % capacityFrames).toInt()
            val chunk = minOf(remaining, capacityFrames - source)
            storage.copyInto(
                buffer.data,
                format.framesToBytes(n - remaining),
                source * frameSize,
                (source + chunk) * frameSize,
            )
            readFrame += chunk
            remaining -= chunk
        }
        buffer.frameCount = n
        return n
    }

    /** Drops up to [frames] buffered frames. Returns the frames dropped. */
    fun skip(frames: Int): Int {
        val n = minOf(frames, availableToRead)
        readFrame += n
        return n
    }

    override fun toString(): String =
        "AudioRingBuffer($format, $availableToRead/$capacityFrames frames, ${format.framesToMillis(availableToRead)} ms)"
}

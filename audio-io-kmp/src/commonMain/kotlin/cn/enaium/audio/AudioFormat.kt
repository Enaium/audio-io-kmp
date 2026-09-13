package cn.enaium.audio

/**
 * Description of an interleaved PCM stream: sample rate, channel count and
 * sample layout.
 *
 * A *frame* is one sample for every channel, so a frame of a stereo 16 bit
 * stream is four bytes. Backends open a stream for exactly this format or fail
 * with an [AudioException]; nothing is resampled behind the caller's back.
 */
data class AudioFormat(
    /** Frames per second, for example 44100 or 48000. */
    val sampleRate: Int,
    /** Number of interleaved channels, `1` for mono. */
    val channelCount: Int,
    /** Storage of a single sample. */
    val sampleFormat: SampleFormat,
) {
    init {
        require(sampleRate > 0) { "sampleRate must be positive: $sampleRate" }
        require(channelCount in 1..MAX_CHANNELS) {
            "channelCount must be between 1 and $MAX_CHANNELS: $channelCount"
        }
    }

    /** Size of one frame in bytes. */
    val frameSizeBytes: Int = channelCount * sampleFormat.bytesPerSample

    /** Bytes needed to hold [frames] frames. */
    fun framesToBytes(frames: Int): Int = frames * frameSizeBytes

    /** Frames contained in [bytes] bytes, rounded down. */
    fun bytesToFrames(bytes: Int): Int = bytes / frameSizeBytes

    /** Duration of [frames] frames in milliseconds. */
    fun framesToMillis(frames: Int): Double = frames * 1000.0 / sampleRate

    /** Frames that fit into [millis] milliseconds. */
    fun millisToFrames(millis: Double): Int = (millis * sampleRate / 1000.0).toInt()

    /** A copy of this format with a different [sampleRate]. */
    fun withSampleRate(sampleRate: Int): AudioFormat = copy(sampleRate = sampleRate)

    /** A copy of this format with a different [channelCount]. */
    fun withChannelCount(channelCount: Int): AudioFormat = copy(channelCount = channelCount)

    /** A copy of this format with a different [sampleFormat]. */
    fun withSampleFormat(sampleFormat: SampleFormat): AudioFormat = copy(sampleFormat = sampleFormat)

    /** `"48000 Hz, 2 ch, PCM_S16"`. */
    override fun toString(): String = "$sampleRate Hz, $channelCount ch, $sampleFormat"

    companion object {
        /** Highest channel count any backend is expected to handle. */
        const val MAX_CHANNELS: Int = 32

        /** 44100 Hz, 16 bit, stereo. */
        val CD_QUALITY: AudioFormat = AudioFormat(44100, 2, SampleFormat.PCM_S16)

        /** 48000 Hz, 16 bit, mono — the usual capture format for speech. */
        val SPEECH: AudioFormat = AudioFormat(48000, 1, SampleFormat.PCM_S16)

        /** 48000 Hz, 32 bit float, stereo — the usual studio mix format. */
        val STUDIO: AudioFormat = AudioFormat(48000, 2, SampleFormat.PCM_F32)
    }
}

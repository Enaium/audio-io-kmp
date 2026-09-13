package cn.enaium.audio

/**
 * How a single PCM sample is stored.
 *
 * Integer formats are two's complement, [PCM_U8] is offset-binary (silence is
 * `0x80`). Everything in this library is little-endian and interleaved: the
 * samples of one frame follow each other, frames follow each other in time.
 */
enum class SampleFormat(val bitsPerSample: Int) {
    /** Unsigned 8 bit, one byte per sample. */
    PCM_U8(8),

    /** Signed 16 bit, two bytes per sample. */
    PCM_S16(16),

    /** Signed 24 bit, three bytes per sample (packed, no padding byte). */
    PCM_S24(24),

    /** Signed 32 bit, four bytes per sample. */
    PCM_S32(32),

    /** IEEE 754 single precision, four bytes per sample. */
    PCM_F32(32),

    /** IEEE 754 double precision, eight bytes per sample. */
    PCM_F64(64),
    ;

    /** Size of one sample in bytes. */
    val bytesPerSample: Int = bitsPerSample / 8

    /** `true` for the IEEE 754 formats. */
    val isFloat: Boolean get() = this == PCM_F32 || this == PCM_F64

    /** `true` when the format stores signed values. */
    val isSigned: Boolean get() = this != PCM_U8
}

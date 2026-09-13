package cn.enaium.audio

/**
 * Little-endian PCM primitives shared by [AudioBuffer] and [AudioRingBuffer].
 *
 * Sample level conversions clamp into the target range instead of wrapping,
 * which keeps a too loud float input from turning into noise when it is read
 * back as an integer format.
 */
internal object Pcm {

    // ==================== raw little-endian access ====================

    fun shortAt(data: ByteArray, offset: Int): Short =
        ((data[offset].toInt() and 0xFF) or (data[offset + 1].toInt() shl 8)).toShort()

    fun putShort(data: ByteArray, offset: Int, value: Short) {
        data[offset] = value.toByte()
        data[offset + 1] = (value.toInt() shr 8).toByte()
    }

    fun int24At(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or
                ((data[offset + 1].toInt() and 0xFF) shl 8) or
                ((data[offset + 2].toInt() and 0xFF) shl 16)

    /** Sign extends the 24 bit value stored at [offset]. */
    fun int24SignedAt(data: ByteArray, offset: Int): Int {
        val raw = int24At(data, offset)
        return if (raw and 0x800000 != 0) raw or 0xFF000000.toInt() else raw
    }

    fun intAt(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or
                ((data[offset + 1].toInt() and 0xFF) shl 8) or
                ((data[offset + 2].toInt() and 0xFF) shl 16) or
                ((data[offset + 3].toInt() and 0xFF) shl 24)

    fun putInt(data: ByteArray, offset: Int, value: Int) {
        data[offset] = value.toByte()
        data[offset + 1] = (value shr 8).toByte()
        data[offset + 2] = (value shr 16).toByte()
        data[offset + 3] = (value shr 24).toByte()
    }

    fun floatAt(data: ByteArray, offset: Int): Float = Float.fromBits(intAt(data, offset))

    fun putFloat(data: ByteArray, offset: Int, value: Float) = putInt(data, offset, value.toRawBits())

    fun doubleAt(data: ByteArray, offset: Int): Double {
        val low = intAt(data, offset).toLong() and 0xFFFFFFFFL
        val high = intAt(data, offset + 4).toLong() and 0xFFFFFFFFL
        return Double.fromBits((high shl 32) or low)
    }

    fun putDouble(data: ByteArray, offset: Int, value: Double) {
        val bits = value.toRawBits()
        putInt(data, offset, bits.toInt())
        putInt(data, offset + 4, (bits ushr 32).toInt())
    }

    // ==================== sample -> kotlin ====================

    fun toShort(data: ByteArray, offset: Int, format: SampleFormat): Short = when (format) {
        SampleFormat.PCM_U8 -> (((data[offset].toInt() and 0xFF) - 128) shl 8).toShort()
        SampleFormat.PCM_S16 -> shortAt(data, offset)
        SampleFormat.PCM_S24 -> (int24SignedAt(data, offset) shr 8).toShort()
        SampleFormat.PCM_S32 -> (intAt(data, offset) shr 16).toShort()
        SampleFormat.PCM_F32 -> (floatAt(data, offset).coerceIn(-1f, 1f) * 32767f).toInt().toShort()
        SampleFormat.PCM_F64 -> (doubleAt(data, offset).coerceIn(-1.0, 1.0) * 32767.0).toInt().toShort()
    }

    fun toInt(data: ByteArray, offset: Int, format: SampleFormat): Int = when (format) {
        SampleFormat.PCM_U8 -> ((data[offset].toInt() and 0xFF) - 128) shl 24
        SampleFormat.PCM_S16 -> shortAt(data, offset).toInt() shl 16
        SampleFormat.PCM_S24 -> int24SignedAt(data, offset) shl 8
        SampleFormat.PCM_S32 -> intAt(data, offset)
        SampleFormat.PCM_F32 -> (floatAt(data, offset).toDouble().coerceIn(-1.0, 1.0) * Int.MAX_VALUE).toInt()
        SampleFormat.PCM_F64 -> (doubleAt(data, offset).coerceIn(-1.0, 1.0) * Int.MAX_VALUE).toInt()
    }

    fun toFloat(data: ByteArray, offset: Int, format: SampleFormat): Float = when (format) {
        SampleFormat.PCM_U8 -> ((data[offset].toInt() and 0xFF) - 128) / 128f
        SampleFormat.PCM_S16 -> shortAt(data, offset) / 32768f
        SampleFormat.PCM_S24 -> int24SignedAt(data, offset) / 8388608f
        SampleFormat.PCM_S32 -> (intAt(data, offset).toDouble() / 2147483648.0).toFloat()
        SampleFormat.PCM_F32 -> floatAt(data, offset)
        SampleFormat.PCM_F64 -> doubleAt(data, offset).toFloat()
    }

    fun toDouble(data: ByteArray, offset: Int, format: SampleFormat): Double = when (format) {
        SampleFormat.PCM_U8 -> ((data[offset].toInt() and 0xFF) - 128) / 128.0
        SampleFormat.PCM_S16 -> shortAt(data, offset) / 32768.0
        SampleFormat.PCM_S24 -> int24SignedAt(data, offset) / 8388608.0
        SampleFormat.PCM_S32 -> intAt(data, offset) / 2147483648.0
        SampleFormat.PCM_F32 -> floatAt(data, offset).toDouble()
        SampleFormat.PCM_F64 -> doubleAt(data, offset)
    }

    // ==================== kotlin -> sample ====================

    fun putShort(data: ByteArray, offset: Int, format: SampleFormat, value: Short) {
        when (format) {
            SampleFormat.PCM_U8 -> data[offset] = ((value.toInt() shr 8) + 128).toByte()
            SampleFormat.PCM_S16 -> putShort(data, offset, value)
            SampleFormat.PCM_S24 -> putInt24(data, offset, value.toInt() shl 8)
            SampleFormat.PCM_S32 -> putInt(data, offset, value.toInt() shl 16)
            SampleFormat.PCM_F32 -> putFloat(data, offset, value / 32768f)
            SampleFormat.PCM_F64 -> putDouble(data, offset, value / 32768.0)
        }
    }

    fun putInt(data: ByteArray, offset: Int, format: SampleFormat, value: Int) {
        when (format) {
            SampleFormat.PCM_U8 -> data[offset] = ((value shr 24) + 128).toByte()
            SampleFormat.PCM_S16 -> putShort(data, offset, (value shr 16).toShort())
            SampleFormat.PCM_S24 -> putInt24(data, offset, value shr 8)
            SampleFormat.PCM_S32 -> putInt(data, offset, value)
            SampleFormat.PCM_F32 -> putFloat(data, offset, (value / 2147483648.0).toFloat())
            SampleFormat.PCM_F64 -> putDouble(data, offset, value / 2147483648.0)
        }
    }

    fun putFloat(data: ByteArray, offset: Int, format: SampleFormat, value: Float) {
        when (format) {
            SampleFormat.PCM_U8 -> data[offset] = ((value * 128f).toInt().coerceIn(-128, 127) + 128).toByte()
            SampleFormat.PCM_S16 -> putShort(data, offset, (value.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
            SampleFormat.PCM_S24 -> putInt24(data, offset, (value.coerceIn(-1f, 1f) * 8388607f).toInt())
            SampleFormat.PCM_S32 -> putInt(data, offset, (value.toDouble().coerceIn(-1.0, 1.0) * Int.MAX_VALUE).toInt())
            SampleFormat.PCM_F32 -> putFloat(data, offset, value)
            SampleFormat.PCM_F64 -> putDouble(data, offset, value.toDouble())
        }
    }

    fun putDouble(data: ByteArray, offset: Int, format: SampleFormat, value: Double) {
        when (format) {
            SampleFormat.PCM_U8 -> data[offset] = ((value * 128.0).toInt().coerceIn(-128, 127) + 128).toByte()
            SampleFormat.PCM_S16 -> putShort(data, offset, (value.coerceIn(-1.0, 1.0) * 32767.0).toInt().toShort())
            SampleFormat.PCM_S24 -> putInt24(data, offset, (value.coerceIn(-1.0, 1.0) * 8388607.0).toInt())
            SampleFormat.PCM_S32 -> putInt(data, offset, (value.coerceIn(-1.0, 1.0) * Int.MAX_VALUE).toInt())
            SampleFormat.PCM_F32 -> putFloat(data, offset, value.toFloat())
            SampleFormat.PCM_F64 -> putDouble(data, offset, value)
        }
    }

    /** Writes the low 24 bits of [value] little-endian. */
    fun putInt24(data: ByteArray, offset: Int, value: Int) {
        data[offset] = value.toByte()
        data[offset + 1] = (value shr 8).toByte()
        data[offset + 2] = (value shr 16).toByte()
    }
}

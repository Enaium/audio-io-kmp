package cn.enaium.audio

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AudioBufferTest {

    private val mono16 = AudioFormat(48000, 1, SampleFormat.PCM_S16)
    private val stereo16 = AudioFormat(48000, 2, SampleFormat.PCM_S16)

    @Test
    fun putShortsStoresLittleEndianInterleavedFrames() {
        val buffer = AudioBuffer(stereo16, 4)
        assertEquals(2, buffer.putShorts(shortArrayOf(0x0102, 0x0304, 0x0506, 0x0708)))
        assertEquals(2, buffer.frameCount)
        assertEquals(8, buffer.byteCount)
        assertContentEquals(
            byteArrayOf(0x02, 0x01, 0x04, 0x03, 0x06, 0x05, 0x08, 0x07),
            buffer.bytes(),
        )
    }

    @Test
    fun exactReadsAreClampedToTheValidRegion() {
        val buffer = AudioBuffer(mono16, 16)
        buffer.putShorts(shortArrayOf(1, 2, 3, 4))
        assertEquals(4, buffer.sampleCount)
        assertEquals(2, buffer.shorts(2).size)
        assertContentEquals(shortArrayOf(1, 2), buffer.shorts(2))
        // Asking for more than the valid region yields what is there, never more.
        assertEquals(4, buffer.shorts(64).size)
        assertEquals(6, buffer.bytes(6).size)
        assertEquals(8, buffer.bytes(64).size)
    }

    @Test
    fun frameCountCannotExceedCapacity() {
        val buffer = AudioBuffer(mono16, 2)
        assertFailsWith<IllegalArgumentException> { buffer.frameCount = 3 }
        assertFailsWith<IllegalArgumentException> { buffer.frameCount = -1 }
    }

    @Test
    fun clearReleasesTheFrames() {
        val buffer = AudioBuffer(mono16, 4)
        buffer.putShorts(shortArrayOf(9, 9, 9, 9))
        buffer.clear()
        assertEquals(0, buffer.frameCount)
        assertTrue(buffer.isEmpty)
        assertContentEquals(shortArrayOf(), buffer.shorts())
        assertTrue(buffer.data.all { it == 0.toByte() })
    }

    @Test
    fun floatSamplesRoundTripThroughEveryIntegerFormat() {
        val input = floatArrayOf(0f, 0.5f, -0.5f, 1f, -1f)
        for (format in SampleFormat.entries) {
            val audio = AudioFormat(48000, 1, format)
            val buffer = AudioBuffer(audio, input.size)
            buffer.putFloats(input)
            val output = buffer.floats()
            for (i in input.indices) {
                assertEquals(
                    input[i].toDouble(),
                    output[i].toDouble(),
                    absoluteTolerance = 1.0 / (1 shl (format.bitsPerSample - 2)),
                    "format $format sample $i",
                )
            }
        }
    }

    @Test
    fun unsignedEightBitIsOffsetBinary() {
        val audio = AudioFormat(8000, 1, SampleFormat.PCM_U8)
        val buffer = AudioBuffer(audio, 3)
        buffer.putShorts(shortArrayOf(0, 32767, -32768))
        assertContentEquals(byteArrayOf(0x80.toByte(), 0xFF.toByte(), 0x00), buffer.bytes())
        assertContentEquals(shortArrayOf(0, 32512, -32768), buffer.shorts())
    }

    @Test
    fun floatsAreClampedInsteadOfWrapping() {
        val buffer = AudioBuffer(mono16, 2)
        buffer.putFloats(floatArrayOf(2f, -2f))
        assertContentEquals(shortArrayOf(32767, -32767), buffer.shorts())
    }

    @Test
    fun copyingRequiresTheSameFormat() {
        val target = AudioBuffer(mono16, 4)
        val source = AudioBuffer(stereo16, 4)
        source.putShorts(shortArrayOf(1, 2))
        assertFailsWith<IllegalArgumentException> { target.copyFrom(source) }
        assertFailsWith<IllegalArgumentException> {
            AudioRingBuffer(stereo16, 4).write(AudioBuffer(mono16, 2).apply { putShorts(shortArrayOf(1)) })
        }
    }

    @Test
    fun copyFromAndSliceKeepFrameAlignment() {
        val source = AudioBuffer(mono16, 8)
        source.putShorts(shortArrayOf(1, 2, 3, 4, 5, 6, 7, 8))

        val target = AudioBuffer(mono16, 4)
        assertEquals(3, target.copyFrom(source, 3))
        assertContentEquals(shortArrayOf(1, 2, 3), target.shorts())

        val slice = source.slice(2, 3)
        assertContentEquals(shortArrayOf(3, 4, 5), slice.shorts())
        assertEquals(3, slice.frameCount)
    }

    @Test
    fun toShortsWritesWithoutAllocating() {
        val buffer = AudioBuffer(mono16, 4)
        buffer.putShorts(shortArrayOf(4, 3, 2, 1))
        val destination = ShortArray(8) { -1 }
        assertEquals(4, buffer.toShorts(destination))
        assertContentEquals(shortArrayOf(4, 3, 2, 1, -1, -1, -1, -1), destination)
    }
}

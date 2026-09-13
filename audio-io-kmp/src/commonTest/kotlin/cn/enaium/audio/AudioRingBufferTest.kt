package cn.enaium.audio

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AudioRingBufferTest {

    private val mono16 = AudioFormat(48000, 1, SampleFormat.PCM_S16)

    @Test
    fun writeAndReadAreFrameExact() {
        val ring = AudioRingBuffer(mono16, 8)
        assertEquals(8, ring.availableToWrite)
        assertEquals(5, ring.write(shortArrayOf(1, 2, 3, 4, 5)))
        assertEquals(5, ring.availableToRead)
        assertEquals(3, ring.availableToWrite)

        val out = ShortArray(3)
        assertEquals(3, ring.read(out))
        assertContentEquals(shortArrayOf(1, 2, 3), out)
        assertEquals(2, ring.availableToRead)
    }

    @Test
    fun writesThatDoNotFitAreTruncated() {
        val ring = AudioRingBuffer(mono16, 4)
        assertEquals(4, ring.write(shortArrayOf(1, 2, 3, 4, 5, 6)))
        assertTrue(ring.isFull)
        assertEquals(0, ring.write(shortArrayOf(7)))
        assertEquals(0, ring.availableToWrite)
    }

    @Test
    fun valuesSurviveTheWrapAround() {
        val ring = AudioRingBuffer(mono16, 4)
        ring.write(shortArrayOf(1, 2, 3))
        val first = ShortArray(2)
        ring.read(first)
        assertContentEquals(shortArrayOf(1, 2), first)

        // Frames 3, 4, 5, 6 straddle the end of the storage.
        assertEquals(3, ring.write(shortArrayOf(4, 5, 6)))
        assertEquals(4, ring.availableToRead)
        val second = ShortArray(4)
        assertEquals(4, ring.read(second))
        assertContentEquals(shortArrayOf(3, 4, 5, 6), second)
    }

    @Test
    fun byteAccessIsFrameAligned() {
        val ring = AudioRingBuffer(mono16, 4)
        assertFailsWith<IllegalArgumentException> { ring.write(byteArrayOf(1, 2, 3)) }
        assertFailsWith<IllegalArgumentException> { ring.read(ByteArray(3)) }

        val stereo = AudioRingBuffer(AudioFormat(48000, 2, SampleFormat.PCM_S16), 4)
        assertEquals(1, stereo.write(byteArrayOf(1, 2, 3, 4)))
        assertFailsWith<IllegalArgumentException> { stereo.read(ByteArray(4), count = 2) }
    }

    @Test
    fun bufferTransferKeepsInterleaving() {
        val stereo = AudioFormat(48000, 2, SampleFormat.PCM_S16)
        val ring = AudioRingBuffer(stereo, 8)
        val source = AudioBuffer(stereo, 4)
        source.putShorts(shortArrayOf(1, -1, 2, -2, 3, -3, 4, -4))
        assertEquals(4, ring.write(source))

        val destination = AudioBuffer(stereo, 8)
        assertEquals(3, ring.read(destination, 3))
        assertEquals(3, destination.frameCount)
        assertContentEquals(shortArrayOf(1, -1, 2, -2, 3, -3), destination.shorts())
        assertEquals(1, ring.availableToRead)
    }

    @Test
    fun floatSamplesRoundTripThroughTheRing() {
        val format = AudioFormat(48000, 1, SampleFormat.PCM_F32)
        val ring = AudioRingBuffer(format, 4)
        assertEquals(2, ring.write(floatArrayOf(0.25f, -0.25f)))
        val out = FloatArray(2)
        assertEquals(2, ring.read(out))
        assertContentEquals(floatArrayOf(0.25f, -0.25f), out)
    }

    @Test
    fun skipDropsFramesWithoutReading() {
        val ring = AudioRingBuffer(mono16, 8)
        ring.write(shortArrayOf(1, 2, 3, 4))
        assertEquals(2, ring.skip(2))
        assertEquals(2, ring.skip(10))
        assertEquals(0, ring.availableToRead)
    }

    @Test
    fun clearEmpiesTheRing() {
        val ring = AudioRingBuffer(mono16, 4)
        ring.write(shortArrayOf(1, 2, 3, 4))
        ring.clear()
        assertEquals(0, ring.availableToRead)
        assertEquals(4, ring.availableToWrite)
    }
}

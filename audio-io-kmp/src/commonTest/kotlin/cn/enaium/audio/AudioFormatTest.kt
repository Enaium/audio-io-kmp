package cn.enaium.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AudioFormatTest {

    @Test
    fun frameSizeCountsEveryChannel() {
        assertEquals(4, AudioFormat(48000, 2, SampleFormat.PCM_S16).frameSizeBytes)
        assertEquals(6, AudioFormat(48000, 2, SampleFormat.PCM_S24).frameSizeBytes)
        assertEquals(8, AudioFormat(48000, 2, SampleFormat.PCM_F32).frameSizeBytes)
        assertEquals(1, AudioFormat(8000, 1, SampleFormat.PCM_U8).frameSizeBytes)
    }

    @Test
    fun byteAndFrameConversionRoundTrips() {
        val format = AudioFormat(44100, 2, SampleFormat.PCM_S16)
        assertEquals(20 * format.frameSizeBytes, format.framesToBytes(20))
        assertEquals(20, format.bytesToFrames(format.framesToBytes(20)))
        // A partial frame is not a frame.
        assertEquals(0, format.bytesToFrames(format.frameSizeBytes - 1))
    }

    @Test
    fun durationConvertsBothWays() {
        val format = AudioFormat(48000, 1, SampleFormat.PCM_S16)
        assertEquals(20.0, format.framesToMillis(960), absoluteTolerance = 1e-9)
        assertEquals(960, format.millisToFrames(20.0))
    }

    @Test
    fun invalidValuesAreRejected() {
        assertFailsWith<IllegalArgumentException> { AudioFormat(0, 1, SampleFormat.PCM_S16) }
        assertFailsWith<IllegalArgumentException> { AudioFormat(48000, 0, SampleFormat.PCM_S16) }
        assertFailsWith<IllegalArgumentException> {
            AudioFormat(48000, AudioFormat.MAX_CHANNELS + 1, SampleFormat.PCM_S16)
        }
    }

    @Test
    fun formatPropertiesDescribeTheLayout() {
        assertTrue(SampleFormat.PCM_F64.isFloat)
        assertFalse(SampleFormat.PCM_S32.isFloat)
        assertFalse(SampleFormat.PCM_U8.isSigned)
        assertTrue(SampleFormat.PCM_S24.isSigned)
        assertEquals(3, SampleFormat.PCM_S24.bytesPerSample)
        assertEquals(8, SampleFormat.PCM_F64.bytesPerSample)
    }
}

package cn.enaium.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Exercises the Core Audio backend against whatever device the machine has.
 *
 * Enumeration is deterministic, so it is asserted directly. Opening a stream
 * depends on the runner having an audio device at all (a headless build machine
 * may not), so the playback check reports that situation instead of failing.
 */
class CoreAudioTest {

    @Test
    fun systemUsesTheCoreAudioBackend() {
        val system = audioSystem()
        try {
            assertEquals("Core Audio", system.name)
        } finally {
            system.close()
        }
    }

    @Test
    fun enumeratedDevicesDescribeARealEndpoint() {
        val system = audioSystem()
        try {
            (system.inputDevices() + system.outputDevices()).forEach { device ->
                assertTrue(device.id.isNotBlank(), "device id must not be blank")
                assertTrue(device.name.isNotBlank(), "device name must not be blank: $device")
                assertTrue(
                    device.channelCounts.isEmpty() || device.channelCounts.all { it > 0 },
                    "channel counts must be positive: $device",
                )
            }
            system.defaultOutputDevice()?.let { default ->
                assertTrue(default.isOutput, "the default output device must play back: $default")
            }
            system.defaultInputDevice()?.let { default ->
                assertTrue(default.isInput, "the default input device must capture: $default")
            }
        } finally {
            system.close()
        }
    }

    @Test
    fun playbackAcceptsSilence() {
        val system = audioSystem()
        try {
            val format = AudioFormat(48000, 2, SampleFormat.PCM_F32)
            val output = try {
                system.openOutput(format, bufferFrames = 480)
            } catch (e: AudioException) {
                println("no playback device on this machine: ${e.message}")
                return
            }
            output.use {
                assertEquals(format, output.format)
                assertTrue(output.bufferFrames > 0, "the device reports the frames it buffers")
                output.start()
                assertTrue(output.isStarted)

                // A freshly allocated buffer is already silent, it just has to
                // announce its frames.
                val silence = AudioBuffer(format, 480).apply { frameCount = 480 }
                repeat(3) {
                    val written = output.write(silence)
                    assertEquals(480, written, "a full buffer is always accepted")
                }
                assertTrue(output.available() >= 0, "the free space is never negative")
            }
            assertEquals(AudioState.CLOSED, output.state)
        } finally {
            system.close()
        }
    }

    @Test
    fun captureStreamCanBeOpenedAndClosed() {
        val system = audioSystem()
        try {
            val format = AudioFormat(48000, 1, SampleFormat.PCM_S16)
            val input = try {
                system.openInput(format, bufferFrames = 480)
            } catch (e: AudioException) {
                println("no capture device on this machine: ${e.message}")
                return
            }
            input.use {
                assertEquals(format, input.format)
                input.start()
                val buffer = AudioBuffer(format, 480)
                val available = input.available()
                assertTrue(available >= 0, "the buffered frames are never negative")
                val frames = input.readNonBlocking(buffer)
                assertTrue(frames in 0..480, "a non blocking read never overfills: $frames")
                assertNotNull(input.device ?: system.defaultInputDevice(), "a capture device is attached")
            }
        } finally {
            system.close()
        }
    }
}

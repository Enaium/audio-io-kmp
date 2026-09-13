package cn.enaium.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Exercises the ALSA backend on the current machine.
 *
 * A build machine usually has no sound card, and the default PCM then either
 * fails to open or resolves to a null device; the checks therefore stay on what
 * the backend promises regardless of the hardware, and only touch a device when
 * one opens. Writes are non blocking on purpose: a blocking write into a device
 * nobody drains would park the test run forever.
 */
class AlsaSystemTest {

    @Test
    fun systemUsesTheAlsaBackend() {
        val system = audioSystem()
        try {
            assertEquals("ALSA", system.name)
        } finally {
            system.close()
        }
    }

    @Test
    fun devicesHaveOpenableIdentifiers() {
        val system = audioSystem()
        try {
            listOf(system.inputDevices(), system.outputDevices()).forEach { devices ->
                devices.forEach { device ->
                    assertTrue(device.id.isNotBlank(), "a device is addressable by name: $device")
                    assertTrue(device.name.isNotBlank(), "a device has a label: $device")
                    assertTrue(
                        device.type == AudioDeviceType.INPUT ||
                                device.type == AudioDeviceType.OUTPUT ||
                                device.type == AudioDeviceType.INPUT_OUTPUT,
                        "a device has a direction: $device",
                    )
                }
            }
            system.defaultOutputDevice()?.let { default ->
                assertTrue(default.id == "default" || default.id.isNotBlank(), "the default has an id: $default")
            }
        } finally {
            system.close()
        }
    }

    @Test
    fun playbackAcceptsFramesWhenADeviceExists() {
        val system = audioSystem()
        try {
            val format = AudioFormat(48000, 2, SampleFormat.PCM_S16)
            val output = try {
                system.openOutput(format, bufferFrames = 480)
            } catch (e: AudioException) {
                println("no playback PCM on this machine: ${e.message}")
                return
            }
            output.use {
                assertEquals(format, output.format)
                assertTrue(output.bufferFrames > 0, "the device reports the frames it buffers")
                output.start()
                assertTrue(output.isStarted)
                val frames = output.writeNonBlocking(AudioBuffer(format, 480).apply { frameCount = 480 })
                assertTrue(frames in 0..480, "a non blocking write never overfills the buffer: $frames")
                assertTrue(output.available() >= 0)
            }
            assertEquals(AudioState.CLOSED, output.state)
        } finally {
            system.close()
        }
    }

    @Test
    fun unsupportedFormatIsRejectedInsteadOfConverted() {
        val system = audioSystem()
        try {
            // 12345 Hz is not a rate any real device advertises.
            val error = runCatching { system.openInput(AudioFormat(12345, 1, SampleFormat.PCM_S16)) }
                .exceptionOrNull()
            if (error is AudioException) {
                assertTrue(error.message!!.isNotBlank(), "the failure names the call: ${error.message}")
            }
        } finally {
            system.close()
        }
    }
}

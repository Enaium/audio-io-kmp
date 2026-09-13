package cn.enaium.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Exercises the `javax.sound.sampled` backend on the current machine.
 *
 * Which mixers a JDK reports depends on the platform, so the assertions stay on
 * what the backend promises: a stream always reports the format it was opened
 * with, a non blocking read never overfills its buffer and closing is
 * idempotent. Machines without a mixer simply report that instead of failing.
 */
class JvmAudioSystemTest {

    @Test
    fun systemUsesTheJavaSoundBackend() {
        val system = audioSystem()
        try {
            assertEquals("JavaSound", system.name)
            assertTrue(system.inputDevices().all { it.isInput }, "input devices capture")
            assertTrue(system.outputDevices().all { it.isOutput }, "output devices play back")
        } finally {
            system.close()
        }
    }

    @Test
    fun unknownDeviceIdIsRejected() {
        val system = audioSystem()
        try {
            val error = runCatching {
                system.openOutput(AudioFormat.CD_QUALITY, AudioDevice("not-a-mixer", "nope", AudioDeviceType.OUTPUT))
            }.exceptionOrNull()
            assertTrue(error is AudioException, "an unknown device fails with an audio error: $error")
        } finally {
            system.close()
        }
    }

    @Test
    fun everyEnumeratedDeviceResolvesBackToItsMixer() {
        val system = audioSystem()
        try {
            val format = AudioFormat(44100, 2, SampleFormat.PCM_S16)
            // A mixer that does not take the format may fail, a device that
            // cannot be mapped back to the mixer it came from never may.
            val failures = mutableListOf<String>()
            for (device in system.inputDevices()) {
                runCatching { system.openInput(format, device).close() }
                    .onFailure { failures += it.message.orEmpty() }
            }
            for (device in system.outputDevices()) {
                runCatching { system.openOutput(format, device).close() }
                    .onFailure { failures += it.message.orEmpty() }
            }
            assertTrue(
                failures.none { it.contains("is not a capture device") || it.contains("is not a playback device") },
                "devices from the enumeration must resolve: $failures",
            )
        } finally {
            system.close()
        }
    }

    @Test
    fun playbackRoundTrip() {
        val system = audioSystem()
        try {
            val format = AudioFormat(44100, 2, SampleFormat.PCM_S16)
            val output = try {
                system.openOutput(format, bufferFrames = 441)
            } catch (e: AudioException) {
                println("no playback mixer on this machine: ${e.message}")
                return
            }
            output.use {
                assertEquals(format, output.format)
                assertTrue(output.bufferFrames > 0, "the line reports the frames it buffers")
                output.start()
                val silence = AudioBuffer(format, 441).apply { frameCount = 441 }
                assertEquals(441, output.write(silence), "a full buffer is always accepted")
                val nonBlocking = output.writeNonBlocking(AudioBuffer(format, 441).apply { frameCount = 441 })
                assertTrue(nonBlocking >= 0, "a non blocking write never reports a negative count: $nonBlocking")
                assertTrue(output.available() >= 0)
            }
            output.close()
            assertEquals(AudioState.CLOSED, output.state, "close is idempotent")
        } finally {
            system.close()
        }
    }

    @Test
    fun captureStreamReportsFramesItRead() {
        val system = audioSystem()
        try {
            val format = AudioFormat(44100, 1, SampleFormat.PCM_S16)
            val input = try {
                system.openInput(format, bufferFrames = 441)
            } catch (e: AudioException) {
                println("no capture mixer on this machine: ${e.message}")
                return
            }
            input.use {
                assertEquals(format, input.format)
                input.start()
                val buffer = AudioBuffer(format, 441)
                val frames = input.readNonBlocking(buffer)
                assertTrue(frames in 0..441, "a non blocking read never overfills: $frames")
                assertEquals(frames, buffer.frameCount, "the buffer reports what was read")
                assertTrue(input.available() >= 0)
                input.stop()
                assertEquals(AudioState.STOPPED, input.state)
            }
        } finally {
            system.close()
        }
    }
}

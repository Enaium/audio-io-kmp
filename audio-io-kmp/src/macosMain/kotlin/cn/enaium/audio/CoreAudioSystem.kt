package cn.enaium.audio

/**
 * Backend built on Core Audio's Audio Queue Services.
 *
 * A queue runs on its own thread with a pull (playback) or push (capture)
 * callback; both are bridged onto the blocking [AudioInput.read] /
 * [AudioOutput.write] contract by [CallbackAudioInput] / [CallbackAudioOutput].
 * The queue's own converter accepts any sample rate and sample format the
 * library can describe, so the format a stream is opened with is the format the
 * application sees, whatever the hardware runs at.
 *
 * Devices come from the HAL: [AudioDevice.id] is the device UID, which is
 * exactly what AudioQueue expects when a stream is pinned to a device.
 */
class CoreAudioSystem : AudioSystem {

    override val name: String = "Core Audio"

    private var closed = false

    override fun inputDevices(): List<AudioDevice> = devices(AudioDeviceType.INPUT)

    override fun outputDevices(): List<AudioDevice> = devices(AudioDeviceType.OUTPUT)

    override fun defaultInputDevice(): AudioDevice? =
        devices(AudioDeviceType.INPUT).firstOrNull { it.isDefault }

    override fun defaultOutputDevice(): AudioDevice? =
        devices(AudioDeviceType.OUTPUT).firstOrNull { it.isDefault }

    override fun openInput(format: AudioFormat, device: AudioDevice?, bufferFrames: Int): AudioInput {
        check(!closed) { "audio system is closed" }
        val frames = if (bufferFrames > 0) bufferFrames else format.defaultBufferFrames()
        val ring = PcmRing(format, frames * RING_BUFFERS)
        val signal = createRtSignal()
        val control = try {
            AudioQueueInput(format, frames, ring, signal, device?.id)
        } catch (e: Throwable) {
            ring.close()
            signal.close()
            throw e
        }
        return CallbackAudioInput(format, device, frames, ring, signal, control)
    }

    override fun openOutput(format: AudioFormat, device: AudioDevice?, bufferFrames: Int): AudioOutput {
        check(!closed) { "audio system is closed" }
        val frames = if (bufferFrames > 0) bufferFrames else format.defaultBufferFrames()
        val ring = PcmRing(format, frames * RING_BUFFERS)
        val signal = createRtSignal()
        val control = try {
            AudioQueueOutput(format, frames, ring, signal, device?.id)
        } catch (e: Throwable) {
            ring.close()
            signal.close()
            throw e
        }
        return CallbackAudioOutput(format, device, frames, ring, signal, control)
    }

    override fun close() {
        closed = true
    }

    private fun devices(direction: AudioDeviceType): List<AudioDevice> {
        val input = direction == AudioDeviceType.INPUT
        val scope = if (input) CoreAudioDevices.inputScope else CoreAudioDevices.outputScope
        val selector = if (input) CoreAudioDevices.defaultInputSelector else CoreAudioDevices.defaultOutputSelector
        val defaultId = CoreAudioDevices.defaultDevice(selector)
        return CoreAudioDevices.all().mapNotNull { id ->
            val channels = CoreAudioDevices.channels(id, scope)
            if (channels <= 0) return@mapNotNull null
            val uid = CoreAudioDevices.uid(id) ?: return@mapNotNull null
            AudioDevice(
                id = uid,
                name = CoreAudioDevices.name(id),
                type = direction,
                isDefault = id == defaultId,
                channelCounts = listOf(channels),
            )
        }
    }

    private companion object {
        /** Frames the ring holds, expressed in device buffers. */
        const val RING_BUFFERS = 4
    }
}

/** The Core Audio backend of this platform. */
actual fun audioSystem(): AudioSystem = CoreAudioSystem()

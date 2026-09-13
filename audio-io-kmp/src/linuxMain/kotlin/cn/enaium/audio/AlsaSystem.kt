@file:OptIn(ExperimentalForeignApi::class)

package cn.enaium.audio

import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.COpaquePointerVar
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.invoke
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.plus
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.value

/**
 * Backend built directly on ALSA's PCM API.
 *
 * The `default` PCM is what ALSA's own configuration resolves to (usually
 * PipeWire, PulseAudio or `dmix` through the plugin layer), so opening it gives
 * the caller the same device the rest of the system uses, while `hw:CARD,DEV`
 * names can be passed through [AudioDevice.id] when a specific card is wanted.
 *
 * The format is applied exactly as requested: a device that cannot deliver the
 * sample rate, channel count or sample format fails with an [AudioException]
 * instead of being silently converted.
 */
class AlsaSystem : AudioSystem {

    override val name: String = "ALSA"

    private var closed = false

    override fun inputDevices(): List<AudioDevice> = devices(capture = true)

    override fun outputDevices(): List<AudioDevice> = devices(capture = false)

    override fun defaultInputDevice(): AudioDevice? =
        inputDevices().firstOrNull { it.isDefault } ?: inputDevices().firstOrNull()

    override fun defaultOutputDevice(): AudioDevice? =
        outputDevices().firstOrNull { it.isDefault } ?: outputDevices().firstOrNull()

    override fun openInput(format: AudioFormat, device: AudioDevice?, bufferFrames: Int): AudioInput {
        checkOpen()
        val frames = if (bufferFrames > 0) bufferFrames else format.defaultBufferFrames()
        val (pcm, applied) = openPcm(device?.id ?: DEFAULT_PCM, capture = true, format = format, bufferFrames = frames)
        return AlsaAudioInput(format, device, applied, pcm)
    }

    override fun openOutput(format: AudioFormat, device: AudioDevice?, bufferFrames: Int): AudioOutput {
        checkOpen()
        val frames = if (bufferFrames > 0) bufferFrames else format.defaultBufferFrames()
        val (pcm, applied) = openPcm(device?.id ?: DEFAULT_PCM, capture = false, format = format, bufferFrames = frames)
        return AlsaAudioOutput(format, device, applied, pcm)
    }

    override fun close() {
        closed = true
    }

    private fun checkOpen() {
        check(!closed) { "audio system is closed" }
        if (!Alsa.available) {
            throw AudioException(
                "ALSA is not usable on this system: libasound.so.2 could not be loaded",
            )
        }
    }

    /**
     * Devices reported by ALSA's name hints.
     *
     * A hint carries a direction only when the PCM is not duplex; entries that
     * name a well known plugin (`null`, `default`, `sysdefault`) are surfaced as
     * they are because those are what a caller normally wants to open.
     */
    private fun devices(capture: Boolean): List<AudioDevice> {
        if (!Alsa.available) return emptyList()
        val api = Alsa.api()
        val hint = api.device_name_hint ?: return emptyList()
        return memScoped {
            val holder = alloc<COpaquePointerVar>()
            val opened = "pcm".withCString { iface -> hint(CARD_ANY, iface, holder.ptr) }
            if (opened < 0) return@memScoped emptyList()
            val array = holder.value ?: return@memScoped emptyList()
            val entries = array.reinterpret<COpaquePointerVar>()
            val devices = mutableListOf<AudioDevice>()
            var index = 0
            while (true) {
                val entry = (entries + index)?.pointed?.value ?: break
                val strings = HintStrings.read(entry, api)
                if (strings != null) {
                    val name = strings.deviceName!!
                    val direction = strings.ioDirection
                    val matches = when {
                        capture -> direction == null || direction.equals("Input", ignoreCase = true)
                        else -> direction == null || direction.equals("Output", ignoreCase = true)
                    }
                    if (matches) {
                        devices += AudioDevice(
                            id = name,
                            name = strings.deviceDescription?.substringBefore('\n')?.takeIf { it.isNotBlank() } ?: name,
                            type = when {
                                direction == null -> AudioDeviceType.INPUT_OUTPUT
                                capture -> AudioDeviceType.INPUT
                                else -> AudioDeviceType.OUTPUT
                            },
                            isDefault = name == DEFAULT_PCM,
                        )
                    }
                }
                index++
            }
            api.device_name_free_hint?.invoke(array)
            devices
        }
    }

    private companion object {
        /** ALSA's own default PCM, which the configuration files map to the real device. */
        const val DEFAULT_PCM = "default"

        /** `-1` asks `snd_device_name_hint` for every card. */
        const val CARD_ANY = -1
    }
}

/** The ALSA backend of this platform. */
actual fun audioSystem(): AudioSystem = AlsaSystem()

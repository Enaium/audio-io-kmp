package cn.enaium.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager

/**
 * Android backend built on `AudioRecord` for capture and `AudioTrack` for
 * playback.
 *
 * Device enumeration goes through [AudioManager.getDevices], which needs a
 * [Context]. A system built without one still opens streams on the endpoints
 * Android picks by default, but [inputDevices]/[outputDevices] then report a
 * single synthetic device per direction so that code which always passes
 * `defaultInputDevice()`/`defaultOutputDevice()` back to `openInput`/`openOutput`
 * keeps working.
 *
 * Android's public API has no query for the endpoint that capture or media
 * playback would currently use: `getDevices` only lists what is connected and
 * the communication-device API only covers the voice call route. The first
 * entry of each direction is therefore reported as the default, which matches
 * the order Android keeps its device lists in but is not the per-stream routing
 * decision the framework makes.
 *
 * Nothing is cached: every enumeration asks [AudioManager] again, so devices
 * that are plugged in after this system was created are still visible.
 *
 * The backend uses [AudioManager.getDevices], `AudioDeviceInfo`,
 * `setPreferredDevice`, blocking `read`/`write` and the `AudioRecord`/
 * `AudioTrack` builders, all of which exist on API 23. The module's minSdk is
 * 24, so none of them needs a runtime version check; the only API level
 * compromise is `PCM_S32`, which needs API 31.
 *
 * @param context context used to enumerate devices and to resolve a requested
 *   [AudioDevice] to the `AudioDeviceInfo` a stream sets as its preferred
 *   device. `null` keeps streams usable, but enumeration then only reports the
 *   synthetic default.
 */
class AndroidAudioSystem(private val context: Context? = null) : AudioSystem {

    override val name: String = "AudioRecord/AudioTrack"

    private val audioManager: AudioManager? =
        context?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private var closed = false

    override fun inputDevices(): List<AudioDevice> =
        devices(AudioManager.GET_DEVICES_INPUTS, AudioDeviceType.INPUT, SYNTHETIC_INPUT_ID)

    override fun outputDevices(): List<AudioDevice> =
        devices(AudioManager.GET_DEVICES_OUTPUTS, AudioDeviceType.OUTPUT, SYNTHETIC_OUTPUT_ID)

    override fun defaultInputDevice(): AudioDevice? = inputDevices().firstOrNull()

    override fun defaultOutputDevice(): AudioDevice? = outputDevices().firstOrNull()

    override fun openInput(format: AudioFormat, device: AudioDevice?, bufferFrames: Int): AudioInput {
        checkOpen()
        val preferred = device?.let { resolve(it, AudioManager.GET_DEVICES_INPUTS, SYNTHETIC_INPUT_ID, "capture") }
        return AndroidAudioInput.open(format, device, preferred, bufferFrames)
    }

    override fun openOutput(format: AudioFormat, device: AudioDevice?, bufferFrames: Int): AudioOutput {
        checkOpen()
        val preferred = device?.let { resolve(it, AudioManager.GET_DEVICES_OUTPUTS, SYNTHETIC_OUTPUT_ID, "playback") }
        return AndroidAudioOutput.open(format, device, preferred, bufferFrames)
    }

    override fun close() {
        closed = true
    }

    private fun checkOpen() {
        check(!closed) { "audio system is closed" }
    }

    /**
     * Every connected endpoint of one direction, or the synthetic default when
     * Android reports none. A failing `getDevices` (for example a context that
     * cannot reach the audio service) falls back the same way instead of
     * failing enumeration.
     */
    private fun devices(flag: Int, type: AudioDeviceType, syntheticId: String): List<AudioDevice> {
        val manager = audioManager ?: return listOf(synthetic(type, syntheticId))
        val infos = runCatching { manager.getDevices(flag) }.getOrNull()
        if (infos.isNullOrEmpty()) return listOf(synthetic(type, syntheticId))
        return infos.mapIndexed { index, info -> device(info, type, index == 0) }
    }

    /**
     * Looks [device] up among the endpoints of one direction so the stream can
     * set it as its preferred device. The synthetic default has no platform
     * counterpart and resolves to `null`, which leaves the stream on the
     * system route.
     */
    private fun resolve(
        device: AudioDevice,
        flag: Int,
        syntheticId: String,
        direction: String,
    ): AudioDeviceInfo? {
        if (device.id == syntheticId) return null
        val manager = audioManager
            ?: throw AudioException(
                "cannot open ${device.name} for $direction: this system was created without a Context, " +
                    "so the device id cannot be resolved",
            )
        val infos = runCatching { manager.getDevices(flag) }.getOrNull()
            ?: throw AudioException("cannot enumerate $direction devices to resolve ${device.id}")
        return infos.firstOrNull { it.id.toString() == device.id }
            ?: throw AudioException("Android does not report ${device.name} (id ${device.id}) as a $direction device")
    }

    private fun device(info: AudioDeviceInfo, type: AudioDeviceType, isDefault: Boolean): AudioDevice =
        AudioDevice(
            id = info.id.toString(),
            name = info.productName.toString(),
            type = type,
            isDefault = isDefault,
            sampleRates = info.sampleRates.toList(),
            channelCounts = info.channelCounts.toList(),
        )

    private fun synthetic(type: AudioDeviceType, id: String): AudioDevice =
        AudioDevice(
            id = id,
            name = if (type == AudioDeviceType.INPUT) "Default input" else "Default output",
            type = type,
            isDefault = true,
        )

    private companion object {
        /** Id of the placeholder capture endpoint used when no real device can be reported. */
        const val SYNTHETIC_INPUT_ID: String = "default-input"

        /** Id of the placeholder playback endpoint used when no real device can be reported. */
        const val SYNTHETIC_OUTPUT_ID: String = "default-output"
    }
}

/** The `AudioRecord`/`AudioTrack` backend of this platform, without a [Context]. */
actual fun audioSystem(): AudioSystem = AndroidAudioSystem()

/**
 * The `AudioRecord`/`AudioTrack` backend of this platform for [context].
 *
 * Unlike [audioSystem], the returned backend can enumerate the connected
 * devices.
 */
fun audioSystem(context: Context): AudioSystem = AndroidAudioSystem(context)

package cn.enaium.audio

/** Direction of an [AudioDevice]. */
enum class AudioDeviceType {
    /** Capture only, for example a microphone. */
    INPUT,

    /** Playback only, for example a speaker. */
    OUTPUT,

    /** Both directions, for example a duplex sound card or a headset jack. */
    INPUT_OUTPUT,
}

/**
 * One endpoint of the platform audio API.
 *
 * [id] is the backend's own identifier (a mixer index, an Android device id, a
 * Core Audio UID, an ALSA PCM name, a WASAPI endpoint id string) and is what
 * [AudioSystem.openInput]/[AudioSystem.openOutput] expect when a specific
 * device is requested. Pass `null` to those methods to use the system default.
 */
data class AudioDevice(
    /** Backend specific identifier, stable for as long as the device exists. */
    val id: String,
    /** Human readable name, for example `"MacBook Pro Microphone"`. */
    val name: String,
    /** Direction of the endpoint. */
    val type: AudioDeviceType,
    /** `true` when this is the endpoint the system would pick by default. */
    val isDefault: Boolean = false,
    /** Sample rates the device reported, empty when the backend cannot tell. */
    val sampleRates: List<Int> = emptyList(),
    /** Channel counts the device reported, empty when the backend cannot tell. */
    val channelCounts: List<Int> = emptyList(),
) {
    /** `true` when the device can capture. */
    val isInput: Boolean get() = type == AudioDeviceType.INPUT || type == AudioDeviceType.INPUT_OUTPUT

    /** `true` when the device can play back. */
    val isOutput: Boolean get() = type == AudioDeviceType.OUTPUT || type == AudioDeviceType.INPUT_OUTPUT

    override fun toString(): String = "$name ($id)"
}

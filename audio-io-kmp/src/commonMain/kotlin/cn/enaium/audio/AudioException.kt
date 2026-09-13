package cn.enaium.audio

/**
 * Thrown when an audio device, stream or driver operation fails.
 *
 * Argument validation that a caller can fix by inspecting its own code throws
 * [IllegalArgumentException] instead; [AudioException] always describes a
 * failure that came back from the platform audio API.
 */
class AudioException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

package cn.enaium.audio

import javax.sound.sampled.AudioFormat as JavaSoundFormat
import javax.sound.sampled.AudioSystem as JavaSound
import javax.sound.sampled.DataLine
import javax.sound.sampled.LineUnavailableException
import javax.sound.sampled.Mixer
import javax.sound.sampled.SourceDataLine
import javax.sound.sampled.TargetDataLine

/**
 * Backend built on `javax.sound.sampled`, the audio API of the JDK.
 *
 * Every `Mixer` the platform reports becomes an [AudioDevice]; the mixer that
 * supports the requested line type first is reported as the default. Capture
 * runs on `TargetDataLine`, playback on `SourceDataLine`, both in blocking
 * mode, which is what [AudioInput.read] and [AudioOutput.write] map to.
 *
 * JavaSound resamples nothing: a format the mixer does not advertise fails with
 * an [AudioException] naming the mixer and the format.
 */
class JvmAudioSystem : AudioSystem {

    override val name: String = "JavaSound"

    private val mixers: List<Mixer> = JavaSound.getMixerInfo()
        .mapNotNull { info -> runCatching { JavaSound.getMixer(info) }.getOrNull() }

    private val inputMixers: List<Mixer> = mixers.filter { supports(it, TargetDataLine::class.java) }
    private val outputMixers: List<Mixer> = mixers.filter { supports(it, SourceDataLine::class.java) }

    private var closed = false

    override fun inputDevices(): List<AudioDevice> = inputMixers.mapIndexed { index, mixer ->
        device(mixer, AudioDeviceType.INPUT, index == 0)
    }

    override fun outputDevices(): List<AudioDevice> = outputMixers.mapIndexed { index, mixer ->
        device(mixer, AudioDeviceType.OUTPUT, index == 0)
    }

    override fun defaultInputDevice(): AudioDevice? = inputDevices().firstOrNull()

    override fun defaultOutputDevice(): AudioDevice? = outputDevices().firstOrNull()

    override fun openInput(format: AudioFormat, device: AudioDevice?, bufferFrames: Int): AudioInput {
        checkOpen()
        val mixer = inputMixer(device)
        val info = DataLine.Info(TargetDataLine::class.java, toJavaSoundFormat(format))
        if (!mixer.isLineSupported(info)) {
            throw AudioException("${mixer.mixerInfo.name} does not support capture of $format")
        }
        val line = try {
            mixer.getLine(info) as TargetDataLine
        } catch (e: Exception) {
            throw AudioException("cannot open capture line of ${mixer.mixerInfo.name}: ${e.message}", e)
        }
        openLine(mixer, format, bufferFrames) { javaFormat, bytes ->
            if (bytes > 0) line.open(javaFormat, bytes) else line.open(javaFormat)
        }
        return JvmAudioInput(format, deviceOf(mixer, AudioDeviceType.INPUT), line)
    }

    override fun openOutput(format: AudioFormat, device: AudioDevice?, bufferFrames: Int): AudioOutput {
        checkOpen()
        val mixer = outputMixer(device)
        val info = DataLine.Info(SourceDataLine::class.java, toJavaSoundFormat(format))
        if (!mixer.isLineSupported(info)) {
            throw AudioException("${mixer.mixerInfo.name} does not support playback of $format")
        }
        val line = try {
            mixer.getLine(info) as SourceDataLine
        } catch (e: Exception) {
            throw AudioException("cannot open playback line of ${mixer.mixerInfo.name}: ${e.message}", e)
        }
        openLine(mixer, format, bufferFrames) { javaFormat, bytes ->
            if (bytes > 0) line.open(javaFormat, bytes) else line.open(javaFormat)
        }
        return JvmAudioOutput(format, deviceOf(mixer, AudioDeviceType.OUTPUT), line)
    }

    override fun close() {
        closed = true
    }

    private fun checkOpen() {
        check(!closed) { "audio system is closed" }
    }

    /**
     * Opens a line with the requested buffer, falling back to the driver
     * default when [bufferFrames] is `0`. The caller supplies the actual `open`
     * call because JavaSound declares `open(AudioFormat, int)` on
     * `TargetDataLine`/`SourceDataLine` rather than on `Line`.
     */
    private fun openLine(
        mixer: Mixer,
        format: AudioFormat,
        bufferFrames: Int,
        open: (JavaSoundFormat, Int) -> Unit,
    ) {
        try {
            open(toJavaSoundFormat(format), if (bufferFrames > 0) format.framesToBytes(bufferFrames) else 0)
        } catch (e: LineUnavailableException) {
            throw AudioException("${mixer.mixerInfo.name} cannot open $format: ${e.message}", e)
        }
    }

    private fun inputMixer(device: AudioDevice?): Mixer = mixer(device, AudioDeviceType.INPUT, "capture")

    private fun outputMixer(device: AudioDevice?): Mixer = mixer(device, AudioDeviceType.OUTPUT, "playback")

    /**
     * Resolves a device id back to the mixer it names.
     *
     * [AudioDevice.id] carries the index into [mixers], the list of every
     * mixer, so the lookup has to go through that list and then check the
     * direction - indexing [inputMixers]/[outputMixers] with a full list index
     * would pick an unrelated mixer.
     */
    private fun mixer(device: AudioDevice?, type: AudioDeviceType, direction: String): Mixer {
        val devices = if (type == AudioDeviceType.INPUT) inputMixers else outputMixers
        if (device == null) {
            return devices.firstOrNull() ?: throw AudioException("no $direction device is available")
        }
        val index = mixerIndex(device)
        val resolved = mixers.getOrNull(index)
            ?: throw AudioException("not a JavaSound device: ${device.id}")
        if (resolved !in devices) throw AudioException("${device.name} is not a $direction device")
        return resolved
    }

    private fun mixerIndex(device: AudioDevice): Int =
        device.id.removePrefix(MIXER_PREFIX).toIntOrNull()
            ?: throw AudioException("not a JavaSound device: ${device.id}")

    private fun deviceOf(mixer: Mixer, type: AudioDeviceType): AudioDevice {
        val devices = if (type == AudioDeviceType.INPUT) inputMixers else outputMixers
        return device(mixer, type, devices.firstOrNull() === mixer)
    }

    private fun device(mixer: Mixer, type: AudioDeviceType, isDefault: Boolean): AudioDevice {
        val index = mixers.indexOf(mixer)
        return AudioDevice(
            id = "$MIXER_PREFIX$index",
            name = mixer.mixerInfo.name,
            type = type,
            isDefault = isDefault,
        )
    }

    /**
     * `true` when [mixer] exposes a line of [lineClass] for at least one of the
     * probe formats. Sound cards often publish one mixer per direction, so the
     * direction has to be probed per mixer.
     */
    private fun supports(mixer: Mixer, lineClass: Class<out DataLine>): Boolean =
        PROBE_FORMATS.any { mixer.isLineSupported(DataLine.Info(lineClass, it)) }

    private fun toJavaSoundFormat(format: AudioFormat): JavaSoundFormat {
        val encoding = when {
            format.sampleFormat.isFloat -> JavaSoundFormat.Encoding.PCM_FLOAT
            format.sampleFormat.isSigned -> JavaSoundFormat.Encoding.PCM_SIGNED
            else -> JavaSoundFormat.Encoding.PCM_UNSIGNED
        }
        return JavaSoundFormat(
            encoding,
            format.sampleRate.toFloat(),
            format.sampleFormat.bitsPerSample,
            format.channelCount,
            format.frameSizeBytes,
            format.sampleRate.toFloat(),
            false,
        )
    }

    private companion object {
        const val MIXER_PREFIX = "mixer:"

        /** Formats probed to decide whether a mixer can capture or play back. */
        val PROBE_FORMATS: List<JavaSoundFormat> = listOf(48000, 44100).flatMap { rate ->
            listOf(1, 2).map { channels ->
                JavaSoundFormat(rate.toFloat(), 16, channels, true, false)
            }
        }
    }
}

/** The `javax.sound.sampled` backend of this platform. */
actual fun audioSystem(): AudioSystem = JvmAudioSystem()

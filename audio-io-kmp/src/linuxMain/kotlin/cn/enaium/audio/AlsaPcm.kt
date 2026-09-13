@file:OptIn(ExperimentalForeignApi::class)

package cn.enaium.audio

import alsa.snd_pcm_t
import alsa.audio_io_alsa_api
import alsa.snd_pcm_hw_params_t
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.invoke
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.posix.EPIPE
import platform.posix.ESTRPIPE

/** Periods the device buffer holds. */
internal const val BUFFER_PERIODS: Int = 4

/** `snd_pcm_stream_t` value of a playback stream. */
internal const val ALSA_STREAM_PLAYBACK: Int = 0

/** `snd_pcm_stream_t` value of a capture stream. */
internal const val ALSA_STREAM_CAPTURE: Int = 1

/** `snd_pcm_access_t` value for blocking interleaved reads and writes. */
internal const val ALSA_ACCESS_RW_INTERLEAVED: Int = 3

/** `snd_pcm_format_t` values, little endian layout. */
private const val ALSA_FORMAT_U8: Int = 1
private const val ALSA_FORMAT_S16_LE: Int = 2
private const val ALSA_FORMAT_S32_LE: Int = 10
private const val ALSA_FORMAT_FLOAT_LE: Int = 14
private const val ALSA_FORMAT_FLOAT64_LE: Int = 16
private const val ALSA_FORMAT_S24_3LE: Int = 32

/** `-EPIPE`: the device ran out of data or the application read too late. */
internal const val ALSA_XRUN: Int = -EPIPE

/** `-ESTRPIPE`: the device was suspended, for example while the machine slept. */
internal const val ALSA_SUSPENDED: Int = -ESTRPIPE

/**
 * `snd_pcm_format_t` of a [AudioFormat].
 *
 * ALSA names the formats after their in-memory layout: [SampleFormat.PCM_S24] is
 * the packed three byte format (`S24_3LE`) and not the four byte container. Big
 * endian layouts are never requested because every Linux target of this library
 * is little-endian.
 */
internal fun AudioFormat.alsaFormat(): Int = when (sampleFormat) {
    SampleFormat.PCM_U8 -> ALSA_FORMAT_U8
    SampleFormat.PCM_S16 -> ALSA_FORMAT_S16_LE
    SampleFormat.PCM_S24 -> ALSA_FORMAT_S24_3LE
    SampleFormat.PCM_S32 -> ALSA_FORMAT_S32_LE
    SampleFormat.PCM_F32 -> ALSA_FORMAT_FLOAT_LE
    SampleFormat.PCM_F64 -> ALSA_FORMAT_FLOAT64_LE
}

/**
 * Opens and configures one ALSA PCM handle, returning it together with the
 * buffer size the driver settled on.
 *
 * The device is opened in the default blocking (`rw_interleaved`) access mode:
 * reads and writes return once the requested amount of frames moved, which is
 * exactly the contract of [AudioInput.read] and [AudioOutput.write]. The period
 * is set to the requested frame count and the device buffer to four periods, so
 * a short scheduling delay does not turn into a dropout.
 */
internal fun openPcm(
    deviceName: String,
    capture: Boolean,
    format: AudioFormat,
    bufferFrames: Int,
): Pair<CPointer<snd_pcm_t>, Int> {
    val api = Alsa.api()
    memScoped {
        val holder = alloc<CPointerVar<snd_pcm_t>>()
        val rc = api.pcm_open.required("snd_pcm_open")(
            holder.ptr,
            deviceName.withCString { it },
            if (capture) ALSA_STREAM_CAPTURE else ALSA_STREAM_PLAYBACK,
            0,
        )
        if (rc < 0) throw AudioException("snd_pcm_open($deviceName) failed: ${Alsa.error(rc)}")
        val pcm = holder.value ?: throw AudioException("snd_pcm_open($deviceName) returned no handle")
        val applied = try {
            configurePcm(api, pcm, format, bufferFrames)
        } catch (e: Throwable) {
            api.pcm_close.required("snd_pcm_close")(pcm)
            throw e
        }
        return pcm to applied
    }
}

/** Applies the requested format and returns the buffer size the driver accepted. */
private fun configurePcm(
    api: audio_io_alsa_api,
    pcm: CPointer<snd_pcm_t>,
    format: AudioFormat,
    bufferFrames: Int,
): Int = withHwParams(api) { params: CPointer<snd_pcm_hw_params_t> ->
    Alsa.check(api.hw_params_any.required("snd_pcm_hw_params_any")(pcm, params), "snd_pcm_hw_params_any")
    Alsa.check(
        api.hw_params_set_access.required("snd_pcm_hw_params_set_access")(pcm, params, ALSA_ACCESS_RW_INTERLEAVED),
        "snd_pcm_hw_params_set_access",
    )
    Alsa.check(
        api.hw_params_set_format.required("snd_pcm_hw_params_set_format")(pcm, params, format.alsaFormat()),
        "snd_pcm_hw_params_set_format($format)",
    )
    Alsa.check(
        api.hw_params_set_channels.required("snd_pcm_hw_params_set_channels")(
            pcm,
            params,
            format.channelCount.toUInt(),
        ),
        "snd_pcm_hw_params_set_channels(${format.channelCount})",
    )
    // An exact rate: ALSA either accepts it or the caller learns that the device
    // cannot deliver the format. A silent resample would hide that.
    Alsa.check(
        api.hw_params_set_rate.required("snd_pcm_hw_params_set_rate")(pcm, params, format.sampleRate.toUInt(), 0),
        "snd_pcm_hw_params_set_rate(${format.sampleRate})",
    )

    memScoped {
        val period = alloc<ULongVar>().apply { value = bufferFrames.toULong() }
        Alsa.check(
            api.hw_params_set_period_size_near.required("snd_pcm_hw_params_set_period_size_near")(
                pcm,
                params,
                period.ptr,
                null,
            ),
            "snd_pcm_hw_params_set_period_size_near($bufferFrames)",
        )
        val buffer = alloc<ULongVar>().apply { value = (bufferFrames * BUFFER_PERIODS).toULong() }
        Alsa.check(
            api.hw_params_set_buffer_size_near.required("snd_pcm_hw_params_set_buffer_size_near")(
                pcm,
                params,
                buffer.ptr,
            ),
            "snd_pcm_hw_params_set_buffer_size_near(${bufferFrames * BUFFER_PERIODS})",
        )
    }

    Alsa.check(api.hw_params.required("snd_pcm_hw_params")(pcm, params), "snd_pcm_hw_params")

    // The applied parameters are written back into the same object.
    memScoped {
        val allocated = alloc<ULongVar>()
        if (api.hw_params_get_buffer_size.required("snd_pcm_hw_params_get_buffer_size")(params, allocated.ptr) >= 0) {
            allocated.value.toInt()
        } else {
            bufferFrames * BUFFER_PERIODS
        }
    }
}

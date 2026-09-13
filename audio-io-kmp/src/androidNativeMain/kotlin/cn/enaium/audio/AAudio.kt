@file:OptIn(ExperimentalForeignApi::class)

package cn.enaium.audio

import aaudio.audio_io_aaudio_api
import cnames.structs.AAudioStream
import cnames.structs.AAudioStreamBuilder
import kotlinx.cinterop.CPointed
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.invoke
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import platform.posix.RTLD_NOW
import platform.posix.dlopen
import platform.posix.dlsym

/** `aaudio_direction_t` of a capture stream. */
internal const val AAUDIO_DIRECTION_INPUT: Int = 1

/** `aaudio_direction_t` of a playback stream. */
internal const val AAUDIO_DIRECTION_OUTPUT: Int = 0

/** `aaudio_format_t` of a 16 bit signed stream. */
internal const val AAUDIO_FORMAT_PCM_I16: Int = 1

/** `aaudio_format_t` of a 32 bit float stream. */
internal const val AAUDIO_FORMAT_PCM_FLOAT: Int = 2

/** `aaudio_format_t` of a packed 24 bit stream (API 31 and newer). */
internal const val AAUDIO_FORMAT_PCM_I24_PACKED: Int = 3

/** `aaudio_format_t` of a 32 bit integer stream (API 31 and newer). */
internal const val AAUDIO_FORMAT_PCM_I32: Int = 4

/** `aaudio_sharing_mode_t` of a stream that is mixed with the other apps. */
internal const val AAUDIO_SHARING_MODE_SHARED: Int = 1

/** `aaudio_performance_mode_t` that asks for the smallest practical buffer. */
internal const val AAUDIO_PERFORMANCE_MODE_LOW_LATENCY: Int = 12

/** `aaudio_stream_state_t` of a stream that is running. */
internal const val AAUDIO_STATE_STARTED: Int = 4

/** `aaudio_stream_state_t` of a stream that was closed. */
internal const val AAUDIO_STATE_CLOSED: Int = 12

/** `AAUDIO_OK`. */
internal const val AAUDIO_OK: Int = 0

/**
 * libaaudio loaded at runtime.
 *
 * AAudio is a C API of the Android platform from API level 26 on. Resolving it
 * with `dlsym` keeps the library loadable on older devices, where opening a
 * stream reports that AAudio is unavailable instead of the process failing to
 * start.
 */
internal object AAudio {

    private const val LIBRARY = "libaaudio.so"

    private val handle: COpaquePointer? = dlopen(LIBRARY, RTLD_NOW)

    private val api: audio_io_aaudio_api? = handle?.let { load(it) }

    /** `true` when the running Android version provides AAudio. */
    val available: Boolean
        get() {
            val table = api ?: return false
            return table.createStreamBuilder != null && table.builderOpenStream != null
        }

    /** The resolved entry point table. */
    fun api(): audio_io_aaudio_api = api ?: throw AudioException(
        "AAudio is not available on this device: $LIBRARY could not be loaded. " +
                "AAudio requires Android 8.0 (API level 26) or newer.",
    )

    /** `"<call>: <aaudio result>"`, for example `"AAudioStreamBuilder_openStream: Invalid state"`. */
    fun resultText(rc: Int): String {
        val text = api?.convertResultToText?.invoke(rc)?.toKString()
        return if (text.isNullOrEmpty()) "AAudio error $rc" else text
    }

    /** Fails with the AAudio description of [rc] when the call reported an error. */
    fun check(rc: Int, what: String) {
        if (rc != AAUDIO_OK) throw AudioException("$what failed: ${resultText(rc)}")
    }

    private fun load(handle: COpaquePointer): audio_io_aaudio_api {
        val table = nativeHeap.alloc<audio_io_aaudio_api>()
        table.createStreamBuilder = symbol(handle, "AAudio_createStreamBuilder")
        table.builderDelete = symbol(handle, "AAudioStreamBuilder_delete")
        table.builderSetDeviceId = symbol(handle, "AAudioStreamBuilder_setDeviceId")
        table.builderSetDirection = symbol(handle, "AAudioStreamBuilder_setDirection")
        table.builderSetSharingMode = symbol(handle, "AAudioStreamBuilder_setSharingMode")
        table.builderSetFormat = symbol(handle, "AAudioStreamBuilder_setFormat")
        table.builderSetChannelCount = symbol(handle, "AAudioStreamBuilder_setChannelCount")
        table.builderSetSampleRate = symbol(handle, "AAudioStreamBuilder_setSampleRate")
        table.builderSetBufferCapacityInFrames =
            symbol(handle, "AAudioStreamBuilder_setBufferCapacityInFrames")
        table.builderSetPerformanceMode = symbol(handle, "AAudioStreamBuilder_setPerformanceMode")
        table.builderOpenStream = symbol(handle, "AAudioStreamBuilder_openStream")
        table.streamClose = symbol(handle, "AAudioStream_close")
        table.streamRequestStart = symbol(handle, "AAudioStream_requestStart")
        table.streamRequestStop = symbol(handle, "AAudioStream_requestStop")
        table.streamRead = symbol(handle, "AAudioStream_read")
        table.streamWrite = symbol(handle, "AAudioStream_write")
        table.streamSetBufferSizeInFrames = symbol(handle, "AAudioStream_setBufferSizeInFrames")
        table.streamGetBufferSizeInFrames = symbol(handle, "AAudioStream_getBufferSizeInFrames")
        table.streamGetBufferCapacityInFrames = symbol(handle, "AAudioStream_getBufferCapacityInFrames")
        table.streamGetSampleRate = symbol(handle, "AAudioStream_getSampleRate")
        table.streamGetChannelCount = symbol(handle, "AAudioStream_getChannelCount")
        table.streamGetDeviceId = symbol(handle, "AAudioStream_getDeviceId")
        table.streamGetFormat = symbol(handle, "AAudioStream_getFormat")
        table.streamGetState = symbol(handle, "AAudioStream_getState")
        table.streamWaitForStateChange = symbol(handle, "AAudioStream_waitForStateChange")
        table.convertResultToText = symbol(handle, "AAudio_convertResultToText")
        return table
    }

    private fun <T : CPointed> symbol(handle: COpaquePointer, name: String): CPointer<T>? =
        dlsym(handle, name)?.reinterpret()
}

/** An entry point that is guaranteed to exist. */
internal fun <T : CPointed> CPointer<T>?.required(name: String): CPointer<T> =
    this ?: throw AudioException("libaaudio does not export $name")

/**
 * `aaudio_format_t` of a [AudioFormat], or `null` for a format AAudio cannot
 * carry.
 */
internal fun AudioFormat.aaudioFormat(): Int? = when (sampleFormat) {
    SampleFormat.PCM_S16 -> AAUDIO_FORMAT_PCM_I16
    SampleFormat.PCM_F32 -> AAUDIO_FORMAT_PCM_FLOAT
    SampleFormat.PCM_S24 -> AAUDIO_FORMAT_PCM_I24_PACKED
    SampleFormat.PCM_S32 -> AAUDIO_FORMAT_PCM_I32
    SampleFormat.PCM_U8, SampleFormat.PCM_F64 -> null
}

/** Allocates a builder handle and releases it once [block] returned. */
internal inline fun <R> AAudio.withBuilder(block: (CPointer<AAudioStreamBuilder>) -> R): R {
    val api = api()
    memScoped {
        val holder = alloc<CPointerVar<AAudioStreamBuilder>>()
        AAudio.check(
            api.createStreamBuilder.required("AAudio_createStreamBuilder")(holder.ptr),
            "AAudio_createStreamBuilder",
        )
        val builder = holder.value ?: throw AudioException("AAudio_createStreamBuilder returned no builder")
        try {
            return block(builder)
        } finally {
            api.builderDelete.required("AAudioStreamBuilder_delete")(builder)
        }
    }
}

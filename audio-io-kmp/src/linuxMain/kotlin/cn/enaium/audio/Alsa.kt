@file:OptIn(ExperimentalForeignApi::class)

package cn.enaium.audio

import alsa.audio_io_alsa_api
import alsa.snd_pcm_hw_params_t
import kotlinx.cinterop.ByteVar
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
import platform.posix.free

/**
 * libasound loaded at runtime.
 *
 * The ALSA entry points are resolved with `dlsym` instead of being linked, so
 * the published klib has no link time dependency on a development package: any
 * Linux system that can play audio has `libasound.so.2`, which is the only
 * requirement this backend has.
 */
internal object Alsa {

    /** The shared object a normal Linux installation provides. */
    private const val LIBRARY = "libasound.so.2"

    /** Fallback for installations that only ship the development symlink. */
    private const val LIBRARY_DEVELOPMENT = "libasound.so"

    private val handle: COpaquePointer? =
        dlopen(LIBRARY, RTLD_NOW) ?: dlopen(LIBRARY_DEVELOPMENT, RTLD_NOW)

    private val api: audio_io_alsa_api? = handle?.let { load(it) }

    /** `true` when libasound was found and every entry point this backend needs is present. */
    val available: Boolean
        get() {
            val table = api ?: return false
            return table.pcm_open != null && table.pcm_close != null && table.hw_params_malloc != null
        }

    /**
     * The resolved entry point table.
     *
     * @throws AudioException when libasound is missing, which is the only
     *   situation in which this backend cannot work at all.
     */
    fun api(): audio_io_alsa_api = api
        ?: throw AudioException(
            "ALSA is not available: neither $LIBRARY nor $LIBRARY_DEVELOPMENT could be loaded",
        )

    /** `"<call>: <alsa error>"`, for example `"snd_pcm_open: No such file or directory"`. */
    fun error(rc: Int): String {
        val message = api?.strerror?.invoke(rc)?.toKString()
        return if (message.isNullOrEmpty()) "ALSA error $rc" else message
    }

    /** Fails with the ALSA description of [rc] when the call reported an error. */
    fun check(rc: Int, what: String) {
        if (rc < 0) throw AudioException("$what failed: ${error(rc)}")
    }

    private fun load(handle: COpaquePointer): audio_io_alsa_api {
        val table = nativeHeap.alloc<audio_io_alsa_api>()
        table.pcm_open = symbol(handle, "snd_pcm_open")
        table.pcm_close = symbol(handle, "snd_pcm_close")
        table.pcm_prepare = symbol(handle, "snd_pcm_prepare")
        table.pcm_start = symbol(handle, "snd_pcm_start")
        table.pcm_drop = symbol(handle, "snd_pcm_drop")
        table.pcm_drain = symbol(handle, "snd_pcm_drain")
        table.pcm_resume = symbol(handle, "snd_pcm_resume")
        table.pcm_recover = symbol(handle, "snd_pcm_recover")
        table.pcm_readi = symbol(handle, "snd_pcm_readi")
        table.pcm_writei = symbol(handle, "snd_pcm_writei")
        table.pcm_avail_update = symbol(handle, "snd_pcm_avail_update")
        table.hw_params_malloc = symbol(handle, "snd_pcm_hw_params_malloc")
        table.hw_params_free = symbol(handle, "snd_pcm_hw_params_free")
        table.hw_params_any = symbol(handle, "snd_pcm_hw_params_any")
        table.hw_params_set_access = symbol(handle, "snd_pcm_hw_params_set_access")
        table.hw_params_set_format = symbol(handle, "snd_pcm_hw_params_set_format")
        table.hw_params_set_channels = symbol(handle, "snd_pcm_hw_params_set_channels")
        table.hw_params_set_rate = symbol(handle, "snd_pcm_hw_params_set_rate")
        table.hw_params_set_period_size_near = symbol(handle, "snd_pcm_hw_params_set_period_size_near")
        table.hw_params_set_buffer_size_near = symbol(handle, "snd_pcm_hw_params_set_buffer_size_near")
        table.hw_params = symbol(handle, "snd_pcm_hw_params")
        table.hw_params_get_period_size = symbol(handle, "snd_pcm_hw_params_get_period_size")
        table.hw_params_get_buffer_size = symbol(handle, "snd_pcm_hw_params_get_buffer_size")
        table.strerror = symbol(handle, "snd_strerror")
        table.device_name_hint = symbol(handle, "snd_device_name_hint")
        table.device_name_free_hint = symbol(handle, "snd_device_name_free_hint")
        table.device_name_get_hint = symbol(handle, "snd_device_name_get_hint")
        return table
    }

    private fun <T : CPointed> symbol(handle: COpaquePointer, name: String): CPointer<T>? =
        dlsym(handle, name)?.reinterpret()
}

/** An entry point that is guaranteed to exist; anything else means the system libasound is broken. */
@OptIn(ExperimentalForeignApi::class)
internal fun <T : CPointed> CPointer<T>?.required(name: String): CPointer<T> =
    this ?: throw AudioException("libasound does not export $name")

/**
 * The strings of one `snd_device_name_hint` entry.
 *
 * ALSA allocates each of them separately, so they are copied into Kotlin strings
 * before the hint array is released.
 */
@OptIn(ExperimentalForeignApi::class)
internal class HintStrings(private val name: String?, private val description: String?, private val direction: String?) {
    val deviceName: String? get() = name
    val deviceDescription: String? get() = description

    /** `"Input"`, `"Output"` or `null` when the device works in both directions. */
    val ioDirection: String? get() = direction

    companion object {
        /** Reads one hint entry, or returns `null` when it has no usable name. */
        fun read(hint: COpaquePointer?, api: audio_io_alsa_api): HintStrings? {
            if (hint == null) return null
            val getHint = api.device_name_get_hint.required("snd_device_name_get_hint")
            val name = "NAME".withCString { getHint(hint, it) }.take()
            if (name == null || name.isBlank() || name == "null") {
                "DESC".withCString { getHint(hint, it) }.take()
                "IOID".withCString { getHint(hint, it) }.take()
                return null
            }
            return HintStrings(
                name,
                "DESC".withCString { getHint(hint, it) }.take(),
                "IOID".withCString { getHint(hint, it) }.take(),
            )
        }

        /** Copies a C string returned by a hint accessor and frees it. */
        private fun CPointer<ByteVar>?.take(): String? {
            if (this == null) return null
            val text = toKString()
            free(this)
            return text
        }
    }
}

/** Runs [block] with a `snd_pcm_hw_params_t` allocation that is always released. */
@OptIn(ExperimentalForeignApi::class)
internal inline fun <R> withHwParams(api: audio_io_alsa_api, block: (CPointer<snd_pcm_hw_params_t>) -> R): R =
    memScoped {
        val holder = alloc<CPointerVar<snd_pcm_hw_params_t>>()
        Alsa.check(
            api.hw_params_malloc.required("snd_pcm_hw_params_malloc")(holder.ptr),
            "snd_pcm_hw_params_malloc",
        )
        val params = holder.value ?: throw AudioException("snd_pcm_hw_params_malloc returned no allocation")
        try {
            block(params)
        } finally {
            api.hw_params_free.required("snd_pcm_hw_params_free")(params)
        }
    }

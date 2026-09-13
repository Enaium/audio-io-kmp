@file:OptIn(ExperimentalForeignApi::class)

package cn.enaium.audio

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.COpaquePointerVar
import kotlinx.cinterop.CValue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.cValue
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.plus
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.CoreAudio.AudioDeviceID
import platform.CoreAudio.AudioDeviceIDVar
import platform.CoreAudio.AudioObjectGetPropertyData
import platform.CoreAudio.AudioObjectGetPropertyDataSize
import platform.CoreAudio.AudioObjectID
import platform.CoreAudio.AudioObjectPropertyAddress
import platform.CoreAudio.AudioObjectPropertyScope
import platform.CoreAudio.AudioObjectPropertySelector
import platform.CoreAudio.kAudioDevicePropertyDeviceUID
import platform.CoreAudio.kAudioDevicePropertyStreamConfiguration
import platform.CoreAudio.kAudioHardwarePropertyDefaultInputDevice
import platform.CoreAudio.kAudioHardwarePropertyDefaultOutputDevice
import platform.CoreAudio.kAudioHardwarePropertyDevices
import platform.CoreAudio.kAudioObjectPropertyElementMain
import platform.CoreAudio.kAudioObjectPropertyName
import platform.CoreAudio.kAudioObjectPropertyScopeGlobal
import platform.CoreAudio.kAudioObjectPropertyScopeInput
import platform.CoreAudio.kAudioObjectPropertyScopeOutput
import platform.CoreAudio.kAudioObjectSystemObject
import platform.CoreAudioTypes.AudioBufferList
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringGetCString
import platform.CoreFoundation.CFStringGetLength
import platform.CoreFoundation.CFStringGetMaximumSizeForEncoding
import platform.CoreFoundation.kCFStringEncodingUTF8

/**
 * Enumeration on top of the Core Audio object property API.
 *
 * A Core Audio device is identified by an `AudioDeviceID`; what the rest of the
 * library exposes as [AudioDevice.id] is the device UID string, which is also
 * what AudioQueue accepts when a stream has to run on a specific device.
 */
internal object CoreAudioDevices {

    /** Every device the HAL knows about, whatever its direction. */
    fun all(): List<AudioDeviceID> {
        val size = propertySize(kAudioObjectSystemObject.convert(), kAudioHardwarePropertyDevices, globalScope)
        if (size <= 0) return emptyList()
        return memScoped {
            val sizeHolder = alloc<UIntVar>().apply { value = size.convert() }
            val data = allocArray<ByteVar>(size)
            val status = AudioObjectGetPropertyData(
                kAudioObjectSystemObject.convert(),
                address(kAudioHardwarePropertyDevices, globalScope),
                0u,
                null,
                sizeHolder.ptr,
                data,
            )
            if (status != 0) return@memScoped emptyList()
            val ids = data.reinterpret<AudioDeviceIDVar>()
            (0 until sizeHolder.value.toInt() / sizeOf<AudioDeviceIDVar>().toInt()).map { ids[it] }
        }
    }

    /** The UID string of [device], stable across restarts and reconnects. */
    fun uid(device: AudioDeviceID): String? =
        stringProperty(device, kAudioDevicePropertyDeviceUID, globalScope)

    /** The human readable name of [device]. */
    fun name(device: AudioDeviceID): String =
        stringProperty(device, kAudioObjectPropertyName, globalScope).orEmpty()

    /** Channels [device] has in [scope], `0` when it does not serve that direction. */
    fun channels(device: AudioDeviceID, scope: AudioObjectPropertyScope): Int {
        val size = propertySize(device, kAudioDevicePropertyStreamConfiguration, scope)
        if (size <= 0) return 0
        return memScoped {
            val sizeHolder = alloc<UIntVar>().apply { value = size.convert() }
            val data = allocArray<ByteVar>(size)
            val status = AudioObjectGetPropertyData(
                device,
                address(kAudioDevicePropertyStreamConfiguration, scope),
                0u,
                null,
                sizeHolder.ptr,
                data,
            )
            if (status != 0) return@memScoped 0
            // AudioBufferList is variable length: the buffer count comes first,
            // the buffers themselves follow it.
            val list = data.reinterpret<AudioBufferList>().pointed
            val count = list.mNumberBuffers.toInt()
            var total = 0
            for (index in 0 until count) {
                total += list.mBuffers[index].mNumberChannels.toInt()
            }
            total
        }
    }

    /** The device macOS uses for [selector], one of the `kAudioHardwarePropertyDefault*` selectors. */
    fun defaultDevice(selector: AudioObjectPropertySelector): AudioDeviceID? = memScoped {
        val id = alloc<AudioDeviceIDVar>()
        val size = alloc<UIntVar>().apply { value = sizeOf<AudioDeviceIDVar>().convert() }
        val status = AudioObjectGetPropertyData(
            kAudioObjectSystemObject.convert(),
            address(selector, globalScope),
            0u,
            null,
            size.ptr,
            id.ptr,
        )
        if (status == 0) id.value else null
    }

    /** Default input device selector. */
    val defaultInputSelector: AudioObjectPropertySelector get() = kAudioHardwarePropertyDefaultInputDevice

    /** Default output device selector. */
    val defaultOutputSelector: AudioObjectPropertySelector get() = kAudioHardwarePropertyDefaultOutputDevice

    /** Input scope, queried for capture devices. */
    val inputScope: AudioObjectPropertyScope get() = kAudioObjectPropertyScopeInput

    /** Output scope, queried for playback devices. */
    val outputScope: AudioObjectPropertyScope get() = kAudioObjectPropertyScopeOutput

    private val globalScope: AudioObjectPropertyScope get() = kAudioObjectPropertyScopeGlobal

    private fun propertySize(
        objectId: AudioObjectID,
        selector: AudioObjectPropertySelector,
        scope: AudioObjectPropertyScope,
    ): Int = memScoped {
        val size = alloc<UIntVar>()
        val status = AudioObjectGetPropertyDataSize(objectId, address(selector, scope), 0u, null, size.ptr)
        if (status != 0) 0 else size.value.toInt()
    }

    private fun stringProperty(
        objectId: AudioObjectID,
        selector: AudioObjectPropertySelector,
        scope: AudioObjectPropertyScope,
    ): String? {
        val size = propertySize(objectId, selector, scope)
        if (size <= 0) return null
        return memScoped {
            val holder = alloc<COpaquePointerVar>().apply { value = null }
            val sizeHolder = alloc<UIntVar>().apply { value = size.convert() }
            val status = AudioObjectGetPropertyData(
                objectId,
                address(selector, scope),
                0u,
                null,
                sizeHolder.ptr,
                holder.ptr,
            )
            if (status != 0) return@memScoped null
            coreFoundationString(holder.value)
        }
    }

    private fun address(
        selector: AudioObjectPropertySelector,
        scope: AudioObjectPropertyScope,
    ): CValue<AudioObjectPropertyAddress> = cValue {
        mSelector = selector
        mScope = scope
        mElement = kAudioObjectPropertyElementMain
    }
}

/**
 * Reads a Core Foundation string into a Kotlin string and releases it.
 *
 * Returns `null` for a null reference.
 */
internal fun coreFoundationString(reference: COpaquePointer?): String? {
    if (reference == null) return null
    val length = CFStringGetLength(reference.reinterpret())
    val capacity = CFStringGetMaximumSizeForEncoding(length, kCFStringEncodingUTF8) + 1
    val buffer = ByteArray(capacity.toInt())
    val result = buffer.usePinned { pinned ->
        if (!CFStringGetCString(reference.reinterpret(), pinned.addressOf(0), capacity, kCFStringEncodingUTF8)) {
            ""
        } else {
            pinned.addressOf(0).toKString()
        }
    }
    CFRelease(reference)
    return result
}

@file:OptIn(ExperimentalForeignApi::class)

package cn.enaium.audio

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.UShortVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.invoke
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKStringFromUtf16
import kotlinx.cinterop.value
import platform.windows.COINIT_MULTITHREADED
import platform.windows.CoInitializeEx
import platform.windows.CoTaskMemFree
import platform.windows.CoUninitialize
import platform.windows.IPropertyStore
import platform.windows.PropVariantClear
import platform.windows.STGM_READ
import platform.windows.VT_LPWSTR
import platform.windows.tagPROPVARIANT
import platform.windows.tWAVEFORMATEX
import wasapi.DEVICE_STATE_ACTIVE
import wasapi.IMMDevice
import wasapi.IMMDeviceCollection
import wasapi.IMMDeviceEnumerator
import wasapi.PKEY_Device_FriendlyName
import wasapi.eCapture
import wasapi.eRender

/**
 * Backend built on the Windows Core Audio session API (WASAPI).
 *
 * Enumeration walks the active endpoints of the shared-mode device
 * enumerator; [AudioDevice.id] is the endpoint id string the API hands out,
 * which is exactly what `GetDevice` expects when a stream is pinned to a
 * device. Streams run in shared mode and are event driven, so the application
 * never has to poll the device.
 *
 * COM is initialised once for the lifetime of the system: the calling thread
 * enters a multithreaded apartment in the constructor and leaves it in
 * [close]. Every stream keeps its own apartment reference, so a stream may be
 * opened and closed from a thread other than the one that created the system.
 */
class WasapiSystem : AudioSystem {

    override val name: String = "WASAPI"

    private var closed = false
    private var comInitialized = false

    init {
        val hr = CoInitializeEx(null, COINIT_MULTITHREADED)
        if (hr < 0) wasapiError("CoInitializeEx", hr, null)
        comInitialized = true
    }

    override fun inputDevices(): List<AudioDevice> = devices(eCapture)

    override fun outputDevices(): List<AudioDevice> = devices(eRender)

    override fun defaultInputDevice(): AudioDevice? =
        inputDevices().firstOrNull { it.isDefault }

    override fun defaultOutputDevice(): AudioDevice? =
        outputDevices().firstOrNull { it.isDefault }

    override fun openInput(format: AudioFormat, device: AudioDevice?, bufferFrames: Int): AudioInput {
        check(!closed) { "audio system is closed" }
        val frames = if (bufferFrames > 0) bufferFrames else format.defaultBufferFrames()
        val ring = PcmRing(format, frames * RING_BUFFERS)
        val signal = createRtSignal()
        val control = try {
            WasapiInput(format, frames, ring, signal, device?.id)
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
            WasapiOutput(format, frames, ring, signal, device?.id)
        } catch (e: Throwable) {
            ring.close()
            signal.close()
            throw e
        }
        return CallbackAudioOutput(format, device, frames, ring, signal, control)
    }

    override fun close() {
        if (closed) return
        closed = true
        if (comInitialized) {
            CoUninitialize()
            comInitialized = false
        }
    }

    /** Lists the active endpoints of one direction, marking the system default. */
    private fun devices(flow: UInt): List<AudioDevice> {
        val input = flow == eCapture
        val enumerator = openEnumerator()
        try {
            val defaultId = defaultDeviceId(enumerator, flow)
            val collection = enumEndpoints(enumerator, flow)
            try {
                val count = collectionCount(collection)
                val result = ArrayList<AudioDevice>(count)
                for (index in 0 until count) {
                    val device = collectionItem(collection, index.toUInt()) ?: continue
                    try {
                        val id = readDeviceId(device) ?: continue
                        val channels = readChannels(device)
                        result += AudioDevice(
                            id = id,
                            name = readFriendlyName(device),
                            type = if (input) AudioDeviceType.INPUT else AudioDeviceType.OUTPUT,
                            isDefault = id == defaultId,
                            channelCounts = if (channels > 0) listOf(channels) else emptyList(),
                        )
                    } finally {
                        releaseDevice(device)
                    }
                }
                return result
            } finally {
                releaseCollection(collection)
            }
        } finally {
            releaseEnumerator(enumerator)
        }
    }

    /** Endpoint id of the default endpoint, or `null` when the system has none. */
    private fun defaultDeviceId(enumerator: CPointer<IMMDeviceEnumerator>, flow: UInt): String? {
        val device = try {
            openDevice(enumerator, flow, null)
        } catch (e: AudioException) {
            return null
        }
        return try {
            readDeviceId(device)
        } finally {
            releaseDevice(device)
        }
    }

    private fun enumEndpoints(
        enumerator: CPointer<IMMDeviceEnumerator>,
        flow: UInt,
    ): CPointer<IMMDeviceCollection> = memScoped {
        val out = alloc<CPointerVar<IMMDeviceCollection>>()
        val hr = enumerator.pointed.lpVtbl!!.pointed.EnumAudioEndpoints!!(
            enumerator,
            flow,
            DEVICE_STATE_ACTIVE.toUInt(),
            out.ptr,
        )
        checkHr(hr, "IMMDeviceEnumerator::EnumAudioEndpoints", null)
        out.value ?: wasapiError("IMMDeviceEnumerator::EnumAudioEndpoints", hr, null)
    }

    private fun collectionCount(collection: CPointer<IMMDeviceCollection>): Int = memScoped {
        val out = alloc<UIntVar>()
        val hr = collection.pointed.lpVtbl!!.pointed.GetCount!!(collection, out.ptr)
        checkHr(hr, "IMMDeviceCollection::GetCount", null)
        out.value.toInt()
    }

    private fun collectionItem(
        collection: CPointer<IMMDeviceCollection>,
        index: UInt,
    ): CPointer<IMMDevice>? = memScoped {
        val out = alloc<CPointerVar<IMMDevice>>()
        val hr = collection.pointed.lpVtbl!!.pointed.Item!!(collection, index, out.ptr)
        if (hr < 0) null else out.value
    }

    /** Reads the endpoint id string; the caller owns the returned copy. */
    private fun readDeviceId(device: CPointer<IMMDevice>): String? = memScoped {
        val out = alloc<CPointerVar<UShortVar>>()
        val hr = device.pointed.lpVtbl!!.pointed.GetId!!(device, out.ptr)
        if (hr < 0) return null
        val pointer = out.value ?: return null
        try {
            pointer.toKStringFromUtf16()
        } finally {
            CoTaskMemFree(pointer)
        }
    }

    /** Reads the friendly name from the endpoint property store, or a fallback. */
    private fun readFriendlyName(device: CPointer<IMMDevice>): String = memScoped {
        val out = alloc<CPointerVar<IPropertyStore>>()
        val hr = device.pointed.lpVtbl!!.pointed.OpenPropertyStore!!(
            device,
            STGM_READ.toUInt(),
            out.ptr,
        )
        if (hr < 0) return "Unknown device"
        val store = out.value ?: return "Unknown device"
        try {
            val value = alloc<tagPROPVARIANT>()
            value.vt = 0u.toUShort()
            val read = store.pointed.lpVtbl!!.pointed.GetValue!!(
                store,
                PKEY_Device_FriendlyName.ptr,
                value.ptr,
            )
            if (read < 0) return "Unknown device"
            try {
                if (value.vt.toUInt() != VT_LPWSTR) return "Unknown device"
                value.pwszVal?.toKStringFromUtf16() ?: "Unknown device"
            } finally {
                PropVariantClear(value.ptr)
            }
        } finally {
            releaseStore(store)
        }
    }

    /** Mix-format channel count of the endpoint, or `0` when it cannot be read. */
    private fun readChannels(device: CPointer<IMMDevice>): Int {
        val client = try {
            activateClient(device)
        } catch (e: AudioException) {
            return 0
        }
        return try {
            memScoped {
                val out = alloc<CPointerVar<tWAVEFORMATEX>>()
                val hr = client.pointed.lpVtbl!!.pointed.GetMixFormat!!(client, out.ptr)
                if (hr < 0) return 0
                val mixFormat = out.value ?: return 0
                try {
                    mixFormat.pointed.nChannels.toInt()
                } finally {
                    // The mix format belongs to the client and is CoTaskMem allocated.
                    CoTaskMemFree(mixFormat)
                }
            }
        } finally {
            releaseClient(client)
        }
    }

    private companion object {
        /** Frames the ring holds, expressed in device buffers. */
        const val RING_BUFFERS = 4
    }
}

/** The WASAPI backend of this platform. */
actual fun audioSystem(): AudioSystem = WasapiSystem()

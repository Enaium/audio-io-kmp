@file:OptIn(ExperimentalForeignApi::class, ExperimentalAtomicApi::class)

package cn.enaium.audio

import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointed
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.COpaquePointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.invoke
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.value
import kotlinx.cinterop.wcstr
import platform.posix._GUID
import platform.posix.free
import platform.posix.malloc
import platform.posix.memcpy
import platform.posix.memset
import platform.windows.CLSCTX_ALL
import platform.windows.COINIT_MULTITHREADED
import platform.windows.CloseHandle
import platform.windows.CoCreateInstance
import platform.windows.CoInitializeEx
import platform.windows.CoUninitialize
import platform.windows.CreateEventW
import platform.windows.CreateThread
import platform.windows.GetCurrentThread
import platform.windows.GetLastError
import platform.windows.INFINITE
import platform.windows.IPropertyStore
import platform.windows.SetEvent
import platform.windows.SetThreadPriority
import platform.windows.THREAD_PRIORITY_TIME_CRITICAL
import platform.windows.WAVE_FORMAT_EXTENSIBLE
import platform.windows.WaitForSingleObject
import platform.windows.tWAVEFORMATEX
import wasapi.AUDCLNT_BUFFERFLAGS_SILENT
import wasapi.AUDCLNT_E_UNSUPPORTED_FORMAT
import wasapi.AUDCLNT_STREAMFLAGS_EVENTCALLBACK
import wasapi.CLSID_MMDeviceEnumerator
import wasapi.IID_IAudioCaptureClient
import wasapi.IID_IAudioClient
import wasapi.IID_IAudioRenderClient
import wasapi.IID_IMMDeviceEnumerator
import wasapi.IAudioCaptureClient
import wasapi.IAudioClient
import wasapi.IAudioRenderClient
import wasapi.IMMDevice
import wasapi.IMMDeviceCollection
import wasapi.IMMDeviceEnumerator
import wasapi.KSDATAFORMAT_SUBTYPE_IEEE_FLOAT
import wasapi.KSDATAFORMAT_SUBTYPE_PCM
import wasapi._AUDCLNT_SHAREMODE
import wasapi.audio_io_wave_format_extensible

/**
 * `S_FALSE`, the success code `IsFormatSupported` returns when the format is
 * only reachable through the shared-mode converter.
 */
private const val S_FALSE: Int = 1

// The MinGW headers do not expose these two stream flags (they are guarded by
// a Windows SDK version check), so they are spelled out here with the values
// from `audioclient.h`.
private const val AUDCLNT_STREAMFLAGS_AUTOCONVERTPCM: UInt = 0x80000000u
private const val AUDCLNT_STREAMFLAGS_SRC_DEFAULT_QUALITY: UInt = 0x08000000u

private val AUTOCONVERT_FLAGS: UInt =
    AUDCLNT_STREAMFLAGS_AUTOCONVERTPCM or AUDCLNT_STREAMFLAGS_SRC_DEFAULT_QUALITY

/** Throws an [AudioException] naming the failing call, its HRESULT and the format. */
internal fun wasapiError(call: String, hr: Int, format: AudioFormat?): Nothing {
    val code = "0x" + hr.toUInt().toString(16).padStart(8, '0')
    val suffix = if (format == null) "" else " for $format"
    throw AudioException("$call failed with HRESULT $code$suffix")
}

/** Fails the operation when [hr] reports an error; success codes pass through. */
internal fun checkHr(hr: Int, call: String, format: AudioFormat?) {
    if (hr < 0) wasapiError(call, hr, format)
}

/** Creates the shared-mode device enumerator; COM must already be initialised. */
internal fun openEnumerator(): CPointer<IMMDeviceEnumerator> = memScoped {
    val out = alloc<COpaquePointerVar>()
    val hr = CoCreateInstance(
        CLSID_MMDeviceEnumerator.ptr,
        null,
        CLSCTX_ALL.toUInt(),
        IID_IMMDeviceEnumerator.ptr,
        out.ptr,
    )
    checkHr(hr, "CoCreateInstance(IMMDeviceEnumerator)", null)
    out.value?.reinterpret()
        ?: wasapiError("CoCreateInstance(IMMDeviceEnumerator)", hr, null)
}

/** Opens an endpoint by id, or the default one of the given flow when [id] is null. */
internal fun openDevice(
    enumerator: CPointer<IMMDeviceEnumerator>,
    flow: UInt,
    id: String?,
): CPointer<IMMDevice> = memScoped {
    val out = alloc<CPointerVar<IMMDevice>>()
    val vtbl = enumerator.pointed.lpVtbl?.pointed
        ?: wasapiError("IMMDeviceEnumerator", -1, null)
    val hr = if (id == null) {
        vtbl.GetDefaultAudioEndpoint!!(enumerator, flow, wasapi.eConsole, out.ptr)
    } else {
        vtbl.GetDevice!!(enumerator, id.wcstr.ptr, out.ptr)
    }
    if (hr < 0) {
        wasapiError(if (id == null) "IMMDeviceEnumerator::GetDefaultAudioEndpoint" else "IMMDeviceEnumerator::GetDevice", hr, null)
    }
    out.value ?: wasapiError("IMMDeviceEnumerator device lookup", hr, null)
}

/** Activates the `IAudioClient` of an endpoint. */
internal fun activateClient(device: CPointer<IMMDevice>): CPointer<IAudioClient> = memScoped {
    val out = alloc<COpaquePointerVar>()
    val hr = device.pointed.lpVtbl!!.pointed.Activate!!(
        device,
        IID_IAudioClient.ptr,
        CLSCTX_ALL.toUInt(),
        null,
        out.ptr,
    )
    checkHr(hr, "IMMDevice::Activate(IAudioClient)", null)
    out.value?.reinterpret() ?: wasapiError("IMMDevice::Activate(IAudioClient)", hr, null)
}

internal fun releaseEnumerator(pointer: CPointer<IMMDeviceEnumerator>) {
    pointer.pointed.lpVtbl!!.pointed.Release!!(pointer)
}

internal fun releaseDevice(pointer: CPointer<IMMDevice>) {
    pointer.pointed.lpVtbl!!.pointed.Release!!(pointer)
}

internal fun releaseCollection(pointer: CPointer<IMMDeviceCollection>) {
    pointer.pointed.lpVtbl!!.pointed.Release!!(pointer)
}

internal fun releaseClient(pointer: CPointer<IAudioClient>) {
    pointer.pointed.lpVtbl!!.pointed.Release!!(pointer)
}

internal fun releaseStore(pointer: CPointer<IPropertyStore>) {
    pointer.pointed.lpVtbl!!.pointed.Release!!(pointer)
}

internal fun releaseCapture(pointer: CPointer<IAudioCaptureClient>) {
    pointer.pointed.lpVtbl!!.pointed.Release!!(pointer)
}

internal fun releaseRender(pointer: CPointer<IAudioRenderClient>) {
    pointer.pointed.lpVtbl!!.pointed.Release!!(pointer)
}

/** Speaker mask for [channels], or `0` when the layout is not one WASAPI knows. */
private fun channelMask(channels: Int): UInt = when (channels) {
    1 -> 0x4u
    2 -> 0x3u
    6 -> 0x3Fu
    8 -> 0x63Fu
    else -> 0u
}

/**
 * Fills [wave] with the extensible description of [format].
 *
 * The shared-mode mixer wants linear PCM in an extensible header, so the
 * sub-format GUID carries the sample layout while the packed
 * `WAVEFORMATEX` carries the rate, channel count and frame size.
 */
private fun fillWaveFormat(wave: audio_io_wave_format_extensible, format: AudioFormat) {
    val header = wave.format
    header.wFormatTag = WAVE_FORMAT_EXTENSIBLE.toUShort()
    header.nChannels = format.channelCount.toUShort()
    header.nSamplesPerSec = format.sampleRate.toUInt()
    header.nAvgBytesPerSec = (format.sampleRate * format.frameSizeBytes).toUInt()
    header.nBlockAlign = format.frameSizeBytes.toUShort()
    header.wBitsPerSample = format.sampleFormat.bitsPerSample.toUShort()
    header.cbSize = 22u.toUShort()

    wave.samples.validBitsPerSample = format.sampleFormat.bitsPerSample.toUShort()
    wave.channelMask = channelMask(format.channelCount)

    val subFormat = if (format.sampleFormat.isFloat) {
        KSDATAFORMAT_SUBTYPE_IEEE_FLOAT
    } else {
        KSDATAFORMAT_SUBTYPE_PCM
    }
    memcpy(wave.subFormat.ptr, subFormat.ptr, sizeOf<_GUID>().toULong())
}

/**
 * Shared device side of the WASAPI capture and playback streams.
 *
 * The stream runs in shared mode and is event driven: WASAPI signals an event
 * every period and a dedicated pump thread moves the frames between the device
 * and the [PcmRing]. The pump never allocates in its loop, so it is safe for
 * the real-time audio path, and it only ever touches the ring, the signal and
 * the COM pointers created before it starts.
 */
internal abstract class WasapiDevice(
    protected val format: AudioFormat,
    private val framesPerBuffer: Int,
    private val ring: PcmRing,
    private val signal: RtSignal,
    deviceId: String?,
    private val flow: UInt,
    private val capture: Boolean,
) : DeviceControl {

    private val self = StableRef.create(this)

    private var comInitialized = false
    private var client: CPointer<IAudioClient>? = null
    private var service: COpaquePointer? = null
    private var event: CPointer<out CPointed>? = null
    private var thread: CPointer<out CPointed>? = null
    private var silence: CPointer<ByteVar>? = null
    private var deviceBufferFrames = 0
    private var closed = false

    /** `1` once the pump must leave its loop; only set by [closeDevice]. */
    private val stop = AtomicInt(0)

    /** `1` while the pump must not touch the ring (open or stopped). */
    private val paused = AtomicInt(1)

    /** `1` while the pump is between the wake-up and the end of a device buffer. */
    private val busy = AtomicInt(0)

    init {
        try {
            val hr = CoInitializeEx(null, COINIT_MULTITHREADED)
            if (hr < 0) wasapiError("CoInitializeEx", hr, format)
            comInitialized = true

            val enumerator = openEnumerator()
            try {
                val device = openDevice(enumerator, flow, deviceId)
                try {
                    client = activateClient(device)
                } finally {
                    releaseDevice(device)
                }
            } finally {
                releaseEnumerator(enumerator)
            }

            configure()
            createPump()
        } catch (e: Throwable) {
            closeDevice()
            throw e
        }
    }

    override fun startDevice() {
        val audioClient = client ?: return
        paused.store(0)
        checkHr(
            audioClient.pointed.lpVtbl!!.pointed.Start!!(audioClient),
            "IAudioClient::Start",
            format,
        )
    }

    override fun stopDevice() {
        val audioClient = client ?: return
        val vtbl = audioClient.pointed.lpVtbl?.pointed ?: return
        // Pause the pump before stopping the client, then wait for a buffer it
        // may already be moving so the caller can flush the ring safely.
        paused.store(1)
        checkHr(vtbl.Stop!!(audioClient), "IAudioClient::Stop", format)
        checkHr(vtbl.Reset!!(audioClient), "IAudioClient::Reset", format)
        while (busy.load() != 0) {
            // The pump only holds the ring for the length of one device buffer.
        }
    }

    override fun flushDevice() {
        ring.clear()
    }

    override fun closeDevice() {
        if (closed) return
        closed = true

        // Wake the pump, then wait for it so it can no longer touch the ring.
        stop.store(1)
        paused.store(1)
        event?.let { SetEvent(it) }
        thread?.let {
            WaitForSingleObject(it, INFINITE)
            CloseHandle(it)
            thread = null
        }

        // Release the client and its service before the event they signal
        // through is closed.
        client?.let { audioClient ->
            val vtbl = audioClient.pointed.lpVtbl?.pointed
            if (vtbl != null) {
                vtbl.Stop?.invoke(audioClient)
                vtbl.Reset?.invoke(audioClient)
                vtbl.Release!!(audioClient)
            }
            client = null
        }
        service?.let {
            if (capture) releaseCapture(it.reinterpret()) else releaseRender(it.reinterpret())
            service = null
        }
        event?.let {
            CloseHandle(it)
            event = null
        }
        silence?.let {
            free(it)
            silence = null
        }
        if (comInitialized) {
            CoUninitialize()
            comInitialized = false
        }
        self.dispose()
    }

    /** Configures the client for the requested format, falling back to conversion. */
    private fun configure() {
        val audioClient = client ?: wasapiError("IAudioClient", -1, format)
        val vtbl = audioClient.pointed.lpVtbl?.pointed ?: wasapiError("IAudioClient", -1, format)

        memScoped {
            val wave = alloc<audio_io_wave_format_extensible>()
            fillWaveFormat(wave, format)
            val waveFormat = wave.ptr.reinterpret<tWAVEFORMATEX>()

            val supported = vtbl.IsFormatSupported!!(
                audioClient,
                _AUDCLNT_SHAREMODE.AUDCLNT_SHAREMODE_SHARED,
                waveFormat,
                null,
            )
            val convertible = supported == S_FALSE || supported == AUDCLNT_E_UNSUPPORTED_FORMAT

            val baseFlags = AUDCLNT_STREAMFLAGS_EVENTCALLBACK.toUInt()
            val autoFlags = baseFlags or AUTOCONVERT_FLAGS
            val autoDuration = framesPerBuffer.toLong() * 10_000_000L / format.sampleRate

            val initialize: (UInt, Long) -> Int = { flags, duration ->
                vtbl.Initialize!!(
                    audioClient,
                    _AUDCLNT_SHAREMODE.AUDCLNT_SHAREMODE_SHARED,
                    flags,
                    duration,
                    0L,
                    waveFormat,
                    null,
                )
            }

            // The requested format is tried first; only when the mixer cannot
            // take it does the client switch on its own sample-rate converter.
            val hr = if (convertible) {
                initialize(autoFlags, autoDuration)
            } else {
                val direct = initialize(baseFlags, 0L)
                if (direct >= 0) direct else initialize(autoFlags, autoDuration)
            }
            checkHr(hr, "IAudioClient::Initialize", format)

            val size = alloc<UIntVar>()
            checkHr(vtbl.GetBufferSize!!(audioClient, size.ptr), "IAudioClient::GetBufferSize", format)
            deviceBufferFrames = size.value.toInt()

            val handle = CreateEventW(null, 0, 0, null)
                ?: wasapiError("CreateEventW", GetLastError().toInt(), format)
            event = handle
            checkHr(vtbl.SetEventHandle!!(audioClient, handle), "IAudioClient::SetEventHandle", format)

            val serviceIid = if (capture) IID_IAudioCaptureClient else IID_IAudioRenderClient
            val serviceOut = alloc<COpaquePointerVar>()
            checkHr(
                vtbl.GetService!!(audioClient, serviceIid.ptr, serviceOut.ptr),
                "IAudioClient::GetService",
                format,
            )
            service = serviceOut.value ?: wasapiError("IAudioClient::GetService", -1, format)

            if (capture) {
                val bytes = format.framesToBytes(maxOf(framesPerBuffer, deviceBufferFrames)).toULong()
                val memory = malloc(bytes)
                    ?: throw AudioException("cannot allocate the capture silence buffer")
                memset(memory, 0, bytes)
                silence = memory.reinterpret()
            }
        }
    }

    private fun createPump() {
        val handle = CreateThread(
            null,
            0uL,
            staticCFunction(::wasapiPumpEntry),
            self.asCPointer(),
            0u,
            null,
        ) ?: wasapiError("CreateThread", GetLastError().toInt(), format)
        thread = handle
    }

    /** Waits for device events until [closeDevice] stops the stream. */
    internal fun pump() {
        val initialized = CoInitializeEx(null, COINIT_MULTITHREADED) >= 0
        SetThreadPriority(GetCurrentThread(), THREAD_PRIORITY_TIME_CRITICAL)
        val handle = event
        if (handle != null) {
            while (stop.load() == 0) {
                WaitForSingleObject(handle, INFINITE)
                if (stop.load() != 0) break
                // Claim the ring first, then re-read the pause flag: whichever
                // way this interleaves with stopDevice, the flush sees either
                // no active buffer or a finished one.
                busy.store(1)
                if (paused.load() == 0) {
                    if (capture) captureOnce() else renderOnce()
                    signal.signal()
                }
                busy.store(0)
            }
        }
        if (initialized) CoUninitialize()
    }

    /** Drains every captured packet into the ring, honouring silent packets. */
    private fun captureOnce() {
        val captureClient = service?.reinterpret<IAudioCaptureClient>() ?: return
        val vtbl = captureClient.pointed.lpVtbl?.pointed ?: return
        memScoped {
            val packet = alloc<UIntVar>()
            val data = alloc<CPointerVar<UByteVar>>()
            val frames = alloc<UIntVar>()
            val flags = alloc<UIntVar>()
            val devicePosition = alloc<ULongVar>()
            val qpcPosition = alloc<ULongVar>()

            if (vtbl.GetNextPacketSize!!(captureClient, packet.ptr) < 0) return
            while (packet.value > 0u) {
                val buffer = vtbl.GetBuffer!!(
                    captureClient,
                    data.ptr,
                    frames.ptr,
                    flags.ptr,
                    devicePosition.ptr,
                    qpcPosition.ptr,
                )
                if (buffer < 0) return
                val count = frames.value.toInt()
                if (count > 0) {
                    if (flags.value and AUDCLNT_BUFFERFLAGS_SILENT != 0u) {
                        silence?.let { ring.write(it, count) }
                    } else {
                        val source = data.value
                        if (source != null) ring.write(source.reinterpret<ByteVar>(), count)
                    }
                }
                if (vtbl.ReleaseBuffer!!(captureClient, frames.value) < 0) return
                if (vtbl.GetNextPacketSize!!(captureClient, packet.ptr) < 0) return
            }
        }
    }

    /** Fills the free space of the device buffer from the ring, padding with silence. */
    private fun renderOnce() {
        val renderClient = service?.reinterpret<IAudioRenderClient>() ?: return
        val vtbl = renderClient.pointed.lpVtbl?.pointed ?: return
        val audioClient = client ?: return
        memScoped {
            val padding = alloc<UIntVar>()
            if (audioClient.pointed.lpVtbl!!.pointed.GetCurrentPadding!!(audioClient, padding.ptr) < 0) return
            val free = deviceBufferFrames - padding.value.toInt()
            if (free <= 0) return

            val data = alloc<CPointerVar<UByteVar>>()
            if (vtbl.GetBuffer!!(renderClient, free.toUInt(), data.ptr) < 0) return
            val pointer = data.value
            if (pointer != null) {
                val target = pointer.reinterpret<ByteVar>()
                val frames = ring.read(target, free)
                if (frames < free) {
                    ring.readSilence(target.byteOffset(format.framesToBytes(frames)), free - frames)
                }
            }
            if (vtbl.ReleaseBuffer!!(renderClient, free.toUInt(), 0u) < 0) return
        }
    }
}

/** WASAPI capture stream. */
internal class WasapiInput(
    format: AudioFormat,
    framesPerBuffer: Int,
    ring: PcmRing,
    signal: RtSignal,
    deviceId: String?,
) : WasapiDevice(format, framesPerBuffer, ring, signal, deviceId, wasapi.eCapture, capture = true)

/** WASAPI playback stream. */
internal class WasapiOutput(
    format: AudioFormat,
    framesPerBuffer: Int,
    ring: PcmRing,
    signal: RtSignal,
    deviceId: String?,
) : WasapiDevice(format, framesPerBuffer, ring, signal, deviceId, wasapi.eRender, capture = false)

/** Thread entry point of the pump; the stream travels through a [StableRef]. */
private fun wasapiPumpEntry(parameter: COpaquePointer?): UInt {
    val device = parameter?.asStableRef<WasapiDevice>()?.get() ?: return 1u
    device.pump()
    return 0u
}

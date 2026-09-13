@file:OptIn(ExperimentalForeignApi::class)

package cn.enaium.audio

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CValue
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.alloc
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.cValue
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.value
import platform.AudioToolbox.AudioQueueAllocateBuffer
import platform.AudioToolbox.AudioQueueBufferRef
import platform.AudioToolbox.AudioQueueBufferRefVar
import platform.AudioToolbox.AudioQueueDispose
import platform.AudioToolbox.AudioQueueEnqueueBuffer
import platform.AudioToolbox.AudioQueueNewInput
import platform.AudioToolbox.AudioQueueNewOutput
import platform.AudioToolbox.AudioQueueRef
import platform.AudioToolbox.AudioQueueRefVar
import platform.AudioToolbox.AudioQueueSetProperty
import platform.AudioToolbox.AudioQueueStart
import platform.AudioToolbox.AudioQueueStop
import platform.AudioToolbox.kAudioQueueProperty_CurrentDevice
import platform.CoreAudioTypes.AudioStreamBasicDescription
import platform.CoreAudioTypes.AudioStreamPacketDescription
import platform.CoreAudioTypes.AudioTimeStamp
import platform.CoreAudioTypes.kAudioFormatFlagIsFloat
import platform.CoreAudioTypes.kAudioFormatFlagIsPacked
import platform.CoreAudioTypes.kAudioFormatFlagIsSignedInteger
import platform.CoreAudioTypes.kAudioFormatLinearPCM
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringCreateWithCString
import platform.CoreFoundation.CFStringRefVar
import platform.CoreFoundation.kCFStringEncodingUTF8

/** Buffers the queue keeps in flight: enough to ride out a scheduling hiccup. */
private const val QUEUED_BUFFERS = 3

/**
 * Library [AudioFormat] as Core Audio describes it.
 *
 * Core Audio's linear PCM is little-endian on every Mac, so the byte order flag
 * stays clear; `IsPacked` is what tells the queue that no padding sits between
 * samples.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun AudioFormat.toAsbd(): CValue<AudioStreamBasicDescription> = cValue {
    mSampleRate = this@toAsbd.sampleRate.toDouble()
    mFormatID = kAudioFormatLinearPCM
    mFormatFlags = formatFlags(this@toAsbd)
    mBytesPerPacket = frameSizeBytes.convert()
    mFramesPerPacket = 1u
    mBytesPerFrame = frameSizeBytes.convert()
    mChannelsPerFrame = channelCount.convert()
    mBitsPerChannel = sampleFormat.bitsPerSample.convert()
    mReserved = 0u
}

private fun formatFlags(format: AudioFormat): UInt {
    var flags = kAudioFormatFlagIsPacked
    if (format.sampleFormat.isFloat) {
        flags = flags or kAudioFormatFlagIsFloat
    } else if (format.sampleFormat.isSigned) {
        flags = flags or kAudioFormatFlagIsSignedInteger
    }
    return flags
}

/**
 * `AudioQueue` capture device.
 *
 * The queue runs on its own thread and hands every recorded buffer to
 * [onInputBuffer], which moves the frames into the ring and immediately
 * re-enqueues the buffer. The queue thread therefore never waits for the
 * application.
 */
@OptIn(ExperimentalForeignApi::class)
internal class AudioQueueInput(
    private val format: AudioFormat,
    private val framesPerBuffer: Int,
    private val ring: PcmRing,
    private val signal: RtSignal,
    deviceUid: String?,
) : DeviceControl {

    private val self = StableRef.create(this)
    private val queue: AudioQueueRef

    init {
        memScoped {
            val out = alloc<AudioQueueRefVar>()
            val status = AudioQueueNewInput(
                format.toAsbd(),
                staticCFunction(::audioQueueInputCallback),
                self.asCPointer(),
                null,
                null,
                0u,
                out.ptr,
            )
            val created = out.value
            if (status != 0 || created == null) {
                self.dispose()
                throw AudioException("AudioQueueNewInput failed with status $status for $format")
            }
            queue = created
        }

        allocateBuffers()

        if (deviceUid != null) {
            try {
                selectDevice(deviceUid)
            } catch (e: Throwable) {
                closeDevice()
                throw e
            }
        }
    }

    override fun startDevice() {
        val status = AudioQueueStart(queue, null)
        if (status != 0) throw AudioException("AudioQueueStart failed with status $status")
    }

    override fun stopDevice() {
        // Immediate stop: once this returns the callback thread no longer
        // touches the ring, which is what lets the caller flush it safely.
        AudioQueueStop(queue, true)
    }

    override fun closeDevice() {
        AudioQueueDispose(queue, true)
        self.dispose()
    }

    override fun flushDevice() {
        ring.clear()
    }

    /** Moves a recorded buffer into the ring and hands it back to the queue. */
    internal fun onInputBuffer(queueRef: AudioQueueRef?, bufferRef: AudioQueueBufferRef?) {
        if (queueRef == null || bufferRef == null) return
        val buffer = bufferRef.pointed
        val frames = (buffer.mAudioDataByteSize / format.frameSizeBytes.toUInt()).toInt()
        val data = buffer.mAudioData
        if (frames > 0 && data != null) {
            ring.write(data.reinterpret(), frames)
        }
        AudioQueueEnqueueBuffer(queueRef, bufferRef, 0u, null)
        signal.signal()
    }

    private fun allocateBuffers() {
        memScoped {
            val bytes = format.framesToBytes(framesPerBuffer).convert<UInt>()
            repeat(QUEUED_BUFFERS) {
                val buffer = alloc<AudioQueueBufferRefVar>()
                val status = AudioQueueAllocateBuffer(queue, bytes, buffer.ptr)
                if (status != 0) {
                    closeDevice()
                    throw AudioException("AudioQueueAllocateBuffer failed with status $status")
                }
                AudioQueueEnqueueBuffer(queue, buffer.value, 0u, null)
            }
        }
    }

    private fun selectDevice(uid: String) {
        setQueueDevice(queue, uid)
    }

    companion object {
        internal fun of(userData: COpaquePointer?): AudioQueueInput? =
            userData?.asStableRef<AudioQueueInput>()?.get()
    }
}

/**
 * `AudioQueue` playback device.
 *
 * The callback pulls frames out of the ring and completes the queue's buffer;
 * a buffer the application did not fill in time is finished with silence.
 */
@OptIn(ExperimentalForeignApi::class)
internal class AudioQueueOutput(
    private val format: AudioFormat,
    private val framesPerBuffer: Int,
    private val ring: PcmRing,
    private val signal: RtSignal,
    deviceUid: String?,
) : DeviceControl {

    private val self = StableRef.create(this)
    private val queue: AudioQueueRef

    init {
        memScoped {
            val out = alloc<AudioQueueRefVar>()
            val status = AudioQueueNewOutput(
                format.toAsbd(),
                staticCFunction(::audioQueueOutputCallback),
                self.asCPointer(),
                null,
                null,
                0u,
                out.ptr,
            )
            val created = out.value
            if (status != 0 || created == null) {
                self.dispose()
                throw AudioException("AudioQueueNewOutput failed with status $status for $format")
            }
            queue = created
        }

        allocateBuffers()

        if (deviceUid != null) {
            try {
                setQueueDevice(queue, deviceUid)
            } catch (e: Throwable) {
                closeDevice()
                throw e
            }
        }
    }

    override fun startDevice() {
        val status = AudioQueueStart(queue, null)
        if (status != 0) throw AudioException("AudioQueueStart failed with status $status")
    }

    override fun stopDevice() {
        AudioQueueStop(queue, true)
    }

    override fun closeDevice() {
        AudioQueueDispose(queue, true)
        self.dispose()
    }

    override fun flushDevice() {
        ring.clear()
    }

    /** Fills a playback buffer from the ring and hands it back to the queue. */
    internal fun onOutputBuffer(queueRef: AudioQueueRef?, bufferRef: AudioQueueBufferRef?) {
        if (queueRef == null || bufferRef == null) return
        val buffer = bufferRef.pointed
        val capacityFrames = (buffer.mAudioDataBytesCapacity / format.frameSizeBytes.toUInt()).toInt()
        val data = buffer.mAudioData
        if (data != null && capacityFrames > 0) {
            val pointer = data.reinterpret<ByteVar>()
            val frames = ring.read(pointer, capacityFrames)
            if (frames < capacityFrames) {
                ring.readSilence(pointer.byteOffset(format.framesToBytes(frames)), capacityFrames - frames)
            }
            buffer.mAudioDataByteSize = format.framesToBytes(capacityFrames).toUInt()
        }
        AudioQueueEnqueueBuffer(queueRef, bufferRef, 0u, null)
        signal.signal()
    }

    private fun allocateBuffers() {
        memScoped {
            val bytes = format.framesToBytes(framesPerBuffer).convert<UInt>()
            repeat(QUEUED_BUFFERS) {
                val buffer = alloc<AudioQueueBufferRefVar>()
                val status = AudioQueueAllocateBuffer(queue, bytes, buffer.ptr)
                if (status != 0) {
                    closeDevice()
                    throw AudioException("AudioQueueAllocateBuffer failed with status $status")
                }
                AudioQueueEnqueueBuffer(queue, buffer.value, 0u, null)
            }
        }
    }

    companion object {
        internal fun of(userData: COpaquePointer?): AudioQueueOutput? =
            userData?.asStableRef<AudioQueueOutput>()?.get()
    }
}

/** Points a queue at a specific device, addressed by its UID. */
@OptIn(ExperimentalForeignApi::class)
private fun setQueueDevice(queue: AudioQueueRef?, uid: String) {
    memScoped {
        val reference = CFStringCreateWithCString(null, uid, kCFStringEncodingUTF8)
            ?: throw AudioException("cannot address device $uid")
        try {
            val holder = alloc<CFStringRefVar>().apply { value = reference }
            val status = AudioQueueSetProperty(
                queue,
                kAudioQueueProperty_CurrentDevice,
                holder.ptr,
                sizeOf<CFStringRefVar>().convert(),
            )
            if (status != 0) throw AudioException("Core Audio does not know the device $uid")
        } finally {
            CFRelease(reference)
        }
    }
}

private fun audioQueueInputCallback(
    userData: COpaquePointer?,
    queue: AudioQueueRef?,
    buffer: AudioQueueBufferRef?,
    startTime: CPointer<AudioTimeStamp>?,
    packetCount: UInt,
    packetDescriptions: CPointer<AudioStreamPacketDescription>?,
) {
    AudioQueueInput.of(userData)?.onInputBuffer(queue, buffer)
}

private fun audioQueueOutputCallback(
    userData: COpaquePointer?,
    queue: AudioQueueRef?,
    buffer: AudioQueueBufferRef?,
) {
    AudioQueueOutput.of(userData)?.onOutputBuffer(queue, buffer)
}

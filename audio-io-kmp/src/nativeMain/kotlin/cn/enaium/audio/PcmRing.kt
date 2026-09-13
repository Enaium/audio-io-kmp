package cn.enaium.audio

import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.convert
import kotlinx.cinterop.reinterpret
import platform.posix.free
import platform.posix.malloc
import platform.posix.memcpy
import platform.posix.memset

/**
 * Frame ring buffer on native memory, filled by a device callback and drained
 * by the calling thread (or the other way round for playback).
 *
 * Unlike [AudioRingBuffer] this one works on raw pointers and lives outside the
 * Kotlin heap: the real-time callback only touches `memcpy` and two atomic
 * indices, so it neither allocates nor cooperates with the garbage collector.
 * Same single producer / single consumer rule as [AudioRingBuffer].
 */
@OptIn(ExperimentalForeignApi::class, ExperimentalAtomicApi::class)
internal class PcmRing(
    val format: AudioFormat,
    val capacityFrames: Int,
) {
    private val frameSize = format.frameSizeBytes
    private val storageBytes = format.framesToBytes(capacityFrames)

    /**
     * Byte that repeats through one silent frame.
     *
     * Every sample format this ring carries is silent as an all-zero pattern,
     * except offset-binary unsigned 8 bit, where silence is `0x80`.
     */
    private val silenceByte: Byte =
        if (format.sampleFormat == SampleFormat.PCM_U8) 0x80.toByte() else 0.toByte()
    private val storage: CPointer<ByteVar> =
        requireNotNull(malloc(storageBytes.convert())) { "cannot allocate $storageBytes bytes for the ring" }.reinterpret()
    private val writeIndex = AtomicLong(0)
    private val readIndex = AtomicLong(0)

    /** Frames that can be read. */
    val availableToRead: Int get() = (writeIndex.load() - readIndex.load()).toInt()

    /** Frames that can be written. */
    val availableToWrite: Int get() = capacityFrames - availableToRead

    /** Copies at most [frames] frames from [source] and returns the frames stored. */
    fun write(source: CPointer<ByteVar>, frames: Int): Int {
        val n = minOf(frames, availableToWrite)
        var done = 0
        while (done < n) {
            val boundary = writeIndex.load()
            val offset = (boundary % capacityFrames).toInt()
            val chunk = minOf(n - done, capacityFrames - offset)
            memcpy(
                storage.byteOffset(offset * frameSize),
                source.byteOffset(done * frameSize),
                (chunk * frameSize).convert(),
            )
            writeIndex.store(boundary + chunk)
            done += chunk
        }
        return n
    }

    /** Copies at most [frames] frames into [destination] and returns the frames read. */
    fun read(destination: CPointer<ByteVar>, frames: Int): Int {
        val n = minOf(frames, availableToRead)
        var done = 0
        while (done < n) {
            val boundary = readIndex.load()
            val offset = (boundary % capacityFrames).toInt()
            val chunk = minOf(n - done, capacityFrames - offset)
            memcpy(
                destination.byteOffset(done * frameSize),
                storage.byteOffset(offset * frameSize),
                (chunk * frameSize).convert(),
            )
            readIndex.store(boundary + chunk)
            done += chunk
        }
        return n
    }

    /** Fills the next [frames] frames of [destination] with silence. */
    fun readSilence(destination: CPointer<ByteVar>, frames: Int) {
        val bytes = format.framesToBytes(frames)
        if (bytes <= 0) return
        memset(destination, silenceByte.toInt(), bytes.convert())
    }

    /** Drops every buffered frame. Both sides must be quiesced. */
    fun clear() {
        readIndex.store(0)
        writeIndex.store(0)
        memset(storage, silenceByte.toInt(), storageBytes.convert())
    }

    /** Releases the native storage. */
    fun close() {
        free(storage)
    }
}

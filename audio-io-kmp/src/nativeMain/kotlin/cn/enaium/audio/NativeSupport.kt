package cn.enaium.audio

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.plus
import kotlinx.cinterop.usePinned

/** Buffering used when the caller does not ask for a specific amount. */
internal const val DEFAULT_BUFFER_MILLIS: Int = 20

/** How long a blocked `read`/`write` sleeps before it rechecks the stream state. */
internal const val RT_WAIT_MILLIS: Int = 10

/** Frames that fill [DEFAULT_BUFFER_MILLIS] of this format, with a floor that keeps tiny rates usable. */
internal fun AudioFormat.defaultBufferFrames(): Int = maxOf(millisToFrames(DEFAULT_BUFFER_MILLIS.toDouble()), 64)

/**
 * Moves a byte pointer by [bytes].
 *
 * The stock `CPointer.plus` operator of `kotlinx.cinterop` is declared on a
 * nullable receiver and returns a nullable pointer; this wrapper keeps the
 * offset arithmetic non-null so call sites do not need `!!`.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun CPointer<ByteVar>.byteOffset(bytes: Int): CPointer<ByteVar> =
    requireNotNull(this + bytes.toLong()) { "cannot offset a null pointer" }

/**
 * Runs [block] with this string as a NUL terminated UTF-8 C string.
 *
 * Needed where a C function is reached through a function pointer: the compiler
 * cannot insert the automatic Kotlin-string conversion for those calls, so the
 * bytes are materialized here.
 */
@OptIn(ExperimentalForeignApi::class)
internal inline fun <R> String.withCString(block: (CPointer<ByteVar>) -> R): R {
    val bytes = encodeToByteArray()
    val terminated = ByteArray(bytes.size + 1)
    bytes.copyInto(terminated)
    return terminated.usePinned { block(it.addressOf(0)) }
}

/**
 * Runs [block] with the backing store of this buffer pinned, so a blocking
 * device call can pass its address straight to the driver without copying.
 */
@OptIn(ExperimentalForeignApi::class)
internal inline fun <R> AudioBuffer.usePointer(block: (CPointer<ByteVar>) -> R): R =
    data.usePinned { block(it.addressOf(0)) }

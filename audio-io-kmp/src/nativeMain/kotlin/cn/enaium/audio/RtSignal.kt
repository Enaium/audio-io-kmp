package cn.enaium.audio

/**
 * Wake-up primitive used to get a blocked thread out of a device wait.
 *
 * A stream has exactly one waiting thread (its reader or writer), so `signal`
 * wakes that one and a token posted while nobody waits is remembered: the next
 * [await] returns immediately instead of losing the wake-up. Signalling never
 * blocks and never allocates, which is what makes it safe to call from a
 * real-time audio callback. Each target family creates it through its own
 * `createRtSignal()`.
 */
internal interface RtSignal {

    /** Wakes the waiting thread, or leaves a token for the next [await]. */
    fun signal()

    /** Blocks until a signal arrives. */
    fun await()

    /** Releases the native handle. */
    fun close()
}

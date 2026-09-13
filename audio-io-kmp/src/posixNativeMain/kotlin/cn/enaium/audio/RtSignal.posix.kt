@file:OptIn(ExperimentalForeignApi::class)

package cn.enaium.audio

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.convert
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import platform.posix.free
import platform.posix.malloc
import platform.posix.pthread_cond_destroy
import platform.posix.pthread_cond_init
import platform.posix.pthread_cond_signal
import platform.posix.pthread_cond_t
import platform.posix.pthread_cond_wait
import platform.posix.pthread_mutex_destroy
import platform.posix.pthread_mutex_init
import platform.posix.pthread_mutex_lock
import platform.posix.pthread_mutex_t
import platform.posix.pthread_mutex_unlock

/**
 * Signal built on a POSIX mutex and condition variable, used by the macOS and
 * Linux backends.
 *
 * The condition variable parks the waiting thread; the `pending` flag is what
 * makes a wake-up that arrives before the waiter parks survive, so no wake-up
 * is lost. `signal` allocates nothing and holds the mutex for the few
 * instructions that set the flag, which is what makes it usable from a
 * real-time audio callback.
 */
internal class PosixRtSignal : RtSignal {

    // pthread types are opaque structs: the memory has to outlive the calls
    // below, so both live in plain C memory instead of on the Kotlin heap.
    private val mutex: CPointer<pthread_mutex_t> = requireNotNull(malloc(sizeOf<pthread_mutex_t>().convert()))
        .reinterpret()
    private val condition: CPointer<pthread_cond_t> = requireNotNull(malloc(sizeOf<pthread_cond_t>().convert()))
        .reinterpret()

    /** `1` when a wake-up is waiting to be consumed. Guarded by [mutex]. */
    private var pending = 0

    init {
        if (pthread_mutex_init(mutex, null) != 0) {
            free(mutex)
            free(condition)
            throw AudioException("cannot create the signal mutex")
        }
        if (pthread_cond_init(condition, null) != 0) {
            pthread_mutex_destroy(mutex)
            free(mutex)
            free(condition)
            throw AudioException("cannot create the signal condition variable")
        }
    }

    override fun signal() {
        pthread_mutex_lock(mutex)
        pending = 1
        pthread_cond_signal(condition)
        pthread_mutex_unlock(mutex)
    }

    override fun await() {
        pthread_mutex_lock(mutex)
        while (pending == 0) {
            pthread_cond_wait(condition, mutex)
        }
        pending = 0
        pthread_mutex_unlock(mutex)
    }

    override fun close() {
        pthread_cond_destroy(condition)
        pthread_mutex_destroy(mutex)
        free(mutex)
        free(condition)
    }
}

/** Creates the POSIX signal used by callback driven streams. */
internal fun createRtSignal(): RtSignal = PosixRtSignal()

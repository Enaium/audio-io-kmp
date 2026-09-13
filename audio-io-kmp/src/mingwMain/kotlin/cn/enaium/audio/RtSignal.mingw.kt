@file:OptIn(ExperimentalForeignApi::class)

package cn.enaium.audio

import kotlinx.cinterop.CPointed
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import platform.windows.CloseHandle
import platform.windows.CreateEventW
import platform.windows.INFINITE
import platform.windows.SetEvent
import platform.windows.WaitForSingleObject

/**
 * Signal built on a Win32 auto-reset event, used by the WASAPI backend.
 *
 * An auto-reset event is exactly the wake-up token the stream contract needs:
 * the first waiter is released and the event returns to the non-signalled
 * state, so a wake-up that arrives before the waiter parks is still consumed
 * by the next wait. `SetEvent` neither allocates nor blocks, which is what
 * makes it safe to call from a real-time audio callback.
 */
internal class Win32RtSignal : RtSignal {

    private val handle: CPointer<out CPointed>? = CreateEventW(null, 0, 0, null)
    private var closed = false

    init {
        if (handle == null) throw AudioException("cannot create the signal event")
    }

    override fun signal() {
        SetEvent(handle)
    }

    override fun await() {
        WaitForSingleObject(handle, INFINITE)
    }

    override fun close() {
        if (closed) return
        closed = true
        CloseHandle(handle)
    }
}

/** Creates the Win32 signal used by callback driven streams. */
internal fun createRtSignal(): RtSignal = Win32RtSignal()

@file:OptIn(ExperimentalForeignApi::class)

package cn.enaium.audio

import alsa.snd_pcm_t
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.invoke
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned

/**
 * Blocking capture on an ALSA PCM handle.
 *
 * `snd_pcm_readi` blocks until the whole requested amount of frames arrived, so
 * [read] maps straight onto it. A blocking read that a `stop()` from another
 * thread interrupts returns `-EBADFD`; that is reported as `-1` so a capture loop
 * ends deterministically. Overruns and device suspends are handled by
 * `snd_pcm_recover`, which is what keeps a long running capture alive across a
 * machine suspend.
 */
internal class AlsaAudioInput(
    override val format: AudioFormat,
    override val device: AudioDevice?,
    override val bufferFrames: Int,
    private val pcm: CPointer<snd_pcm_t>,
) : AudioInput {

    override var state: AudioState = AudioState.OPEN
        private set

    override fun start() {
        check(state != AudioState.CLOSED) { "stream is closed" }
        if (state == AudioState.STARTED) return
        val api = Alsa.api()
        // From SETUP (a fresh handle or one that was dropped) back to PREPARED;
        // the first read starts the stream.
        Alsa.check(api.pcm_prepare.required("snd_pcm_prepare")(pcm), "snd_pcm_prepare")
        state = AudioState.STARTED
    }

    override fun stop() {
        if (state != AudioState.STARTED) return
        state = AudioState.STOPPED
        // Drop unblocks a reader that is parked inside snd_pcm_readi and throws
        // away the frames the driver already captured.
        Alsa.api().pcm_drop.required("snd_pcm_drop")(pcm)
    }

    override fun read(buffer: AudioBuffer): Int {
        require(buffer.format == format) { "format mismatch: $format != ${buffer.format}" }
        if (state != AudioState.STARTED) return -1
        return buffer.data.usePinned { pinned -> readFrames(pinned.addressOf(0), buffer) }
    }

    override fun readNonBlocking(buffer: AudioBuffer): Int {
        require(buffer.format == format) { "format mismatch: $format != ${buffer.format}" }
        val ready = available()
        if (ready <= 0) {
            buffer.frameCount = 0
            return 0
        }
        return buffer.data.usePinned { pinned ->
            val frames = Alsa.api().pcm_readi.required("snd_pcm_readi")(
                pcm,
                pinned.addressOf(0),
                minOf(ready, buffer.capacityFrames).toULong(),
            )
            buffer.frameCount = if (frames > 0) frames.toInt() else 0
            buffer.frameCount
        }
    }

    override fun available(): Int {
        val frames = Alsa.api().pcm_avail_update.required("snd_pcm_avail_update")(pcm)
        if (frames >= 0) return frames.toInt()
        // An overrun leaves the handle in SETUP; prepare it again so the next
        // read can continue.
        if (frames.toInt() == ALSA_XRUN || frames.toInt() == ALSA_SUSPENDED) {
            Alsa.api().pcm_recover.required("snd_pcm_recover")(pcm, frames.toInt(), 1)
            return 0
        }
        return 0
    }

    override fun close() {
        if (state == AudioState.CLOSED) return
        state = AudioState.CLOSED
        Alsa.api().pcm_drop.required("snd_pcm_drop")(pcm)
        Alsa.api().pcm_close.required("snd_pcm_close")(pcm)
    }

    private fun readFrames(pointer: CPointer<ByteVar>, buffer: AudioBuffer): Int {
        val api = Alsa.api()
        val readi = api.pcm_readi.required("snd_pcm_readi")
        val recover = api.pcm_recover.required("snd_pcm_recover")
        while (true) {
            val frames = readi(pcm, pointer, buffer.capacityFrames.toULong())
            if (frames >= 0) {
                buffer.frameCount = frames.toInt()
                return buffer.frameCount
            }
            if (state != AudioState.STARTED) {
                buffer.frameCount = 0
                return -1
            }
            val rc = frames.toInt()
            if (rc == ALSA_XRUN || rc == ALSA_SUSPENDED) {
                Alsa.check(recover(pcm, rc, 0), "snd_pcm_recover")
                continue
            }
            throw AudioException("snd_pcm_readi failed: ${Alsa.error(rc)}")
        }
    }
}

/**
 * Blocking playback on an ALSA PCM handle.
 *
 * `snd_pcm_writei` accepts as many frames as the device buffer has room for and
 * blocks otherwise, so [write] loops until the buffer reached the driver. A
 * suspend or an underrun is recovered in place, which keeps a long playback
 * session running.
 */
internal class AlsaAudioOutput(
    override val format: AudioFormat,
    override val device: AudioDevice?,
    override val bufferFrames: Int,
    private val pcm: CPointer<snd_pcm_t>,
) : AudioOutput {

    override var state: AudioState = AudioState.OPEN
        private set

    override fun start() {
        check(state != AudioState.CLOSED) { "stream is closed" }
        if (state == AudioState.STARTED) return
        Alsa.check(Alsa.api().pcm_prepare.required("snd_pcm_prepare")(pcm), "snd_pcm_prepare")
        state = AudioState.STARTED
    }

    override fun stop() {
        if (state != AudioState.STARTED) return
        state = AudioState.STOPPED
        val api = Alsa.api()
        // Drain plays what is still buffered, drop would cut it off.
        api.pcm_drain.required("snd_pcm_drain")(pcm)
        api.pcm_drop.required("snd_pcm_drop")(pcm)
    }

    override fun write(buffer: AudioBuffer): Int {
        require(buffer.format == format) { "format mismatch: $format != ${buffer.format}" }
        if (state != AudioState.STARTED) return -1
        if (buffer.frameCount == 0) return 0
        return buffer.data.usePinned { pinned -> writeFrames(pinned.addressOf(0), buffer.frameCount) }
    }

    override fun writeNonBlocking(buffer: AudioBuffer): Int {
        require(buffer.format == format) { "format mismatch: $format != ${buffer.format}" }
        val room = available()
        if (room <= 0) return 0
        val frames = minOf(room, buffer.frameCount)
        return buffer.data.usePinned { pinned ->
            val written = Alsa.api().pcm_writei.required("snd_pcm_writei")(
                pcm,
                pinned.addressOf(0),
                frames.toULong(),
            )
            if (written > 0) written.toInt() else 0
        }
    }

    override fun available(): Int {
        val frames = Alsa.api().pcm_avail_update.required("snd_pcm_avail_update")(pcm)
        if (frames >= 0) return frames.toInt()
        val rc = frames.toInt()
        if (rc == ALSA_XRUN || rc == ALSA_SUSPENDED) {
            Alsa.api().pcm_recover.required("snd_pcm_recover")(pcm, rc, 1)
        }
        return 0
    }

    override fun close() {
        if (state == AudioState.CLOSED) return
        state = AudioState.CLOSED
        val api = Alsa.api()
        api.pcm_drop.required("snd_pcm_drop")(pcm)
        api.pcm_close.required("snd_pcm_close")(pcm)
    }

    private fun writeFrames(pointer: CPointer<ByteVar>, total: Int): Int {
        val api = Alsa.api()
        val writei = api.pcm_writei.required("snd_pcm_writei")
        val recover = api.pcm_recover.required("snd_pcm_recover")
        var written = 0
        while (written < total) {
            val frames = writei(
                pcm,
                pointer.byteOffset(format.framesToBytes(written)),
                (total - written).toULong(),
            )
            if (frames >= 0) {
                written += frames.toInt()
                continue
            }
            if (state != AudioState.STARTED) return -1
            val rc = frames.toInt()
            if (rc == ALSA_XRUN || rc == ALSA_SUSPENDED) {
                Alsa.check(recover(pcm, rc, 0), "snd_pcm_recover")
                continue
            }
            throw AudioException("snd_pcm_writei failed: ${Alsa.error(rc)}")
        }
        return written
    }
}

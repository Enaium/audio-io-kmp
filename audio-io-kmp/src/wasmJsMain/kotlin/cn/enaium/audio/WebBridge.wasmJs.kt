package cn.enaium.audio

import org.khronos.webgl.Float32Array
import org.khronos.webgl.get
import org.khronos.webgl.set

/**
 * Global `audioIo_*` functions installed by `audio-io-kmp-web.js`.
 *
 * The file only declares them and marshals frames through one reusable typed
 * array: the Web Audio graph, the ring buffers and the callbacks live in the
 * JavaScript glue, so both web runtimes share a single implementation.
 */

private external fun audioIo_deviceCount(): Int
private external fun audioIo_deviceKind(index: Int): Int
private external fun audioIo_deviceId(index: Int): String
private external fun audioIo_deviceLabel(index: Int): String
private external fun audioIo_defaultDeviceId(kind: Int): String?
private external fun audioIo_refreshDevices()
private external fun audioIo_createContext(sampleRate: Int): Int
private external fun audioIo_contextSampleRate(id: Int): Int
private external fun audioIo_contextBlock(id: Int): Int
private external fun audioIo_openCapture(id: Int, deviceId: String?, channels: Int, blockFrames: Int)
private external fun audioIo_captureStatus(id: Int): Int
private external fun audioIo_captureAvailable(id: Int): Int
private external fun audioIo_captureRead(id: Int, destination: Float32Array, maxFrames: Int): Int
private external fun audioIo_openPlayback(id: Int, deviceId: String?, channels: Int, blockFrames: Int): Int
private external fun audioIo_playbackFree(id: Int): Int
private external fun audioIo_playbackWrite(id: Int, source: Float32Array, frames: Int): Int
private external fun audioIo_playbackUnderruns(id: Int): Int
private external fun audioIo_start(id: Int)
private external fun audioIo_suspend(id: Int)
private external fun audioIo_close(id: Int)

internal actual object WebBridge {

    actual fun deviceCount(): Int = audioIo_deviceCount()

    actual fun deviceKind(index: Int): Int = audioIo_deviceKind(index)

    actual fun deviceId(index: Int): String = audioIo_deviceId(index)

    actual fun deviceLabel(index: Int): String = audioIo_deviceLabel(index)

    actual fun defaultDeviceId(kind: Int): String? = audioIo_defaultDeviceId(kind)

    actual fun refreshDevices() {
        audioIo_refreshDevices()
    }

    actual fun createContext(sampleRate: Int): Int = audioIo_createContext(sampleRate)

    actual fun contextSampleRate(id: Int): Int = audioIo_contextSampleRate(id)

    actual fun openCapture(id: Int, deviceId: String?, channels: Int, blockFrames: Int) {
        audioIo_openCapture(id, deviceId, channels, blockFrames)
    }

    actual fun captureAvailable(id: Int): Int = audioIo_captureAvailable(id)

    actual fun captureRead(id: Int, destination: FloatArray, maxFrames: Int): Int {
        val scratch = Float32Array(destination.size)
        val frames = audioIo_captureRead(id, scratch, maxFrames)
        for (index in 0 until destination.size) destination[index] = scratch[index]
        return frames
    }

    actual fun openPlayback(id: Int, deviceId: String?, channels: Int, blockFrames: Int): Int =
        audioIo_openPlayback(id, deviceId, channels, blockFrames)

    actual fun playbackFree(id: Int): Int = audioIo_playbackFree(id)

    actual fun playbackWrite(id: Int, source: FloatArray, frames: Int): Int {
        val scratch = Float32Array(source.size)
        for (index in 0 until source.size) scratch[index] = source[index]
        return audioIo_playbackWrite(id, scratch, frames)
    }

    actual fun start(id: Int) {
        audioIo_start(id)
    }

    actual fun suspend(id: Int) {
        audioIo_suspend(id)
    }

    actual fun close(id: Int) {
        audioIo_close(id)
    }
}

# audio-io-kmp

Kotlin Multiplatform audio I/O library. It captures and plays back **linear
PCM** with a precisely configurable sample rate, channel count and sample
format, and reads or writes exactly as many frames, samples or bytes as the
caller asks for.

The public API only knows about PCM streams and devices; each platform is
implemented on top of its own native audio API behind that API.

| Platform             | Kotlin target                                     | Backend                      |
| -------------------- | ------------------------------------------------- | ---------------------------- |
| JVM desktop          | `jvm`                                             | `javax.sound.sampled`        |
| Android (JVM)        | `android`                                         | `AudioRecord` / `AudioTrack` |
| Android Native       | `androidNativeArm64` / `Arm32` / `X64` / `X86`     | AAudio                       |
| Windows              | `mingwX64`                                        | WASAPI                       |
| Linux x86_64         | `linuxX64`                                         | ALSA                         |
| Linux ARM64          | `linuxArm64`                                       | ALSA                         |
| macOS ARM64          | `macosArm64`                                       | Core Audio (Audio Queue)     |
| macOS x86_64         | `macosX64`                                         | Core Audio (Audio Queue)     |

Audio is always interleaved, little-endian PCM. Raw streams only: container
formats (WAV, MP3, ...) and codecs are out of scope.

## Dependency

```kotlin
commonMain.dependencies {
    implementation("cn.enaium.audio:audio-io-kmp:1.0.0")
}
```

## Quick start

```kotlin
import cn.enaium.audio.*

fun main() {
    val system = audioSystem()
    try {
        println(system.name)                       // "Core Audio", "WASAPI", ...
        println(system.inputDevices())             // every capture endpoint

        val format = AudioFormat(48000, 1, SampleFormat.PCM_S16)
        val input = system.openInput(format, bufferFrames = 960)   // 20 ms
        try {
            input.start()
            val buffer = AudioBuffer(input.format, input.bufferFrames)
            while (true) {
                val frames = input.read(buffer)    // blocks until audio arrives
                if (frames < 0) break              // stopped or closed
                process(buffer.shorts())           // exactly `buffer.sampleCount` samples
            }
        } finally {
            input.close()
        }
    } finally {
        system.close()
    }
}
```

Playback works the same way through `system.openOutput(...)` and
`output.write(buffer)`.

### Reading an exact amount of data

`AudioBuffer` never hides how much data is there. Every accessor takes a count
and clamps it to the valid region, so a partially filled capture buffer yields
fewer samples instead of zeros or an exception:

```kotlin
val frames = input.read(buffer)

val firstBytes = buffer.bytes(64)      // exactly 64 bytes, or fewer at the end
val samples = buffer.shorts(4096)      // exactly 4096 samples, interleaved
val left = buffer.floats(2048)         // converted to -1.0 .. 1.0

buffer.bytes()                          // the whole valid region
buffer.toShorts(destination)            // conversion without allocating
```

## API overview

| Type             | Purpose                                                                 |
| ---------------- | ----------------------------------------------------------------------- |
| `AudioFormat`    | sample rate, channel count, `SampleFormat`; frame/byte/duration math     |
| `SampleFormat`   | `PCM_U8`, `PCM_S16`, `PCM_S24`, `PCM_S32`, `PCM_F32`, `PCM_F64`          |
| `AudioBuffer`    | PCM chunk with its valid frame count and exact-count conversions         |
| `AudioDevice`    | one endpoint: backend id, label, direction, default flag                 |
| `AudioSystem`    | device enumeration and stream creation for one backend                   |
| `AudioInput`     | capture stream: blocking `read`, `readNonBlocking`, `available`          |
| `AudioOutput`    | playback stream: blocking `write`, `writeNonBlocking`, `available`       |
| `AudioRingBuffer`| single-producer/single-consumer ring for capture -> processing pipelines |
| `AudioException` | every failure that came back from the platform audio API                 |

`audioSystem()` returns the backend of the current platform. Several systems can
coexist, and the caller owns and closes each one.

### Lifecycle

```
open -> OPEN, start -> STARTED, stop -> STOPPED, close -> CLOSED
```

`read` and `write` block. `stop()` and `close()` from another thread release the
blocked call, which then returns `-1`, so a capture loop ends without needing an
extra flag. `readNonBlocking`/`writeNonBlocking` never block and report `0` when
there is no room or no data.

### Ring buffer

`AudioRingBuffer` is the piece to put between the capture thread and a
processing or playback thread:

```kotlin
val ring = AudioRingBuffer(format, capacityFrames = 48000)   // one second
// capture thread
ring.write(buffer)
// processing thread
val frames = ring.read(destination, 960)
```

It is not thread safe by itself: exactly one thread may write and one may read,
which is what makes it allocation free.

## Platform notes

### JVM (`javax.sound.sampled`)

Every `Mixer` the JDK reports becomes a device. Capture uses `TargetDataLine`,
playback `SourceDataLine`, both in blocking mode. JavaSound does not convert
between formats: a mixer that does not advertise the requested format fails
with an `AudioException` naming the mixer and the format.

### Android (`AudioRecord` / `AudioTrack`)

`AudioRecord`/`AudioTrack` take the requested format when the device supports
it (`PCM_U8`, `PCM_S16`, `PCM_F32`, and `PCM_S32` from API 31 on), otherwise
the call fails with an `AudioException` naming the format. The application has
to hold `RECORD_AUDIO` itself; a missing permission surfaces as an
`AudioException` that says so. Device enumeration needs an application
`Context`:

```kotlin
val system = audioSystem(context)   // full AudioManager enumeration
val system = audioSystem()          // default input/output only
```

### Android Native (AAudio)

AAudio is available from Android 8.0 (API 26). The backend loads `libaaudio.so`
at runtime, so a binary that links this library still starts on older devices
and reports the missing API instead. AAudio has no device enumeration: one
default endpoint per direction is reported, and a numeric platform device id can
be passed through `AudioDevice.id` to pin a stream to a specific device.

Every shared object that links this library is built for 16 KB memory pages
(`-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384`), which Android 15
and newer require on arm64 devices.

### Windows (WASAPI)

Streams run in shared mode. The requested format is tried first; when the audio
engine cannot take it, the client is opened again with
`AUDCLNT_STREAMFLAGS_AUTOCONVERTPCM` so the engine performs the sample rate and
format conversion. Devices come from `IMMDeviceEnumerator` and the
`AudioDevice.id` is the endpoint id string.

### Linux (ALSA)

`libasound.so.2` is loaded at runtime, so nothing has to be installed to *link*
against the library; the runtime package that every Linux installation has is
enough. Devices come from ALSA's name hints, so `default`, `sysdefault` and
`hw:CARD,DEV` all work as `AudioDevice.id`. The format is applied exactly as
requested: a device that cannot deliver the sample rate, channel count or sample
format fails instead of being silently converted. Overruns and suspends are
recovered in place, which keeps a long capture running across a machine sleep.

### macOS (Core Audio)

Streams use Audio Queue Services, which runs its own thread and converts between
the requested format and the hardware format, so any sample rate and sample
format this library can describe works. `AudioDevice.id` is the Core Audio
device UID, which is what pins a queue to a specific device.

## Module layout

```
audio-io-kmp/                       the library
  src/commonMain                    public API, PCM conversions, ring buffer
  src/jvmMain                       javax.sound.sampled backend
  src/androidMain                   AudioRecord / AudioTrack backend
  src/nativeMain                    shared native code: frame ring, signals, streams
  src/posixNativeMain               mutex/condition signal for macOS and Linux
  src/macosMain                     Core Audio backend
  src/linuxMain                     ALSA backend
  src/mingwMain                     WASAPI backend
  src/androidNativeMain             AAudio backend
  src/nativeInterop/cinterop        cinterop definitions (ALSA, WASAPI, AAudio)
examples/audio-visualizer/          Dear ImGui + ImPlot spectrum visualizer
```

The native targets compile `src/nativeMain` plus the directory of their OS
family, and each family provides the platform specific pieces such as the
backend itself and its wake-up signal.

## Example

`examples/audio-visualizer` captures from the default input and draws a
waveform, a live spectrum and a waterfall spectrogram with Dear ImGui and
ImPlot, with device/format selection and level meters:

```shell
./gradlew :examples:audio-visualizer:jvmRun            # JVM desktop
./gradlew :examples:audio-visualizer:runDebugExecutableMacosArm64

./gradlew :examples:audio-visualizer:android:assembleRelease   # Android APK
adb install -r examples/audio-visualizer/android/build/outputs/apk/release/android-release.apk
```

Use the release APK on a device: a debug build links an unoptimized
Kotlin/Native binary (46 MB against 12 MB for arm64), which renders a frame in
the hundreds of milliseconds. The release build type is signed with the debug
key, so it installs without a keystore.

The Android application module hosts the SDL activity and packages the
`libmain.so` that the example module links for every ABI (arm64-v8a,
armeabi-v7a, x86_64, x86). The activity is locked to landscape, runs immersive,
and asks for `RECORD_AUDIO` before the native side opens the AAudio capture
stream; it also forwards the display density so the interface is scaled for the
screen instead of using desktop sized text.

The interface fills the whole window: the main ImGui window is re-sized to the
ImGui viewport every frame and drawn without decoration, so the layout follows
the SDL window on every resize.

The draw data is rendered by the example itself (`DrawDataRenderer`) instead of
by the bundled SDL backend: ImGui merges items into commands whose vertices
follow the previous command, and a backend that copies the range from the
command's vertex offset needs that offset to be reported correctly. When it is
not (it stays `0`), every command copies the whole draw list up to its own
vertices - about seven times the geometry of a frame, and 190 ms per frame on a
phone. The renderer here only trusts the indices and copies exactly the vertices
a command uses.

The waterfall is drawn as a 128 x 64 texture rather than as an ImPlot heatmap.
A heatmap cell costs four vertices in the render backend, which for a
spectrogram of a reasonable size dominates the frame time; the same data as a
texture is two triangles, and the values are turned into colors through a
lookup table sampled from the selected ImPlot colormap. That is what keeps the
example at display refresh rate.

Pass `--frames N` (JVM) or set `AUDIO_VISUALIZER_FRAMES=N` (native) to exit
after a fixed number of frames, which is what the CI runs use; the run prints
the frame rate it reached. Without a capture device the window still opens and
shows the error instead of failing.

## Building

```shell
./gradlew :audio-io-kmp:jvmTest                # core + JavaSound backend tests
./gradlew :audio-io-kmp:macosArm64Test         # the same tests on the native target
./gradlew :audio-io-kmp:compileKotlinLinuxX64  # cross compile a target klib
```

Every native target except the Apple ones is cross-compiled from any host: the
WASAPI bindings use the MinGW headers that Kotlin/Native ships, and AAudio and
ALSA are resolved with `dlopen`, so no Android NDK or ALSA development package
is needed to produce the klibs.

The Android target needs an Android SDK (`local.properties` with `sdk.dir`, or
`ANDROID_HOME`).

## Publishing

`.github/workflows/publish.yml` publishes to Maven Central, and
`.github/workflows/test.yml` builds and tests every platform. Both are started
manually from the Actions tab.

## License

MIT

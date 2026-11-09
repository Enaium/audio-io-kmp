/*
 * Web Audio glue for the audio-io-kmp browser backend.
 *
 * Neither Kotlin/Wasm nor Kotlin/JS can attach a handler to a Web Audio node
 * that calls back into Kotlin, so this file owns the audio graph: one
 * AudioContext per stream, a ScriptProcessorNode whose callback moves frames
 * between the browser and a ring buffer, and the accessors the Kotlin side
 * polls every frame.
 *
 * Load it before the Kotlin module (see the README). It installs the flat
 * `audioIo_*` globals the Kotlin side declares and does nothing when they
 * already exist, so including it twice is harmless.
 *
 * Frames crossing the bridge are interleaved float samples; the Kotlin side
 * converts them to the stream's PCM format. Capture and playback run on the
 * page's thread (ScriptProcessorNode), which is also where the Kotlin code
 * runs, so no locking is needed anywhere.
 */
(function () {
    if (globalThis.audioIo_deviceCount) return;


    var contexts = new Map();
    var nextContext = 1;
    var devices = [];
    var defaults = [null, null];

    function context(id) {
        return contexts.get(id) || null;
    }

    function consoleError(message, error) {
        if (globalThis.console) globalThis.console.error('audio-io-kmp: ' + message, error);
    }

    // ==================== devices ====================

    globalThis.audioIo_refreshDevices = function () {
        if (!navigator.mediaDevices || !navigator.mediaDevices.enumerateDevices) return;
        navigator.mediaDevices.enumerateDevices().then(function (list) {
            devices = list.filter(function (device) {
                return device.kind === 'audioinput' || device.kind === 'audiooutput';
            });
            defaults = [null, null];
            for (var i = 0; i < devices.length; i++) {
                var kind = devices[i].kind === 'audioinput' ? 0 : 1;
                if (defaults[kind] === null) defaults[kind] = devices[i].deviceId;
            }
        }).catch(function (error) {
            devices = [];
            consoleError('cannot enumerate audio devices', error);
        });
    };

    globalThis.audioIo_deviceCount = function () {
        return devices.length;
    };

    globalThis.audioIo_deviceKind = function (index) {
        var device = devices[index];
        return device ? (device.kind === 'audioinput' ? 0 : 1) : -1;
    };

    globalThis.audioIo_deviceId = function (index) {
        var device = devices[index];
        return device ? device.deviceId : '';
    };

    globalThis.audioIo_deviceLabel = function (index) {
        var device = devices[index];
        if (!device) return '';
        if (device.label) return device.label;
        return (device.kind === 'audioinput' ? 'input ' : 'output ') + (index + 1);
    };

    globalThis.audioIo_defaultDeviceId = function (kind) {
        return defaults[kind] || null;
    };

    // ==================== contexts ====================

    globalThis.audioIo_createContext = function (sampleRate) {
        var Ctor = globalThis.AudioContext || globalThis.webkitAudioContext;
        if (!Ctor) return 0;
        var ctx = null;
        try {
            ctx = new Ctor({ sampleRate: sampleRate });
        } catch (error) {
            try {
                ctx = new Ctor();
            } catch (fallbackError) {
                consoleError('cannot create an AudioContext', fallbackError);
                return 0;
            }
        }
        var id = nextContext++;
        // A fresh context is suspended until the first user gesture; start()
        // resumes it.
        contexts.set(id, { ctx: ctx, block: 0, capture: null, playback: null, status: 0 });
        return id;
    };

    globalThis.audioIo_contextSampleRate = function (id) {
        var entry = context(id);
        return entry ? entry.ctx.sampleRate | 0 : 0;
    };

    /** ScriptProcessorNode only takes powers of two between 256 and 16384. */
    function blockSize(frames) {
        var wanted = frames > 0 ? frames : 1024;
        if (wanted < 256) wanted = 256;
        if (wanted > 16384) wanted = 16384;
        var size = 256;
        while (size * 2 <= wanted && size < 16384) size *= 2;
        return size;
    }

    // ==================== capture ====================

    function closeCapture(entry) {
        var capture = entry.capture;
        if (!capture) return;
        entry.capture = null;
        if (capture.node) {
            capture.node.onaudioprocess = null;
            capture.node.disconnect();
        }
        if (capture.source) capture.source.disconnect();
        if (capture.sink) capture.sink.disconnect();
        if (capture.stream) {
            capture.stream.getTracks().forEach(function (track) { track.stop(); });
        }
    }

    globalThis.audioIo_openCapture = function (id, deviceId, channels, frames) {
        var entry = context(id);
        if (!entry) return;
        closeCapture(entry);
        entry.status = 0;
        var capture = { channels: channels, ring: new Float32Array(0), capacity: 0, read: 0, write: 0 };
        entry.capture = capture;
        var constraints = {
            echoCancellation: false,
            noiseSuppression: false,
            autoGainControl: false
        };
        if (channels > 0) constraints.channelCount = { ideal: channels };
        if (deviceId) constraints.deviceId = { exact: deviceId };
        navigator.mediaDevices.getUserMedia({ audio: constraints }).then(function (stream) {
            if (entry.capture !== capture) {
                stream.getTracks().forEach(function (track) { track.stop(); });
                return;
            }
            var size = blockSize(frames);
            entry.block = size;
            capture.ring = new Float32Array(size * 4 * channels);
            capture.capacity = capture.ring.length / channels;
            capture.stream = stream;
            capture.source = entry.ctx.createMediaStreamSource(stream);
            capture.node = entry.ctx.createScriptProcessor(size, channels, 1);
            capture.node.onaudioprocess = function (event) {
                var input = event.inputBuffer;
                var frameCount = input.length;
                var available = capture.capacity - (capture.write - capture.read);
                var count = Math.min(frameCount, available);
                for (var frame = 0; frame < count; frame++) {
                    var base = ((capture.write + frame) % capture.capacity) * channels;
                    for (var channel = 0; channel < channels; channel++) {
                        var source = Math.min(channel, input.numberOfChannels - 1);
                        capture.ring[base + channel] = input.getChannelData(source)[frame];
                    }
                }
                capture.write += count;
            };
            // A ScriptProcessorNode only runs while connected to the
            // destination; the gain keeps the microphone out of the speakers.
            capture.sink = entry.ctx.createGain();
            capture.sink.gain.value = 0;
            capture.source.connect(capture.node);
            capture.node.connect(capture.sink);
            capture.sink.connect(entry.ctx.destination);
            entry.status = 1;
        }).catch(function (error) {
            if (entry.capture !== capture) return;
            entry.status = error && error.name === 'NotAllowedError' ? -1 : -2;
            consoleError('cannot open the microphone', error);
        });
    };

    /** 0 while the stream is being opened, 1 when it runs, negative on failure. */
    globalThis.audioIo_captureAvailable = function (id) {
        var entry = context(id);
        if (!entry || !entry.capture) return 0;
        return entry.capture.write - entry.capture.read;
    };

    globalThis.audioIo_captureRead = function (id, destination, maxFrames) {
        var entry = context(id);
        if (!entry || !entry.capture) return 0;
        var capture = entry.capture;
        var channels = capture.channels;
        var available = capture.write - capture.read;
        var count = Math.min(maxFrames, available);
        for (var frame = 0; frame < count; frame++) {
            var base = ((capture.read + frame) % capture.capacity) * channels;
            for (var channel = 0; channel < channels; channel++) {
                destination[frame * channels + channel] = capture.ring[base + channel];
            }
        }
        capture.read += count;
        return count;
    };

    // ==================== playback ====================

    function closePlayback(entry) {
        var playback = entry.playback;
        if (!playback) return;
        entry.playback = null;
        if (playback.node) {
            playback.node.onaudioprocess = null;
            playback.node.disconnect();
        }
    }

    globalThis.audioIo_openPlayback = function (id, deviceId, channels, frames) {
        var entry = context(id);
        if (!entry) return -1;
        closePlayback(entry);
        var size = blockSize(frames);
        entry.block = size;
        var playback = { channels: channels, ring: new Float32Array(size * 4 * channels), read: 0, write: 0, underruns: 0 };
        playback.capacity = playback.ring.length / channels;
        playback.node = entry.ctx.createScriptProcessor(size, 1, channels);
        playback.node.onaudioprocess = function (event) {
            var output = event.outputBuffer;
            var frameCount = output.length;
            var available = playback.write - playback.read;
            var count = Math.min(frameCount, available);
            for (var channel = 0; channel < channels; channel++) {
                var data = output.getChannelData(channel);
                for (var frame = 0; frame < count; frame++) {
                    data[frame] = playback.ring[((playback.read + frame) % playback.capacity) * channels + channel];
                }
                for (var silent = count; silent < frameCount; silent++) data[silent] = 0;
            }
            if (count < frameCount) playback.underruns++;
            playback.read += count;
        };
        playback.node.connect(entry.ctx.destination);
        entry.playback = playback;
        if (deviceId && entry.ctx.setSinkId) {
            entry.ctx.setSinkId(deviceId).catch(function (error) {
                consoleError('cannot route to the selected output device', error);
            });
        }
        return 0;
    };

    globalThis.audioIo_playbackFree = function (id) {
        var entry = context(id);
        if (!entry || !entry.playback) return 0;
        var playback = entry.playback;
        return playback.capacity - (playback.write - playback.read);
    };

    globalThis.audioIo_playbackWrite = function (id, source, frames) {
        var entry = context(id);
        if (!entry || !entry.playback) return 0;
        var playback = entry.playback;
        var free = playback.capacity - (playback.write - playback.read);
        var count = Math.min(frames, free);
        for (var frame = 0; frame < count; frame++) {
            var base = ((playback.write + frame) % playback.capacity) * playback.channels;
            for (var channel = 0; channel < playback.channels; channel++) {
                playback.ring[base + channel] = source[frame * playback.channels + channel];
            }
        }
        playback.write += count;
        return count;
    };

    // ==================== lifecycle ====================

    globalThis.audioIo_start = function (id) {
        var entry = context(id);
        if (entry) entry.ctx.resume();
    };

    globalThis.audioIo_suspend = function (id) {
        var entry = context(id);
        if (entry) entry.ctx.suspend();
    };

    globalThis.audioIo_close = function (id) {
        var entry = context(id);
        if (!entry) return;
        contexts.delete(id);
        closeCapture(entry);
        closePlayback(entry);
        entry.ctx.close().catch(function () {});
    };

    globalThis.audioIo_refreshDevices();
})();

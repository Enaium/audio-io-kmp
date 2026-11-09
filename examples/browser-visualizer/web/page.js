/*
 * Page side of the browser visualizer.
 *
 * The controls are plain HTML so they work without drawing a UI toolkit into
 * the canvas; this file wires them to the command bits the Kotlin module polls
 * and draws the three plots on the canvas. The Kotlin side calls the flat
 * webApp_* functions below and owns the animation frame loop; the buttons only
 * set bits, so no call goes from JavaScript into Kotlin.
 */
(function () {
    if (globalThis.webApp_start) return;

    var canvas = null;
    var context = null;
    var windowSelect = null;
    var startButton = null;
    var recordButton = null;
    var playButton = null;
    var status = null;
    var commands = 0;
    var recording = false;

    // The waterfall keeps its own canvas: scrolling one pixel and drawing the
    // new row is a lot cheaper than storing and repainting every frame here.
    var waterfall = null;
    var waterfallContext = null;
    var WATERFALL_COLUMNS = 128;
    var WATERFALL_ROWS = 64;

    function color(value) {
        // Dark blue -> cyan -> yellow -> white, the desktop example's colormap.
        var t = value < 0 ? 0 : value > 1 ? 1 : value;
        var r, g, b;
        if (t < 0.33) {
            var a = t / 0.33;
            r = 12 + a * 20;
            g = 20 + a * 120;
            b = 60 + a * 150;
        } else if (t < 0.66) {
            var c = (t - 0.33) / 0.33;
            r = 32 + c * 190;
            g = 140 + c * 90;
            b = 210 - c * 140;
        } else {
            var d = (t - 0.66) / 0.34;
            r = 222 + d * 33;
            g = 230 + d * 25;
            b = 70 + d * 120;
        }
        return 'rgb(' + (r | 0) + ',' + (g | 0) + ',' + (b | 0) + ')';
    }

    function resize() {
        if (!canvas) return;
        var ratio = globalThis.devicePixelRatio || 1;
        var width = Math.floor(globalThis.innerWidth * ratio);
        var height = Math.floor(globalThis.innerHeight * ratio);
        if (canvas.width !== width || canvas.height !== height) {
            canvas.width = width;
            canvas.height = height;
        }
        canvas.style.width = globalThis.innerWidth + 'px';
        canvas.style.height = globalThis.innerHeight + 'px';
    }

    globalThis.webApp_start = function () {
        canvas = document.getElementById('canvas');
        context = canvas.getContext('2d');
        windowSelect = document.getElementById('window');
        startButton = document.getElementById('start');
        recordButton = document.getElementById('record');
        playButton = document.getElementById('play');
        status = document.getElementById('status');

        waterfall = document.createElement('canvas');
        waterfall.width = WATERFALL_COLUMNS;
        waterfall.height = WATERFALL_ROWS;
        waterfallContext = waterfall.getContext('2d');
        waterfallContext.fillStyle = '#05060b';
        waterfallContext.fillRect(0, 0, WATERFALL_COLUMNS, WATERFALL_ROWS);

        startButton.onclick = function () { commands = commands | 1; };
        recordButton.onclick = function () { commands = commands | 4; };
        playButton.onclick = function () { commands = commands | 8; };
        document.getElementById('clear').onclick = function () { commands = commands | 16; };
        window.addEventListener('resize', resize);
        resize();
    };

    /** Takes the pending buttons and clears them. */
    globalThis.webApp_takeCommand = function () {
        var value = commands;
        commands = 0;
        return value;
    };

    globalThis.webApp_windowSize = function () {
        return windowSelect ? parseInt(windowSelect.value, 10) | 0 : 1024;
    };

    globalThis.webApp_setRecording = function (value) {
        recording = !!value;
        if (recordButton) {
            recordButton.textContent = recording ? 'Stop recording' : 'Record';
            recordButton.classList.toggle('active', recording);
        }
    };

    globalThis.webApp_setPlayback = function (value) {
        if (playButton) {
            playButton.textContent = value ? 'Stop playback' : 'Play';
            playButton.classList.toggle('active', !!value);
        }
    };

    globalThis.webApp_setStatus = function (text) {
        if (status) status.textContent = text;
    };

    globalThis.webApp_width = function () {
        return canvas ? canvas.width : 0;
    };

    globalThis.webApp_height = function () {
        return canvas ? canvas.height : 0;
    };

    globalThis.webApp_scale = function () {
        return globalThis.devicePixelRatio || 1;
    };

    // ==================== drawing ====================

    globalThis.webApp_begin = function () {
        if (!context) return;
        context.fillStyle = '#121218';
        context.fillRect(0, 0, canvas.width, canvas.height);
    };

    globalThis.webApp_title = function (text, x, y, size) {
        if (!context) return;
        context.fillStyle = '#8b93a7';
        context.font = (size | 0) + 'px system-ui, sans-serif';
        context.fillText(text, x, y);
    };

    /** Polyline of normalised samples spanning [-1, 1] vertically. */
    globalThis.webApp_waveform = function (values, count, x, y, width, height) {
        if (!context || count <= 0) return;
        context.strokeStyle = '#59d9ff';
        context.lineWidth = Math.max(1, (globalThis.devicePixelRatio || 1) * 1.2);
        context.beginPath();
        for (var i = 0; i < count; i++) {
            var px = x + (width * i) / (count - 1 || 1);
            var py = y + height * (0.5 - 0.5 * values[i]);
            if (i === 0) context.moveTo(px, py);
            else context.lineTo(px, py);
        }
        context.stroke();
    };

    /** Bars for the normalised magnitudes, low frequency on the left. */
    globalThis.webApp_spectrum = function (values, count, x, y, width, height) {
        if (!context || count <= 0) return;
        var bar = Math.max(1, width / count);
        for (var i = 0; i < count; i++) {
            var value = values[i];
            if (value <= 0) continue;
            var barHeight = height * Math.min(1, value);
            context.fillStyle = color(0.35 + 0.65 * Math.min(1, value));
            context.fillRect(x + i * bar, y + height - barHeight, Math.max(1, bar - 0.5), barHeight);
        }
    };

    /** Pushes one row of normalised magnitudes into the scrolling spectrogram. */
    globalThis.webApp_waterfallRow = function (values, count) {
        if (!waterfallContext || count <= 0) return;
        waterfallContext.drawImage(waterfall, 0, 1);
        for (var column = 0; column < WATERFALL_COLUMNS; column++) {
            var from = Math.floor((column * count) / WATERFALL_COLUMNS);
            var to = Math.floor(((column + 1) * count) / WATERFALL_COLUMNS);
            var peak = values[from] || 0;
            for (var i = from; i < to; i++) if (values[i] > peak) peak = values[i];
            waterfallContext.fillStyle = color(peak);
            waterfallContext.fillRect(column, 0, 1, 1);
        }
    };

    globalThis.webApp_waterfall = function (x, y, width, height) {
        if (!context || !waterfall) return;
        context.imageSmoothingEnabled = false;
        context.drawImage(waterfall, x, y, width, height);
    };

    globalThis.webApp_clearWaterfall = function () {
        if (!waterfallContext) return;
        waterfallContext.fillStyle = '#05060b';
        waterfallContext.fillRect(0, 0, WATERFALL_COLUMNS, WATERFALL_ROWS);
    };
})();

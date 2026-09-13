package cn.enaium.audio.example.visualizer

/** Parses `--frames N` (or `--frames=N`); `0` renders until the window is closed. */
private fun parseFrames(args: Array<String>): Int {
    var frames = 0
    var i = 0
    while (i < args.size) {
        val arg = args[i]
        when {
            arg == "--frames" && i + 1 < args.size -> {
                frames = args[i + 1].toIntOrNull() ?: 0
                i++
            }

            arg.startsWith("--frames=") -> frames = arg.removePrefix("--frames=").toIntOrNull() ?: 0
        }
        i++
    }
    return frames
}

fun main(args: Array<String>) {
    val frames = parseFrames(args)
    println("audio-io-kmp spectrum visualizer (frames=${if (frames <= 0) "unlimited" else frames})")
    VisualizerApp.run(frames)
}

package io.github.intramuros.weatherbuddy.core

/** One step of the picture, drawn bottom-most first. */
sealed interface Layer {
    /** The image to draw in a still picture (widget, static wallpaper). */
    val still: String

    /** Every asset this layer can draw. */
    val assets: List<String>

    /** A hand-drawn image, or a loop of them (hair in the wind, blinking). */
    data class Sprite(val frames: List<Frame>) : Layer {
        constructor(path: String) : this(listOf(Frame(path, 1.0)))

        init {
            require(frames.isNotEmpty() && frames.all { it.seconds > 0 })
        }

        override val still: String get() = frames.first().path
        override val assets: List<String> get() = frames.map { it.path }
        val isAnimated: Boolean get() = frames.size > 1
        private val loopSeconds = frames.sumOf { it.seconds }

        fun frameAt(seconds: Double): String {
            var t = seconds.mod(loopSeconds)
            for (frame in frames) {
                if (t < frame.seconds) return frame.path
                t -= frame.seconds
            }
            return frames.last().path
        }
    }

    /**
     * Rain, snow, hail or wind. A still picture uses the painted [still] image;
     * the live wallpaper animates [specs] instead.
     */
    data class Particles(override val still: String, val specs: List<ParticleSpec>) : Layer {
        override val assets: List<String> get() = listOf(still)
    }
}

data class Frame(val path: String, val seconds: Double)

package app.lyra.music.playback

/** Bounds consecutive automatic skips and never wraps around a failing queue. */
internal class FailedTrackSkipPolicy {
    private var consecutiveFailures = 0

    fun nextIndex(enabled: Boolean, failedIndex: Int, size: Int): Int? {
        if (!enabled || failedIndex !in 0 until size) return null
        consecutiveFailures++
        return (failedIndex + 1).takeIf {
            consecutiveFailures <= MAX_FAILURES && it < size
        }
    }

    fun reset() {
        consecutiveFailures = 0
    }

    companion object {
        private const val MAX_FAILURES = 5
    }
}

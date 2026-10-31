package app.lyra.music.audio

/** Evita retry illimitati quando il dispositivo contiene solo file non analizzabili. */
internal object AudioScanRetryPolicy {
    private const val MAX_NO_PROGRESS_ATTEMPTS = 3

    fun startOffset(pendingCount: Int, runAttemptCount: Int, batchSize: Int): Int {
        if (pendingCount <= 0 || batchSize <= 0) return 0
        return ((runAttemptCount.toLong() * batchSize) % pendingCount).toInt()
    }

    fun shouldRetry(
        remaining: Int,
        successThisRun: Int,
        runAttemptCount: Int,
        priorityScan: Boolean,
        stopped: Boolean,
    ): Boolean {
        if (priorityScan || stopped || remaining <= 0) return false
        return successThisRun > 0 || runAttemptCount < MAX_NO_PROGRESS_ATTEMPTS
    }
}

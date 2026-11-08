package app.lyra.music.playback

/** Reuse the oldest verified item only after a refill fails at the queue tail. */
internal object VerifiedQueueRecovery {
    fun targetIndex(isVerified: Boolean, currentIndex: Int, size: Int): Int? =
        if (isVerified && size > 1 && currentIndex == size - 1) 0 else null
}

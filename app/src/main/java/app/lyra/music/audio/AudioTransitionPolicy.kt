package app.lyra.music.audio

/** Criteri indipendenti dal player, verificabili anche con test JVM. */
internal object AudioTransitionPolicy {
    fun canCrossfade(
        enabled: Boolean,
        queueHasNext: Boolean,
        repeatOne: Boolean,
        remainingDurationMs: Long,
        fadeMs: Int,
        firstAlbum: String?,
        secondAlbum: String?,
        firstAlbumId: Long?,
        secondAlbumId: Long?,
        firstDurationMs: Long,
    ): Boolean {
        if (!enabled || !queueHasNext || repeatOne) return false
        if (firstDurationMs < fadeMs * 3L || remainingDurationMs < 0L) return false
        // Per album continui e registrazioni live il gapless e' piu' fedele.
        if (firstAlbumId != null && firstAlbumId == secondAlbumId) return false
        if (!firstAlbum.isNullOrBlank() && firstAlbum.equals(secondAlbum, ignoreCase = true)) return false
        return true
    }
}

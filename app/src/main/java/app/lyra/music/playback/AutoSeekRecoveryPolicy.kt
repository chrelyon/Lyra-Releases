package app.lyra.music.playback

/**
 * Decisione pura per il recupero dei comandi Avanti/Indietro di Android Auto.
 * Un secondo seek e' permesso solo se il primo non ha cambiato ne' l'indice
 * ne' il brano corrente, e la coda persistita punta ancora allo stesso brano.
 *
 * Il riavvolgimento all'inizio del brano su PREVIOUS conta come comando riuscito.
 */
internal object AutoSeekRecoveryPolicy {
    fun shouldRecover(
        direction: Int,
        beforeIndex: Int,
        afterIndex: Int,
        beforeMediaId: String?,
        afterMediaId: String?,
        beforePositionMs: Long,
        afterPositionMs: Long,
        navigationInFlight: Boolean,
        persistedCurrentMediaId: String?,
        hasNext: Boolean,
        hasPrevious: Boolean,
    ): Boolean {
        if (direction != 1 && direction != -1) return false
        if (navigationInFlight) return false
        if (direction > 0 && !hasNext || direction < 0 && !hasPrevious) return false
        if (beforeIndex != afterIndex || beforeMediaId != afterMediaId) return false
        if (afterMediaId.isNullOrBlank() || persistedCurrentMediaId != afterMediaId) return false
        // Android Auto puo' richiedere PREVIOUS per ripartire da zero nella
        // traccia corrente. Lo stesso vale per alcuni player con repeat ONE.
        if (beforePositionMs > 2_000L && afterPositionMs >= 0L &&
            afterPositionMs + 1_500L < beforePositionMs
        ) return false
        return true
    }
}

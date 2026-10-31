package app.lyra.music.personalization

/** No player/Android dependencies: applying a reply may only change the future. */
object ServerQueuePolicy {
    fun canCommit(preparedTrack: String, endedTrack: String?, preparedRevision: String,
                  liveRevision: String, repeatOff: Boolean): Boolean =
        endedTrack != null && preparedTrack == endedTrack && preparedRevision == liveRevision && repeatOff

    fun replaceFuture(queueIds: List<String>, currentId: String, futureIds: List<String>): List<String> {
        val at = queueIds.indexOf(currentId)
        if (at < 0) return queueIds
        val prefix = queueIds.take(at + 1)
        return prefix + futureIds.distinct().filterNot { it in prefix }
    }
}

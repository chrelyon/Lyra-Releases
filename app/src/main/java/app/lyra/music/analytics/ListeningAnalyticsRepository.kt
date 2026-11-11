package app.lyra.music.analytics

import app.lyra.music.cloud.CloudProfileSync
import app.lyra.music.personalization.ListeningEventStore
import app.lyra.music.playback.ListeningProfileStore
import org.json.JSONObject

/** Sola lettura. Non effettua restore e non modifica l'apprendimento Lyra. */
object ListeningAnalyticsRepository {
    fun localSnapshot(): AnalyticsSnapshot = AnalyticsSnapshot(
        tracks = ListeningProfileStore.stats.value.values.map { stat ->
            AnalyticsTrack(
                id = stat.song.id,
                title = stat.song.title,
                artist = stat.song.artist,
                plays = stat.playCount,
                completions = stat.completionCount,
                earlySkips = stat.skipCount,
                partials = stat.partialCount,
                liked = stat.liked,
                blocked = stat.blocked,
            )
        },
        events = ListeningEventStore.events.value.map { event ->
            AnalyticsEvent(
                id = event.id,
                type = event.type.name,
                timestampMs = event.timestampMs,
                songId = event.songId,
                title = event.title,
                artist = event.artist,
                listenedMs = event.listenedMs ?: 0L,
                completed = event.completed == true,
                earlySkip = event.earlySkip == true,
                contextTag = event.contextTag,
                queueTitle = event.queueTitle,
            )
        },
        source = "Dati locali",
    )

    suspend fun cloudSnapshot(): AnalyticsSnapshot {
        val root = CloudProfileSync.readAnalyticsSnapshot()
        val profile = root.optJSONObject("profile")
            ?: throw IllegalStateException("Lyra non contiene ancora un profilo musicale")
        val tracks = profile.optJSONArray("tracks")
        val events = root.optJSONArray("events")
        return AnalyticsSnapshot(
            tracks = buildList {
                if (tracks != null) for (i in 0 until tracks.length()) {
                    val item = tracks.optJSONObject(i) ?: continue
                    val id = item.optString("songId")
                    if (id.isBlank()) continue
                    add(
                        AnalyticsTrack(
                            id = id,
                            title = item.optString("title"),
                            artist = item.optString("artist"),
                            plays = item.optInt("playCount", 0),
                            completions = item.optInt("completionCount", 0),
                            earlySkips = item.optInt("skipCount", 0),
                            partials = item.optInt("partialCount", 0),
                            liked = item.optBoolean("liked", false),
                            blocked = item.optBoolean("blocked", false),
                        )
                    )
                }
            },
            events = buildList {
                if (events != null) for (i in 0 until events.length()) {
                    val item: JSONObject = events.optJSONObject(i) ?: continue
                    val id = item.optString("event_id")
                    if (id.isBlank()) continue
                    add(
                        AnalyticsEvent(
                            id = id,
                            type = item.optString("event_type"),
                            timestampMs = item.optLong("created_at_ms", 0L),
                            songId = item.optString("song_id"),
                            title = item.optString("title"),
                            artist = item.optString("artist"),
                            listenedMs = item.optLong("listened_ms", 0L),
                            completed = item.optInt("completed", 0) == 1,
                            earlySkip = item.optInt("early_skip", 0) == 1,
                            contextTag = item.optString("context_tag").takeIf { it.isNotBlank() && it != "null" },
                            queueTitle = item.optString("queue_title").takeIf { it.isNotBlank() && it != "null" },
                        )
                    )
                }
            },
            source = "Lyra · backup musicale",
            updatedAtMs = profile.optLong("updatedAtMs", root.optLong("updatedAtMs", 0L)),
        )
    }
}

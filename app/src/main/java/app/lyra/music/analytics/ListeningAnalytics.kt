package app.lyra.music.analytics

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Modello indipendente da Android, usato sia per Ubuntu che per i dati locali. */
data class AnalyticsTrack(
    val id: String,
    val title: String,
    val artist: String,
    val plays: Int,
    val completions: Int,
    val earlySkips: Int,
    val partials: Int,
    val liked: Boolean,
    val blocked: Boolean,
)

data class AnalyticsEvent(
    val id: String,
    val type: String,
    val timestampMs: Long,
    val songId: String,
    val title: String,
    val artist: String,
    val listenedMs: Long,
    val completed: Boolean,
    val earlySkip: Boolean,
    val contextTag: String?,
    val queueTitle: String?,
)

data class AnalyticsSnapshot(
    val tracks: List<AnalyticsTrack>,
    val events: List<AnalyticsEvent>,
    val source: String,
    val updatedAtMs: Long = 0L,
)

data class AnalyticsRow(
    val label: String,
    val value: Int,
    val detail: String,
)

data class AnalyticsDay(val label: String, val count: Int, val listenedMinutes: Long)

data class AnalyticsOutcome(val completed: Int, val partial: Int, val earlySkip: Int) {
    val total: Int get() = completed + partial + earlySkip
}

object ListeningAnalytics {
    fun artistRows(tracks: List<AnalyticsTrack>, limit: Int = 10): List<AnalyticsRow> =
        tracks.asSequence()
            .filterNot { it.blocked }
            .groupBy { it.artist.trim() }
            .filterKeys { it.isNotBlank() }
            .map { (artist, songs) ->
                val plays = songs.sumOf { it.plays.coerceAtLeast(0) }
                val completed = songs.sumOf { it.completions.coerceAtLeast(0) }
                val skipped = songs.sumOf { it.earlySkips.coerceAtLeast(0) }
                val likes = songs.count { it.liked }
                val rating = (plays + completed * 3 + likes * 5 - skipped * 3).coerceAtLeast(0)
                AnalyticsRow(artist, rating, "$plays avvii · $completed completi · $skipped skip precoci · $likes preferiti")
            }
            .filter { it.value > 0 }
            .sortedWith(compareByDescending<AnalyticsRow> { it.value }.thenBy { it.label })
            .take(limit)
            .toList()

    fun trackRows(tracks: List<AnalyticsTrack>, limit: Int = 10): List<AnalyticsRow> =
        tracks.asSequence()
            .filterNot { it.blocked }
            .map { track ->
                AnalyticsRow(
                    label = track.title.ifBlank { "Brano senza titolo" },
                    value = (track.plays + track.completions * 3 + if (track.liked) 5 else 0 - track.earlySkips * 3).coerceAtLeast(0),
                    detail = "${track.artist} · ${track.plays} avvii · ${track.completions} completi · ${track.earlySkips} skip",
                )
            }
            .filter { it.value > 0 }
            .sortedByDescending { it.value }
            .take(limit)
            .toList()

    fun filteredEvents(events: List<AnalyticsEvent>, days: Int, nowMs: Long): List<AnalyticsEvent> {
        val zone = ZoneId.systemDefault()
        val firstDay = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
            .minusDays(days.coerceAtLeast(1).toLong() - 1L)
        return events.filter { event ->
            event.timestampMs in 1..nowMs &&
                !Instant.ofEpochMilli(event.timestampMs).atZone(zone).toLocalDate().isBefore(firstDay)
        }
    }

    fun outcome(events: List<AnalyticsEvent>): AnalyticsOutcome {
        val results = events.filter { it.type == "OUTCOME" }
        return AnalyticsOutcome(
            completed = results.count { it.completed },
            partial = results.count { !it.completed && !it.earlySkip },
            earlySkip = results.count { !it.completed && it.earlySkip },
        )
    }

    fun contexts(events: List<AnalyticsEvent>, limit: Int = 10): List<AnalyticsRow> =
        events.asSequence()
            .filter { it.type == "STARTED" }
            .mapNotNull { event ->
                val context = event.queueTitle?.trim()?.takeIf { it.isNotEmpty() }
                    ?: event.contextTag?.trim()?.takeIf { it.isNotEmpty() }
                context
            }
            .groupingBy { it }
            .eachCount()
            .entries.sortedByDescending { it.value }
            .take(limit)
            .map { AnalyticsRow(it.key, it.value, "${it.value} avvii registrati") }

    fun daily(events: List<AnalyticsEvent>, days: Int, nowMs: Long, zoneId: ZoneId = ZoneId.systemDefault()): List<AnalyticsDay> {
        val today = Instant.ofEpochMilli(nowMs).atZone(zoneId).toLocalDate()
        val recent = filteredEvents(events, days, nowMs)
        val starts = recent.filter { it.type == "STARTED" }
            .groupBy { Instant.ofEpochMilli(it.timestampMs).atZone(zoneId).toLocalDate() }
        val outcomes = recent.filter { it.type == "OUTCOME" }
            .groupBy { Instant.ofEpochMilli(it.timestampMs).atZone(zoneId).toLocalDate() }
        return (days - 1 downTo 0).map { offset ->
            val date: LocalDate = today.minusDays(offset.toLong())
            val count = starts[date]?.size ?: 0
            val milliseconds = outcomes[date]?.sumOf { it.listenedMs.coerceAtLeast(0L) } ?: 0L
            AnalyticsDay("${date.dayOfMonth}/${date.monthValue}", count, milliseconds / 60_000L)
        }
    }
}

package app.lyra.music.cloud

import org.json.JSONArray
import org.json.JSONObject

/** Retain local changes made while a shared profile sync was in flight. */
internal object MusicSnapshotMerge {
    private val counters = setOf("playCount", "completionCount", "skipCount", "partialCount", "boostCount")
    private fun entries(root: JSONObject): Map<String, JSONObject> {
        val array = root.optJSONArray("tracks") ?: JSONArray()
        return (0 until array.length()).mapNotNull { array.optJSONObject(it) }
            .filter { it.optString("songId").isNotBlank() }.associateBy { it.getString("songId") }
    }
    private fun deltaMap(shared: JSONObject?, local: JSONObject?, before: JSONObject?): JSONObject {
        val result = JSONObject(shared?.toString() ?: "{}")
        local?.keys()?.forEach { key ->
            result.put(key, (result.optDouble(key, 0.0) + local.optDouble(key, 0.0) - (before?.optDouble(key, 0.0) ?: 0.0)))
        }
        return result
    }
    fun merge(shared: JSONObject, local: JSONObject, sent: JSONObject): JSONObject {
        val result = JSONObject(shared.toString())
        val tracks = entries(shared).mapValues { JSONObject(it.value.toString()) }.toMutableMap()
        val before = entries(sent)
        entries(local).forEach { (id, current) ->
            val previous = before[id] ?: JSONObject()
            val target = tracks.getOrPut(id) { JSONObject().put("songId", id) }
            val initial = JSONObject(target.toString())
            current.keys().forEach { key ->
                when (key) {
                    in counters -> target.put(key, (target.optInt(key) + current.optInt(key) - previous.optInt(key)).coerceAtLeast(0))
                    "contextScores" -> target.put(key, deltaMap(target.optJSONObject(key), current.optJSONObject(key), previous.optJSONObject(key)))
                    "lastPlayedAtMs" -> target.put(key, maxOf(target.optLong(key), current.optLong(key)))
                    else -> if (current.opt(key) != previous.opt(key)) target.put(key, current.get(key))
                }
            }
            val scores = target.optJSONObject("contextScores")
            scores?.keys()?.forEach { key -> scores.put(key, scores.optDouble(key, 0.0).coerceIn(-6.0, 8.0)) }
            if (current.has("averageListenedFraction")) {
                fun outcomes(t: JSONObject): Int = t.optInt("completionCount") + t.optInt("skipCount") + t.optInt("partialCount")
                val total = initial.optDouble("averageListenedFraction", 0.0) * outcomes(initial) +
                    current.optDouble("averageListenedFraction", 0.0) * outcomes(current) -
                    previous.optDouble("averageListenedFraction", 0.0) * outcomes(previous)
                target.put("averageListenedFraction", if (outcomes(target) > 0) (total / outcomes(target)).coerceIn(0.0, 1.0) else 0.0)
            }
        }
        result.put("tracks", JSONArray(tracks.values.toList()))
        for (key in listOf("artistPenalties", "artistBoosts")) {
            val values = deltaMap(shared.optJSONObject(key), local.optJSONObject(key), sent.optJSONObject(key))
            values.keys().forEach { name -> values.put(name, values.optDouble(name, 0.0).coerceIn(if (key == "artistPenalties") -1.0 else 0.0, if (key == "artistPenalties") 0.0 else 1.0)) }
            result.put(key, values)
        }
        return result
    }
}

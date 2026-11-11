package app.lyra.music.genre
import app.lyra.music.core.model.Song
import kotlinx.coroutines.flow.MutableStateFlow
object GenreClassifierSettings {
    val authorized = MutableStateFlow(false)
    fun isConfigured() = authorized.value
}
object GenreClassifierClient {
    suspend fun filterKnownIncompatible(tag: String?, songs: List<Song>) = songs
}
object VerifiedGenrePlaylistClient {
    data class Result(val songs: List<Song>, val notice: String? = null, val retryAfterSeconds: Int? = null)
    var fail = false
    var calls = 0
    fun supported(id: String) = true
    fun isMood(id: String) = false
    fun enabled(id: String) = GenreClassifierSettings.isConfigured()
    suspend fun fetch(id: String, limit: Int = 40) = fetchWithStatus(id, limit = limit).songs
    suspend fun fetchWithStatus(id: String, exclude: Set<String> = emptySet(), limit: Int = 40, requireMix: Boolean = false): Result {
        calls++
        if (fail) throw java.io.IOException("Synthetic server outage")
        return Result((0 until 5).map { Song("server%05d".format(it), "Song $it", "Artist", 200) })
    }
}

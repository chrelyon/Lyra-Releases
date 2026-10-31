package app.lyra.music.genre
import app.lyra.music.core.model.Song
object GenreClassifierClient {
    suspend fun filterKnownIncompatible(tag: String?,songs: List<Song>)=songs
}

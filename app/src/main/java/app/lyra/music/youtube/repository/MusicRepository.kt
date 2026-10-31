package app.lyra.music.youtube.repository

import app.lyra.music.core.model.Playlist
import app.lyra.music.core.model.PlaylistContent
import app.lyra.music.core.model.Song

interface MusicRepository {

    suspend fun getPlaylists(): List<Playlist>

    suspend fun getPlaylist(
        playlistId: String
    ): PlaylistContent

    suspend fun searchSongs(
        query: String
    ): List<Song>
}

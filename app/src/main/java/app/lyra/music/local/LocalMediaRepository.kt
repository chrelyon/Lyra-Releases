package app.lyra.music.local

import android.Manifest
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import app.lyra.music.core.model.Song
import app.lyra.music.diagnostics.LyraLog as Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Lettura in sola lettura della libreria audio indicizzata da MediaStore. */
class LocalMediaRepository(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver

    suspend fun getSongs(): List<Song> = withContext(Dispatchers.IO) {
        if (!LocalMediaAccess.hasPermission(appContext)) return@withContext emptyList()

        val songs = querySongs()
        Log.d("LYRA_LOCAL", "library songs=${songs.size}")
        songs
    }

    suspend fun searchSongs(query: String): List<Song> = withContext(Dispatchers.IO) {
        if (query.isBlank() || !LocalMediaAccess.hasPermission(appContext)) {
            return@withContext emptyList()
        }

        val needle = query.trim().lowercase()
        getSongs().filter { song ->
            song.title.lowercase().contains(needle) ||
                song.artist.lowercase().contains(needle) ||
                song.album.orEmpty().lowercase().contains(needle)
        }.take(MAX_SEARCH_RESULTS)
    }

    private fun querySongs(): List<Song> {
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = buildList {
            add(MediaStore.Audio.Media._ID)
            add(MediaStore.Audio.Media.TITLE)
            add(MediaStore.Audio.Media.ARTIST)
            add(MediaStore.Audio.Media.ALBUM)
            add(MediaStore.Audio.Media.ALBUM_ID)
            add(MediaStore.Audio.Media.DURATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(MediaStore.Audio.Media.RELATIVE_PATH)
            } else {
                @Suppress("DEPRECATION")
                add(MediaStore.Audio.Media.DATA)
            }
        }.toTypedArray()

        // MediaStore considera spesso come "music" anche note vocali, audio di
        // messaggistica e altri file multimediali. Lyra mostra deliberatamente
        // soltanto la cartella Music standard di Android e le sue sottocartelle.
        val (selection, selectionArgs) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND " +
                "${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ?" to arrayOf("Music/%")
        } else {
            @Suppress("DEPRECATION")
            val dataColumn = MediaStore.Audio.Media.DATA
            "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND $dataColumn LIKE ?" to
                arrayOf("%/Music/%")
        }
        val sortOrder = "${MediaStore.Audio.Media.ARTIST} COLLATE NOCASE, " +
            "${MediaStore.Audio.Media.ALBUM} COLLATE NOCASE, " +
            "${MediaStore.Audio.Media.TRACK} ASC, ${MediaStore.Audio.Media.TITLE} COLLATE NOCASE"

        return buildList {
            resolver.query(
                collection,
                projection,
                selection,
                selectionArgs,
                sortOrder,
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val albumIdColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)

                while (cursor.moveToNext()) {
                    val mediaId = cursor.getLong(idColumn)
                    val contentUri = ContentUris.withAppendedId(collection, mediaId)
                    val albumId = cursor.getLong(albumIdColumn).takeIf { it > 0L }
                    val albumArtUri = albumId?.let(::albumArtworkUri)

                    add(
                        Song(
                            id = "local:$mediaId",
                            title = cursor.getString(titleColumn)
                                ?.takeIf { it.isNotBlank() }
                                ?: "Brano locale",
                            artist = normalizeUnknown(cursor.getString(artistColumn), "Artista sconosciuto"),
                            durationSeconds = (cursor.getLong(durationColumn) / 1_000L)
                                .coerceAtLeast(0L),
                            thumbnailUrl = albumArtUri?.toString(),
                            playbackUri = contentUri.toString(),
                            album = normalizeUnknown(cursor.getString(albumColumn), "Album sconosciuto"),
                            albumId = albumId,
                        )
                    )
                }
            }
        }
    }

    private fun albumArtworkUri(albumId: Long): Uri = ContentUris.withAppendedId(
        Uri.parse("content://media/external/audio/albumart"),
        albumId,
    )

    private fun normalizeUnknown(value: String?, fallback: String): String {
        val cleaned = value.orEmpty().trim()
        return if (
            cleaned.isBlank() ||
            cleaned.equals(MediaStore.UNKNOWN_STRING, ignoreCase = true) ||
            cleaned.equals("<unknown>", ignoreCase = true)
        ) {
            fallback
        } else {
            cleaned
        }
    }

    private companion object {
        const val MAX_SEARCH_RESULTS = 100
    }
}

object LocalMediaAccess {
    fun requiredPermission(): String = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_AUDIO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

    fun hasPermission(context: Context): Boolean = ContextCompat.checkSelfPermission(
        context,
        requiredPermission(),
    ) == PackageManager.PERMISSION_GRANTED
}

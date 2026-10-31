package app.lyra.music.playback

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import app.lyra.music.core.model.Song

/**
 * Unico punto in cui Lyra traduce i metadata di un Song in un MediaItem Media3.
 */
object PlaybackMediaItemFactory {

    fun playable(
        source: PlaybackSource,
        song: Song,
        artworkUri: Uri? = null,
        contextLabel: String? = null,
    ): MediaItem {
        return MediaItem.Builder()
            .setMediaId(song.id)
            .setUri(source.url)
            .setMimeType(source.mimeType)
            .setMediaMetadata(metadata(song, artworkUri, source.loudnessDb, contextLabel))
            .build()
    }

    fun local(
        song: Song,
        artworkUri: Uri? = null,
        contextLabel: String? = null,
    ): MediaItem {
        val uri = song.playbackUri
            ?.takeIf { it.isNotBlank() }
            ?.let(Uri::parse)
            ?: error("Brano locale senza playbackUri: ${song.id}")

        return MediaItem.Builder()
            .setMediaId(song.id)
            .setUri(uri)
            .setMediaMetadata(metadata(song, artworkUri, contextLabel = contextLabel))
            .build()
    }

    fun metadataOnly(song: Song, contextLabel: String? = null): MediaItem {
        return MediaItem.Builder()
            .setMediaId(song.id)
            .setMediaMetadata(metadata(song, contextLabel = contextLabel))
            .build()
    }

    /**
     * Per YouTube usa lyra://stream/...; per la libreria locale usa direttamente
     * il content:// MediaStore. In entrambi i casi ExoPlayer puo' mantenere una
     * timeline completa per Android Auto, NEXT/PREV e resume.
     */
    fun deferred(
        song: Song,
        artworkUri: Uri? = null,
        contextLabel: String? = null,
    ): MediaItem {
        return if (song.isLocal) {
            local(song, artworkUri, contextLabel)
        } else {
            MediaItem.Builder()
                .setMediaId(song.id)
                .setUri(LyraDeferredPlayback.uri(song.id))
                .setMediaMetadata(metadata(song, artworkUri, contextLabel = contextLabel))
                .build()
        }
    }

    private fun metadata(
        song: Song,
        artworkUri: Uri? = null,
        loudnessDb: Double? = null,
        contextLabel: String? = null,
    ): MediaMetadata {
        val context = contextLabel?.trim()?.takeIf { it.isNotBlank() }
        // Alcuni host Android Auto visualizzano DISPLAY_SUBTITLE prima di ARTIST.
        // Il contesto della playlist resta esclusivamente negli extras.
        val artist = song.artist.trim().ifBlank { "Artista non disponibile" }
        val extras = Bundle().apply {
            loudnessDb?.let { putDouble(EXTRA_LOUDNESS_DB, it) }
            context?.let { putString(EXTRA_CONTEXT_LABEL, it) }
        }
        return MediaMetadata.Builder()
            .setTitle(song.title)
            .setDisplayTitle(song.title)
            .setArtist(artist)
            .setSubtitle(artist)
            .setDescription(artist)
            .setAlbumTitle(song.album)
            .apply {
                if (!extras.isEmpty) setExtras(extras)
                val artwork = artworkUri
                    ?: song.thumbnailUrl
                        ?.takeIf { it.isNotBlank() }
                        ?.let(Uri::parse)
                artwork?.let(::setArtworkUri)
            }
            .build()
    }

    const val EXTRA_LOUDNESS_DB = "lyra.audio.loudnessDb"
    const val EXTRA_CONTEXT_LABEL = "lyra.playback.contextLabel"
}

package app.lyra.music.playback

import app.lyra.music.audio.AudioSettings
import app.lyra.music.diagnostics.LyraLog as Log
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.YouTubeClient
import com.metrolist.innertubex.cipher.PlayerConfigRepository
import com.metrolist.innertubex.cipher.RemotePlayerConfigStore
import com.metrolist.innertubex.cipher.YouTubeCipherService
import com.metrolist.innertubex.extraction.AudioQuality
import com.metrolist.innertubex.extraction.ContentHints
import com.metrolist.innertubex.extraction.InnerTubeExtractor
import com.metrolist.innertubex.extraction.StreamExtractor
import com.metrolist.innertubex.extraction.YtConfigParserImpl

/**
 * Risolve un video YouTube in una sorgente audio riproducibile da Media3.
 *
 * Fase 2B:
 * - la scelta dei client non e' piu' hardcoded in Lyra;
 * - InnerTubeX gestisce fallback client e cipher/n-transform;
 * - chiediamo volutamente solo trasporti HTTP/HTTPS direttamente compatibili
 *   con il player attuale;
 * - SABR, HLS e client che richiedono bounded-range restano disabilitati per
 *   isolare il test del nuovo resolver.
 */
class YouTubePlaybackRepository : PlaybackRepository {

    @Volatile
    private var cachedExtractor: StreamExtractor? = null

    @Volatile
    private var cachedTransportGeneration: Long = Long.MIN_VALUE

    override suspend fun getPlaybackSource(
        videoId: String
    ): PlaybackSource {
        val extractor = getExtractor()

        val hints = ContentHints(
            wantVideo = false
        ).withStreamCapabilities(
            allowHls = false,
            allowSabr = false,
            allowBoundedRange = false,
        )

        val requestedQuality = when (AudioSettings.streamingQuality.value) {
            AudioSettings.StreamingQuality.AUTOMATIC -> AudioQuality.AUTO
            AudioSettings.StreamingQuality.HIGH -> AudioQuality.HIGH
            AudioSettings.StreamingQuality.DATA_SAVER -> AudioQuality.LOW
        }

        Log.d(
            "LYRA_STREAM",
            "extractorStart videoId=$videoId quality=$requestedQuality " +
                "hls=false sabr=false boundedRange=false"
        )

        val stream = extractor.extract(
            videoId = videoId,
            hints = hints,
            audioQuality = requestedQuality,
        ) ?: error("InnerTubeX non ha trovato uno stream audio utilizzabile")

        val loudnessDb = if (AudioSettings.normalizationEnabled.value) {
            resolveLoudnessDb(videoId)
        } else {
            null
        }

        /*
         * Non logghiamo mai URL firmato, header o token: sono dati sensibili
         * e possono contenere parametri di sessione/antibot.
         */
        Log.d(
            "LYRA_STREAM",
            "extracted videoId=$videoId client=${stream.clientName} " +
                "profile=${stream.profileId} itag=${stream.itag} " +
                "mime=${stream.mimeType} bitrate=${stream.bitrate} " +
                "headers=${stream.headers.size} " +
                "boundedRange=${stream.requireBoundedRange} " +
                "rangeChunks=${stream.useRangeChunks} " +
                "sabr=${stream.sabrBootstrap != null} " +
                "expires=${stream.expiresAt != null}"
        )

        check(stream.sabrBootstrap == null) {
            "Il resolver ha restituito SABR durante il test direct/cipher"
        }

        check(!stream.requireBoundedRange) {
            "Il resolver ha restituito uno stream bounded-range non supportato in fase 2B"
        }

        check(
            stream.audioUrl.startsWith("https://") ||
                stream.audioUrl.startsWith("http://")
        ) {
            "Schema stream non supportato in fase 2B"
        }

        if (stream.headers.isNotEmpty()) {
            Log.w(
                "LYRA_STREAM",
                "selectedStreamRequiresHeaders count=${stream.headers.size}; " +
                    "fase 2B prova prima il percorso Media3 standard"
            )
        }

        return PlaybackSource(
            videoId = videoId,
            url = stream.audioUrl,
            mimeType = stream.mimeType,
            bitrate = stream.bitrate,
            clientName = stream.clientName,
            expiresInSeconds = null,
            loudnessDb = loudnessDb,
        )
    }

    private suspend fun resolveLoudnessDb(videoId: String): Double? {
        return runCatching {
            val response = YouTube.player(
                videoId = videoId,
                client = YouTubeClient.WEB_REMIX,
            ).getOrNull() ?: return@runCatching null

            response.playerConfig?.audioConfig?.loudnessDb
                ?: response.playerConfig?.audioConfig?.perceptualLoudnessDb
                ?: response.streamingData?.adaptiveFormats
                    ?.firstOrNull { it.isAudio && it.loudnessDb != null }
                    ?.loudnessDb
        }.onFailure { error ->
            Log.w(
                "LYRA_AUDIO",
                "loudnessLookupFailed videoId=$videoId message=${error.message}"
            )
        }.getOrNull().also { loudness ->
            Log.d(
                "LYRA_AUDIO",
                "loudnessResolved videoId=$videoId available=${loudness != null}"
            )
        }
    }

    /**
     * YouTube/InnerTube puo' ricreare il proprio trasporto (ad esempio quando
     * cambia il proxy). L'extractor deve quindi usare sempre la stessa
     * generazione di InnerTubeX e HttpClient.
     */
    private fun getExtractor(): StreamExtractor {
        val transport = YouTube.extractionTransport()
        val current = cachedExtractor

        if (
            current != null &&
            cachedTransportGeneration == transport.generation
        ) {
            return current
        }

        return synchronized(this) {
            val latestTransport = YouTube.extractionTransport()
            val latestCached = cachedExtractor

            if (
                latestCached != null &&
                cachedTransportGeneration == latestTransport.generation
            ) {
                latestCached
            } else {
                val configStore = RemotePlayerConfigStore(
                    httpClient = latestTransport.httpClient,
                    repository = PlayerConfigRepository.disabled(),
                )

                val cipherService = YouTubeCipherService(
                    httpClient = latestTransport.httpClient,
                    remotePlayerConfigStore = configStore,
                )

                InnerTubeExtractor(
                    configParser = YtConfigParserImpl(
                        httpClient = latestTransport.httpClient,
                        innerTube = latestTransport.innerTube,
                        remotePlayerConfigStore = configStore,
                    ),
                    cipherService = cipherService,
                    innerTube = latestTransport.innerTube,
                ).also { newExtractor ->
                    cachedExtractor = newExtractor
                    cachedTransportGeneration = latestTransport.generation

                    Log.d(
                        "LYRA_STREAM",
                        "extractorCreated generation=${latestTransport.generation}"
                    )
                }
            }
        }
    }
}

package app.lyra.music.playback

import app.lyra.music.core.model.Song
import app.lyra.music.genre.GenreClassifierClient
import app.lyra.music.genre.VerifiedGenrePlaylistClient
import app.lyra.music.diagnostics.LyraLog as Log
import app.lyra.music.personalization.AutomaticVarietyPolicy
import app.lyra.music.personalization.RecommendationEngine
import app.lyra.music.personalization.PersonalizationSettings
import app.lyra.music.youtube.repository.AutomaticPlaylistCatalog
import app.lyra.music.youtube.repository.AutomaticPlaylistRepository
import com.metrolist.innertube.models.WatchEndpoint
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.random.Random

/** Stato process-local della radio automatica e del ranking personalizzato. */
object AutomaticPlaylistManager {
    data class StartResult(
        val title: String,
        val songs: List<Song>,
    )

    data class FeedbackResult(
        val earlySkip: Boolean,
        val consecutiveEarlySkips: Int,
        val strongIntentShift: Boolean,
    )

    private data class Session(
        var title: String,
        var contextTag: String?,
        val sessionSalt: Long,
        var endpoint: WatchEndpoint,
        var continuation: String?,
        val seenIds: MutableSet<String>,
        var tailSeed: Song,
        var secondaryEndpoint: WatchEndpoint? = null,
        var secondaryContinuation: String? = null,
        var secondaryTailSeed: Song? = null,
        var balancedPop: Boolean = false,
        var consecutiveEarlySkips: Int = 0,
        val skippedArtists: MutableSet<String> = mutableSetOf(),
        val positiveArtists: MutableSet<String> = mutableSetOf(),
        var intentShiftCount: Int = 0,
        val strictGenre: String? = null,
    )

    private val repository = AutomaticPlaylistRepository()
    private val mutex = Mutex()

    @Volatile private var session: Session? = null
    @Volatile private var knownSongs: List<Song> = emptyList()

    fun isActive(): Boolean = session != null
    fun currentSongs(): List<Song> = knownSongs
    fun currentTitle(): String? = session?.title
    @Synchronized
    fun skippedArtistKeys(): Set<String> = session?.skippedArtists?.toSet().orEmpty()

    @Synchronized
    fun clear() {
        session = null
        knownSongs = emptyList()
    }

    suspend fun startFromSeed(
        seed: Song,
        title: String = "Radio: ${seed.title}",
        explicitContextTag: String? = null,
    ): StartResult = mutex.withLock {
        val batch = repository.fromSeed(seed)
        val tag = explicitContextTag ?: ListeningProfileStore.inferContextTag(title)
        val presetStart = explicitContextTag?.let(AutomaticPlaylistCatalog::preset) != null
        val salt = System.currentTimeMillis() xor System.nanoTime()
        val ranked = RecommendationEngine.rank(
            candidates = if (presetStart) listOf(seed) + batch.songs else batch.songs,
            context = RecommendationEngine.SessionContext(
                title = title,
                contextTag = tag,
                recentSongs = if (presetStart) emptyList() else listOf(seed),
                sessionSalt = salt,
            ),
        )
        val proposedQueue = if (presetStart) {
            AutomaticVarietyPolicy.diversifyRanked(
                ranked = ranked,
                recentProposalIds = if (PersonalizationSettings.avoidRepeats.value) {
                    AutomaticPlaylistVarietyStore.recentProposals(requireNotNull(tag))
                } else emptySet(),
                recentOpeningIds = if (PersonalizationSettings.avoidRepeats.value) {
                    AutomaticPlaylistVarietyStore.recentOpeners(requireNotNull(tag))
                } else emptySet(),
                salt = salt,
            )
        } else {
            (listOf(seed) + ranked).distinctBy { it.id }
        }
        val queue = if (presetStart) GenreClassifierClient.filterKnownIncompatible(tag, proposedQueue)
                    else proposedQueue
        if (queue.isEmpty()) throw IllegalStateException("Nessun brano idoneo per questa radio")
        session = Session(
            title = title,
            contextTag = tag,
            sessionSalt = salt,
            endpoint = batch.endpoint,
            continuation = batch.continuation,
            seenIds = queue.mapTo(mutableSetOf()) { it.id },
            tailSeed = queue.lastOrNull() ?: seed,
        )
        knownSongs = queue
        if (presetStart) {
            AutomaticPlaylistVarietyStore.remember(requireNotNull(tag), listOf(seed.id), queue)
            Log.d("LYRA_AUTO_VARIETY", "preset=$tag seed=${seed.id} opener=${queue.first().id} items=${queue.size}")
        }
        StartResult(title, queue)
    }

    suspend fun startFromPreset(presetId: String): StartResult {
        val preset = AutomaticPlaylistCatalog.preset(presetId)
            ?: throw IllegalArgumentException("Preset automatico sconosciuto: $presetId")

        // Track-level evidence is the only source of strict genre playlists.
        // Never fill a confirmed techno request with unrelated YTM Automix.
        if (VerifiedGenrePlaylistClient.enabled(presetId)) {
            val verified = VerifiedGenrePlaylistClient.fetch(presetId, limit = 40)
            return mutex.withLock {
                val title = "Playlist Automatica · ${preset.title}"
                val salt = System.currentTimeMillis() xor System.nanoTime()
                val ranked = RecommendationEngine.rank(
                    candidates = verified,
                    context = RecommendationEngine.SessionContext(
                        title = title, contextTag = preset.id, sessionSalt = salt,
                    ),
                )
                val queue = AutomaticVarietyPolicy.diversifyRanked(
                    ranked = ranked,
                    recentProposalIds = if (PersonalizationSettings.avoidRepeats.value)
                        AutomaticPlaylistVarietyStore.recentProposals(preset.id) else emptySet(),
                    salt = salt,
                )
                if (queue.isEmpty()) throw IllegalStateException(
                    "Nessun brano ${preset.title} verificato disponibile"
                )
                session = Session(
                    title = title, contextTag = preset.id, sessionSalt = salt,
                    endpoint = WatchEndpoint(videoId = queue.last().id),
                    continuation = null,
                    seenIds = queue.mapTo(mutableSetOf()) { it.id },
                    tailSeed = queue.last(), strictGenre = preset.id,
                )
                knownSongs = queue
                AutomaticPlaylistVarietyStore.remember(preset.id, emptyList(), queue)
                Log.d("LYRA_AUTO_VARIETY", "strictGenre=${preset.id} verifiedItems=${queue.size}")
                StartResult(title, queue)
            }
        }

        if (presetId != POP_PRESET_ID) {
            val recentSeeds = if (PersonalizationSettings.avoidRepeats.value) {
                AutomaticPlaylistVarietyStore.recentSeeds(preset.id)
            } else emptySet()
            val recentProposals = if (PersonalizationSettings.avoidRepeats.value) {
                AutomaticPlaylistVarietyStore.recentProposals(preset.id)
            } else emptySet()
            val learnedSeed = AutomaticVarietyPolicy.chooseSeed(
                rankedCandidates = ListeningProfileStore.seedCandidatesForTag(preset.id),
                recentSeedIds = recentSeeds,
                recentProposalIds = recentProposals,
            )
            // Ogni tanto esploriamo YouTube anche quando conosciamo gia' un seed.
            // Se l'unico seed noto e' appena stato usato, non lo forziamo ancora.
            val tryRemoteFirst = learnedSeed == null || learnedSeed.id in recentSeeds || Random.nextInt(4) == 0
            val searchedSeed = if (tryRemoteFirst) runCatching {
                repository.findSeed(presetId, recentSeeds, emptySet())
            }.getOrNull() else null
            val initialSeed = searchedSeed ?: learnedSeed
                ?: repository.findSeed(presetId, recentSeeds)
            Log.d("LYRA_AUTO_VARIETY", "preset=$presetId seedSource=${if (searchedSeed != null) "search" else "learned"} recentSeeds=${recentSeeds.size}")
            val first = runCatching {
                startFromSeed(
                    seed = initialSeed,
                    title = "Playlist Automatica · ${preset.title}",
                    explicitContextTag = preset.id,
                )
            }.getOrNull()
            if (first != null) return first
            // Fallback ad altra fonte dello STESSO genere se il seed scelto
            // non produce un Watch Next valido.
            val alternativeSeed = repository.findSeed(
                presetId, recentSeeds + initialSeed.id,
            )
            return startFromSeed(
                seed = alternativeSeed,
                title = "Playlist Automatica · ${preset.title}",
                explicitContextTag = preset.id,
            )
        }

        return mutex.withLock {
            val mixed = repository.balancedPopBatch()
            val title = "Playlist Automatica · ${preset.title}"
            val salt = System.currentTimeMillis() xor System.nanoTime()
            val italianRanking = RecommendationEngine.rank(
                candidates = listOf(mixed.italianSeed) + mixed.italian.songs,
                context = RecommendationEngine.SessionContext(
                    title = title,
                    contextTag = preset.id,
                    recentSongs = emptyList(),
                    sessionSalt = salt,
                ),
            )
            val internationalRanking = RecommendationEngine.rank(
                candidates = listOf(mixed.internationalSeed) + mixed.international.songs,
                context = RecommendationEngine.SessionContext(
                    title = title,
                    contextTag = preset.id,
                    recentSongs = emptyList(),
                    sessionSalt = salt + 1L,
                ),
            )
            // Manteniamo l'alternanza Italia/internazionale, diversificando
            // ciascuna delle due liste prima di combinarle.
            val recentlyProposed = if (PersonalizationSettings.avoidRepeats.value) {
                AutomaticPlaylistVarietyStore.recentProposals(preset.id)
            } else emptySet()
            val italian = AutomaticVarietyPolicy.diversifyRanked(
                ranked = italianRanking,
                recentProposalIds = recentlyProposed,
                recentOpeningIds = if (PersonalizationSettings.avoidRepeats.value) {
                    AutomaticPlaylistVarietyStore.recentOpeners(preset.id)
                } else emptySet(),
                salt = salt,
            )
            val international = AutomaticVarietyPolicy.diversifyRanked(
                ranked = internationalRanking,
                recentProposalIds = recentlyProposed,
                recentOpeningIds = if (PersonalizationSettings.avoidRepeats.value) {
                    AutomaticPlaylistVarietyStore.recentOpeners(preset.id)
                } else emptySet(),
                salt = salt + 1L,
            )
            val queue = mergeAlternating(
                first = if (salt % 2L == 0L) italian else international,
                second = if (salt % 2L == 0L) international else italian,
            )
            session = Session(
                title = title,
                contextTag = preset.id,
                sessionSalt = salt,
                endpoint = mixed.italian.endpoint,
                continuation = mixed.italian.continuation,
                seenIds = queue.mapTo(mutableSetOf()) { it.id },
                tailSeed = italian.lastOrNull() ?: mixed.italianSeed,
                secondaryEndpoint = mixed.international.endpoint,
                secondaryContinuation = mixed.international.continuation,
                secondaryTailSeed = international.lastOrNull() ?: mixed.internationalSeed,
                balancedPop = true,
            )
            knownSongs = queue
            AutomaticPlaylistVarietyStore.remember(preset.id, listOf(mixed.italianSeed.id, mixed.internationalSeed.id), queue)
            Log.d("LYRA_AUTO_VARIETY", "preset=pop opener=${queue.firstOrNull()?.id} items=${queue.size}")
            StartResult(title, queue)
        }
    }

    suspend fun resumeFromHistory(
        title: String,
        songs: List<Song>,
    ): StartResult = mutex.withLock {
        val clean = songs.distinctBy { it.id }
        val tail = clean.lastOrNull()
            ?: throw IllegalArgumentException("Cronologia automatica senza brani")
        val batch = repository.fromSeed(tail)
        val tag = ListeningProfileStore.inferContextTag(title)
        val salt = System.currentTimeMillis()
        val ranked = RecommendationEngine.rank(
            candidates = batch.songs,
            context = RecommendationEngine.SessionContext(
                title = title,
                contextTag = tag,
                recentSongs = clean,
                sessionSalt = salt,
            ),
        )
        val queue = (clean + ranked).distinctBy { it.id }
        session = Session(
            title = title,
            contextTag = tag,
            sessionSalt = salt,
            endpoint = batch.endpoint,
            continuation = batch.continuation,
            seenIds = queue.mapTo(mutableSetOf()) { it.id },
            tailSeed = queue.lastOrNull() ?: tail,
        )
        knownSongs = queue
        StartResult(title, queue)
    }

    /** Memoria breve: due skip precoci consecutivi diventano un cambio di intenzione. */
    @Synchronized
    fun recordPlaybackOutcome(
        song: Song,
        listenedMs: Long,
        durationMs: Long,
        completed: Boolean,
        manualSkip: Boolean,
    ): FeedbackResult? {
        if (!PersonalizationSettings.enabled.value || !PersonalizationSettings.learnFromSkips.value) return null
        val current = session ?: return null
        val safeDuration = durationMs.coerceAtLeast(0L)
        val safeListened = listenedMs.coerceAtLeast(0L)
        val fraction = if (safeDuration > 0L) {
            (safeListened.toDouble() / safeDuration.toDouble()).coerceIn(0.0, 1.0)
        } else 0.0
        val earlySkip = manualSkip && safeDuration > 0L && fraction < 0.30 && safeListened < 45_000L

        val artistKey = RecommendationEngine.artistKey(song.artist)
        if (earlySkip) {
            current.consecutiveEarlySkips = (current.consecutiveEarlySkips + 1).coerceAtMost(4)
            artistKey.takeIf { it.isNotBlank() }?.let { key ->
                current.skippedArtists.remove(key)
                current.skippedArtists.add(key)

                while (current.skippedArtists.size > 6) {
                    current.skippedArtists.remove(
                        current.skippedArtists.first()
                    )
                }
                current.positiveArtists.remove(key)
            }
        } else if (completed || fraction >= 0.70) {
            current.consecutiveEarlySkips = 0
            artistKey.takeIf { it.isNotBlank() }?.let { key ->
                current.positiveArtists.add(key)
                current.skippedArtists.remove(key)
            }
        } else if (fraction >= 0.55) {
            current.consecutiveEarlySkips = 0
        }

        return FeedbackResult(
            earlySkip = earlySkip,
            consecutiveEarlySkips = current.consecutiveEarlySkips,
            strongIntentShift = earlySkip && current.consecutiveEarlySkips >= 2,
        )
    }

    /** Riordina subito la coda futura dopo il primo segnale negativo. */
    @Synchronized
    fun rerankFutureAfterFeedback(currentSongId: String): List<Song> {
        val current = session ?: return knownSongs
        val index = knownSongs.indexOfFirst { it.id == currentSongId }
        if (index < 0 || index >= knownSongs.lastIndex) return knownSongs
        val prefix = knownSongs.take(index + 1)
        val tail = knownSongs.drop(index + 1)
        val ranked = RecommendationEngine.rank(
            candidates = tail,
            context = RecommendationEngine.SessionContext(
                title = current.title,
                contextTag = current.contextTag,
                recentSongs = prefix.takeLast(18),
                sessionSalt = current.sessionSalt + current.intentShiftCount,
                avoidArtists = current.skippedArtists,
                positiveArtists = current.positiveArtists,
                diversificationLevel = current.consecutiveEarlySkips,
            ),
        )
        knownSongs = (prefix + ranked).distinctBy { it.id }
        return knownSongs
    }

    /**
     * Dopo due skip precoci consecutivi abbandona il pool corrente. Se la sessione
     * nasce da un preset, mantiene il genere scelto e cambia sorgente; per una radio libera
     * utilizza un brano di artista differente e apre un nuovo Watch Next.
     */
    suspend fun pivotAfterRepeatedSkips(currentSongId: String): List<Song> = mutex.withLock {
        val current = session ?: return@withLock emptyList()
        if (current.consecutiveEarlySkips < 2) return@withLock emptyList()

        if (current.strictGenre != null) {
            val verified = VerifiedGenrePlaylistClient.fetch(
                current.strictGenre, current.seenIds + currentSongId, limit = 20,
            ).filter { RecommendationEngine.artistKey(it.artist) !in current.skippedArtists }
            if (verified.isEmpty()) return@withLock emptyList()
            val index = knownSongs.indexOfFirst { it.id == currentSongId }
            if (index < 0) return@withLock emptyList()
            val fresh = verified.filter { current.seenIds.add(it.id) }
            if (fresh.isEmpty()) return@withLock emptyList()
            current.tailSeed = fresh.last()
            current.consecutiveEarlySkips = 0
            current.intentShiftCount += 1
            knownSongs = (knownSongs.take(index) + fresh).distinctBy { it.id }
            AutomaticPlaylistVarietyStore.remember(
                current.strictGenre, emptyList(), fresh, recordOpening = false,
            )
            return@withLock fresh
        }

        val currentIndex = knownSongs.indexOfFirst { it.id == currentSongId }
        if (currentIndex < 0) return@withLock emptyList()
        val prefix = knownSongs.take(currentIndex)
        val avoided = current.skippedArtists.toSet()

        val explicitPreset = current.contextTag?.takeIf { AutomaticPlaylistCatalog.preset(it) != null }
        val (newContext, seed) = if (explicitPreset != null) {
            // Gli skip non autorizzano a passare da House a Techno o Pop:
            // cerchiamo invece un'altra sorgente dello STESSO preset.
            val candidate = repository.findSeed(
                presetId = explicitPreset,
                excludingIds = current.seenIds + currentSongId,
                avoidingArtists = avoided,
            )
            explicitPreset to candidate
        } else {
            val candidate = knownSongs.drop(currentIndex)
                .firstOrNull { RecommendationEngine.artistKey(it.artist) !in avoided && it.id != currentSongId }
                ?: current.tailSeed
            null to candidate
        }

        val batch = repository.fromSeed(seed)
        val ranked = RecommendationEngine.rank(
            candidates = batch.songs,
            context = RecommendationEngine.SessionContext(
                title = current.title,
                contextTag = newContext,
                recentSongs = prefix.takeLast(18),
                sessionSalt = current.sessionSalt + current.intentShiftCount + 1L,
                avoidArtists = avoided,
                positiveArtists = current.positiveArtists,
                diversificationLevel = 3,
            ),
        )
        val priorSeen = current.seenIds.toSet()
        // Un seed restituito dal remoto puo' essere dell'artista skippato:
        // non deve essere forzato come primo brano della nuova coda.
        val ordered = if (RecommendationEngine.artistKey(seed.artist) in avoided) {
            ranked + seed
        } else {
            listOf(seed) + ranked
        }
        val fresh = GenreClassifierClient.filterKnownIncompatible(newContext, ordered
            .distinctBy { it.id }
            .filterNot { song -> song.id in priorSeen || prefix.any { it.id == song.id } })
        if (fresh.isEmpty()) return@withLock emptyList()

        current.contextTag = newContext
        current.endpoint = batch.endpoint
        current.continuation = batch.continuation
        current.secondaryEndpoint = null
        current.secondaryContinuation = null
        current.secondaryTailSeed = null
        current.balancedPop = false
        current.seenIds.addAll(fresh.map { it.id })
        current.tailSeed = fresh.last()
        current.consecutiveEarlySkips = 0
        current.intentShiftCount += 1
        knownSongs = prefix + fresh
        explicitPreset?.let {
            AutomaticPlaylistVarietyStore.remember(it, listOf(seed.id), fresh, recordOpening = false)
        }
        fresh
    }

    suspend fun loadMore(): List<Song> = mutex.withLock {
        val current = session ?: return@withLock emptyList()

        if (current.strictGenre != null) {
            // A strict session never falls back to YTM Watch Next.
            val verified = VerifiedGenrePlaylistClient.fetch(
                current.strictGenre, current.seenIds, limit = 20,
            )
            val fresh = verified.filter { current.seenIds.add(it.id) }
            if (fresh.isNotEmpty()) {
                current.tailSeed = fresh.last()
                knownSongs = knownSongs + fresh
                AutomaticPlaylistVarietyStore.remember(
                    current.strictGenre, emptyList(), fresh, recordOpening = false,
                )
            }
            return@withLock fresh
        }

        if (current.balancedPop) {
            val firstBatch = runCatching {
                if (!current.continuation.isNullOrBlank()) {
                    repository.continueFrom(current.endpoint, requireNotNull(current.continuation))
                } else {
                    repository.fromSeed(current.tailSeed)
                }
            }.getOrNull()
            val secondEndpoint = current.secondaryEndpoint
            val secondSeed = current.secondaryTailSeed
            val secondBatch = runCatching {
                when {
                    secondEndpoint != null && !current.secondaryContinuation.isNullOrBlank() ->
                        repository.continueFrom(secondEndpoint, requireNotNull(current.secondaryContinuation))
                    secondSeed != null -> repository.fromSeed(secondSeed)
                    else -> null
                }
            }.getOrNull()

            if (firstBatch == null && secondBatch == null) {
                throw IllegalStateException("Impossibile continuare il mix Pop")
            }
            firstBatch?.let {
                current.endpoint = it.endpoint
                current.continuation = it.continuation
            }
            secondBatch?.let {
                current.secondaryEndpoint = it.endpoint
                current.secondaryContinuation = it.continuation
            }

            val baseContext = RecommendationEngine.SessionContext(
                title = current.title,
                contextTag = current.contextTag,
                recentSongs = knownSongs.takeLast(18),
                sessionSalt = current.sessionSalt + current.intentShiftCount,
                avoidArtists = current.skippedArtists,
                positiveArtists = current.positiveArtists,
                diversificationLevel = current.consecutiveEarlySkips,
            )
            val firstRanked = RecommendationEngine.rank(
                candidates = firstBatch?.songs.orEmpty().filterNot { it.id in current.seenIds },
                context = baseContext,
            )
            val secondRanked = RecommendationEngine.rank(
                candidates = secondBatch?.songs.orEmpty().filterNot { it.id in current.seenIds },
                context = baseContext.copy(sessionSalt = baseContext.sessionSalt + 1L),
            )
            val recentProposals = if (PersonalizationSettings.avoidRepeats.value) {
                AutomaticPlaylistVarietyStore.recentProposals(POP_PRESET_ID)
            } else emptySet()
            val firstDiverse = AutomaticVarietyPolicy.diversifyRanked(
                ranked = firstRanked,
                recentProposalIds = recentProposals,
                previousArtistKeys = knownSongs.takeLast(2).map { AutomaticVarietyPolicy.artistKey(it.artist) },
                salt = current.sessionSalt xor knownSongs.size.toLong(),
            )
            val secondDiverse = AutomaticVarietyPolicy.diversifyRanked(
                ranked = secondRanked,
                recentProposalIds = recentProposals,
                salt = current.sessionSalt xor (knownSongs.size + 1).toLong(),
            )
            val fresh = mergeAlternating(firstDiverse, secondDiverse)
                .filter { current.seenIds.add(it.id) }
            firstDiverse.lastOrNull()?.let { current.tailSeed = it }
            secondDiverse.lastOrNull()?.let { current.secondaryTailSeed = it }
            if (fresh.isNotEmpty()) {
                knownSongs = knownSongs + fresh
                AutomaticPlaylistVarietyStore.remember(POP_PRESET_ID, emptyList(), fresh, recordOpening = false)
            }
            return@withLock fresh
        }

        val batch = if (!current.continuation.isNullOrBlank()) {
            repository.continueFrom(
                endpoint = current.endpoint,
                continuation = requireNotNull(current.continuation),
            )
        } else {
            repository.fromSeed(current.tailSeed)
        }

        current.endpoint = batch.endpoint
        current.continuation = batch.continuation

        val candidates = batch.songs.filterNot { it.id in current.seenIds }
        val ranked = RecommendationEngine.rank(
            candidates = candidates,
            context = RecommendationEngine.SessionContext(
                title = current.title,
                contextTag = current.contextTag,
                recentSongs = knownSongs.takeLast(18),
                sessionSalt = current.sessionSalt + current.intentShiftCount,
                avoidArtists = current.skippedArtists,
                positiveArtists = current.positiveArtists,
                diversificationLevel = current.consecutiveEarlySkips,
            ),
        )
        val diversified = if (current.contextTag?.let(AutomaticPlaylistCatalog::preset) != null) {
            AutomaticVarietyPolicy.diversifyRanked(
                ranked = ranked,
                recentProposalIds = if (PersonalizationSettings.avoidRepeats.value) {
                    AutomaticPlaylistVarietyStore.recentProposals(requireNotNull(current.contextTag))
                } else emptySet(),
                previousArtistKeys = knownSongs.takeLast(2).map { AutomaticVarietyPolicy.artistKey(it.artist) },
                salt = current.sessionSalt xor knownSongs.size.toLong(),
            )
        } else ranked
        val fresh = GenreClassifierClient.filterKnownIncompatible(current.contextTag, diversified)
            .filter { current.seenIds.add(it.id) }
        if (fresh.isNotEmpty()) {
            current.tailSeed = fresh.last()
            knownSongs = knownSongs + fresh
            current.contextTag?.takeIf { AutomaticPlaylistCatalog.preset(it) != null }?.let { tag ->
                AutomaticPlaylistVarietyStore.remember(tag, emptyList(), fresh, recordOpening = false)
            }
        }
        fresh
    }

    private fun mergeAlternating(first: List<Song>, second: List<Song>): List<Song> {
        if (first.isEmpty()) return second.distinctBy { it.id }
        if (second.isEmpty()) return first.distinctBy { it.id }
        val merged = ArrayList<Song>(first.size + second.size)
        val seen = mutableSetOf<String>()
        val max = maxOf(first.size, second.size)
        for (index in 0 until max) {
            first.getOrNull(index)?.takeIf { seen.add(it.id) }?.let(merged::add)
            second.getOrNull(index)?.takeIf { seen.add(it.id) }?.let(merged::add)
        }
        return merged
    }

    private const val POP_PRESET_ID = "pop"

}

package app.lyra.music.playback

import app.lyra.music.core.model.Song
import app.lyra.music.personalization.NoVerifiedCandidatesException
import app.lyra.music.personalization.ServerPlaylistClient
import app.lyra.music.personalization.ServerQueuePolicy
import kotlinx.coroutines.CancellationException
import app.lyra.music.genre.GenreClassifierClient
import app.lyra.music.genre.GenreClassifierSettings
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
        /** Verified-only sessions never fall through to the legacy YTM radio. */
        val strictGenre: String? = null,
    )

    val source=kotlinx.coroutines.flow.MutableStateFlow("Locale")
    /** Visible feedback for automatic playlists, including a short verified catalog. */
    val notice=kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    private val repository = AutomaticPlaylistRepository()
    private val mutex = Mutex()

    private val generation=java.util.concurrent.atomic.AtomicLong()
    @Volatile private var activeMediaId: String? = null
    @Volatile private var remote: ServerPlaylistClient? = null
    @Volatile private var session: Session? = null
    @Volatile private var knownSongs: List<Song> = emptyList()

    fun isActive(): Boolean = session != null
    fun currentSongs(): List<Song> = knownSongs
    fun currentTitle(): String? = session?.title
    @Synchronized
    fun skippedArtistKeys(): Set<String> = session?.skippedArtists?.toSet().orEmpty()

    @Synchronized
    fun clear() {
        activeMediaId=null
        generation.incrementAndGet()
        remote?.invalidate()
        remote = null
        source.value="Locale"
        notice.value=null
        session = null
        knownSongs = emptyList()
    }

    fun onPlaybackTransition(id: String) { activeMediaId=id }
    fun hasServerIntent(): Boolean = remote != null
    fun serverOwnsQueue(): Boolean = remote?.authoritative == true
    fun hasArtistConstraint(): Boolean = remote?.kind=="artist"
    private fun serverLabel()=when(remote?.kind) { "context" -> "Server · contesto appreso"; "artist" -> "Server · artista richiesto"; else -> "Server · genere verificato" }
    fun serverBinding(): Any? = remote

    private val serverGenres = mapOf("pop" to "pop", "rock" to "rock", "rap" to "hip hop",
        "dance" to "dance", "house" to "house", "techno" to "techno", "edm" to "electronic dance music|electronic",
        "rnb" to "rhythm and blues|soul", "indie" to "indie rock|indie pop",
        "metal" to "metal", "jazz" to "jazz", "classica" to "classical")

    /** Restore binding only for the exact saved automatic queue. Manual queues never adopt it. */
    @Synchronized fun restoreServerBinding(title: String, songs: List<Song>) {
        if (session != null || songs.isEmpty() || !PersonalizationSettings.enabled.value) return
        val saved = ServerPlaylistClient.savedIntent() ?: return
        if (saved.third != title) return
        remote=ServerPlaylistClient(saved.first,saved.second,title,restore=true)
        installServerSession(title,songs,ListeningProfileStore.inferContextTag(title))
        source.value="Buffer locale · riallineamento"
    }

    private fun installServerSession(title: String, songs: List<Song>, tag: String?) {
        session=Session(title,tag,System.nanoTime(),WatchEndpoint(videoId=songs.last().id),null,
            songs.mapTo(mutableSetOf()) { it.id },songs.last())
        knownSongs=songs
        source.value=if (remote?.authoritative==true) serverLabel() else "Locale"
    }

    suspend fun prepareServerBoundary(currentId: String, queue: List<Song>, revision: String, repeatOff: Boolean) {
        val client=remote ?: return
        client.prepare(currentId,queue.map { it.id },revision,repeatOff)
        if (remote===client && !client.authoritative) source.value="Locale · riallineamento in attesa"
    }

    suspend fun serverBoundary(endedId: String?, revision: String, repeatOff: Boolean): List<Song>? {
        val client=remote ?: return null
        return client.boundary(endedId,revision,repeatOff).takeIf { remote === client }
    }

    @Synchronized fun acceptServerTail(binding: Any?, currentId: String, queue: List<Song>, future: List<Song>): List<Song>? {
        val client=remote ?: return null
        if (binding !== client || session == null || queue.none { it.id==currentId }) return null
        val byId=(queue+future).associateBy { it.id }
        val updated=ServerQueuePolicy.replaceFuture(queue.map { it.id },currentId,future.map { it.id }).map { byId.getValue(it) }
        knownSongs=updated
        session!!.seenIds.addAll(future.map { it.id });session!!.tailSeed=updated.last()
        client.applied()
        source.value=serverLabel()
        return updated
    }

    suspend fun startFromArtist(artist: String): StartResult {
        val name=artist.trim().take(120)
        require(name.isNotBlank())
        val title="Playlist Automatica · $name"
        if (!PersonalizationSettings.enabled.value) {
            val expected=generation.get()
            val songs=repository.artistSongs(name)
            if (generation.get()!=expected) throw CancellationException("Playlist cambiata")
            installServerSession(title,songs,null)
            return StartResult(title,songs)
        }
        val client=ServerPlaylistClient("artist",name,title);remote=client
        try {
            val songs=client.next(emptySet())
            if (remote !== client) throw CancellationException("Playlist cambiata")
            installServerSession(title,songs,null)
            return StartResult(title,songs)
        } catch (e: CancellationException) { throw e }
        catch (e: NoVerifiedCandidatesException) { client.localFallback() }
        catch (_: Exception) { }
        run {
            // Strict local artist fallback; a generic artist radio would drift to other artists.
            val songs=repository.artistSongs(name)
            if (remote !== client) throw CancellationException("Playlist cambiata")
            installServerSession(title,songs,null)
            return StartResult(title,songs)
        }
    }

    suspend fun startFromSeed(
        seed: Song,
        title: String = "Radio: ${seed.title}",
        explicitContextTag: String? = null,
    ): StartResult = mutex.withLock {
        val expectedGeneration=generation.get()
        val expectedRemote=remote
        val batch = repository.fromSeed(seed)
        check(remote === expectedRemote) { "Playlist cambiata" }
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
        if (generation.get()!=expectedGeneration) throw CancellationException("Playlist cambiata")
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
        val expectedGeneration=generation.get()
        val preset = AutomaticPlaylistCatalog.preset(presetId)
            ?: throw IllegalArgumentException("Preset automatico sconosciuto: $presetId")

        // Music genres are verified per recording. Moods are different:
        // prefer track-level mood evidence, but retain the existing context radio
        // if no independent evidence is available for that atmosphere.
        if (VerifiedGenrePlaylistClient.supported(presetId)) {
            val isMood = VerifiedGenrePlaylistClient.isMood(presetId)
            if (!VerifiedGenrePlaylistClient.enabled(presetId)) {
                if (!isMood) {
                    notice.value = "Classificatore non configurato: verifica connessione e token Lyra."
                    throw IllegalStateException(
                        "Classificatore Lyra non configurato. Controlla connessione e token nelle Impostazioni."
                    )
                }
                notice.value = "Ricerca delle atmosfere non configurata: utilizzo la radio contestuale."
            } else {
                val selected = try {
                    VerifiedGenrePlaylistClient.fetch(presetId, limit = 40)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    if (!isMood) throw error
                    notice.value = "Nessun brano con atmosfera verificata: uso la radio contestuale."
                    Log.w(
                        "LYRA_AUTO_PLAYLIST",
                        "moodVerifiedUnavailable preset=$presetId message=${error.message}",
                    )
                    null
                }
                if (selected != null) {
                    if (generation.get()!=expectedGeneration) throw CancellationException("Playlist cambiata")
                    return mutex.withLock {
                        if (generation.get()!=expectedGeneration) throw CancellationException("Playlist cambiata")
                        val title = "Playlist Automatica · ${preset.title}"
                        val salt = System.currentTimeMillis() xor System.nanoTime()
                        val ranked = RecommendationEngine.rank(
                            candidates = selected,
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
                        Log.d(
                            "LYRA_AUTO_PLAYLIST",
                            "verifiedStart preset=$presetId mood=$isMood serverItems=${selected.size} " +
                                "ranked=${ranked.size} playable=${queue.size} " +
                                "excludedByRanking=${selected.size - ranked.size}",
                        )
                        if (queue.isEmpty()) throw NoVerifiedCandidatesException()
                        notice.value = when (queue.size) {
                            1 -> "Playlist incompleta: soltanto 1 brano verificato per ${preset.title}."
                            in 2..4 -> "Catalogo ${preset.title} limitato: solo ${queue.size} brani verificati."
                            else -> null
                        }
                        remote?.invalidate()
                        remote = null
                        session = Session(
                            title = title, contextTag = preset.id, sessionSalt = salt,
                            endpoint = WatchEndpoint(videoId = queue.last().id),
                            continuation = null,
                            seenIds = queue.mapTo(mutableSetOf()) { it.id },
                            tailSeed = queue.last(), strictGenre = preset.id,
                        )
                        knownSongs = queue
                        source.value = if (isMood) "Server · atmosfera verificata"
                            else "Server · genere verificato"
                        AutomaticPlaylistVarietyStore.remember(preset.id, emptyList(), queue)
                        Log.d("LYRA_AUTO_VARIETY", "strictPreset=${preset.id} verifiedItems=${queue.size}")
                        StartResult(title, queue)
                    }
                }
            }
        }

        val client=if (!PersonalizationSettings.enabled.value) null else serverGenres[presetId]?.let {
            ServerPlaylistClient(if (it.contains('|')) "genres" else "genre",it,"Playlist Automatica · ${preset.title}")
        } ?: if (presetId in setOf("relax","energia","party","focus","romantica")) {
            ServerPlaylistClient("context",presetId,"Playlist Automatica · ${preset.title}")
        } else null
        remote=client
        if (client != null) {
            try {
                val songs=client.next(emptySet())
                if (remote !== client) throw CancellationException("Playlist cambiata")
                installServerSession("Playlist Automatica · ${preset.title}",songs,presetId)
                return StartResult("Playlist Automatica · ${preset.title}",songs)
            } catch (e: CancellationException) { throw e }
            catch (e: NoVerifiedCandidatesException) {
                if (client.kind!="context") { source.value="Catalogo da verificare";throw e }
                client.localFallback();source.value="Locale · contesto in apprendimento"
            }
            catch (_: Exception) {
                if (generation.get()!=expectedGeneration) throw CancellationException("Playlist cambiata")
                source.value="Locale · genere non verificato dal server"
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
            if (generation.get()!=expectedGeneration) throw CancellationException("Playlist cambiata")
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

        val strict = AutomaticPlaylistCatalog.presets.firstOrNull {
            title == "Playlist Automatica · ${it.title}" &&
                VerifiedGenrePlaylistClient.supported(it.id)
        }
        if (strict != null) {
            if (!VerifiedGenrePlaylistClient.enabled(strict.id)) {
                throw IllegalStateException("Classificatore Lyra non configurato per la ripresa della playlist.")
            }
            val eligible = VerifiedGenrePlaylistClient.fetch(strict.id, limit = 50)
            val verified = eligible.associateBy { it.id }
            val restored = clean.mapNotNull { verified[it.id] }.ifEmpty { eligible }
            if (restored.isEmpty()) throw NoVerifiedCandidatesException()
            remote?.invalidate()
            remote = null
            session = Session(
                title = title, contextTag = strict.id,
                sessionSalt = System.nanoTime(),
                endpoint = WatchEndpoint(videoId = restored.last().id),
                continuation = null,
                seenIds = restored.mapTo(mutableSetOf()) { it.id },
                tailSeed = restored.last(), strictGenre = strict.id,
            )
            knownSongs = restored
            source.value = "Server · genere verificato"
            notice.value = if (restored.size < 2)
                "Playlist incompleta: soltanto 1 brano verificato disponibile." else null
            return@withLock StartResult(title, restored)
        }
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
        if (manualSkip) remote?.feedbackChanged()
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
        if (current.consecutiveEarlySkips < 2 || hasArtistConstraint()) return@withLock emptyList()

        if (current.strictGenre != null) {
            val fresh = try {
                VerifiedGenrePlaylistClient.fetch(
                    current.strictGenre, current.seenIds + currentSongId, limit = 20,
                ).filter { RecommendationEngine.artistKey(it.artist) !in current.skippedArtists }
            } catch (e: CancellationException) { throw e }
              catch (_: Exception) { return@withLock emptyList() }
            val index = knownSongs.indexOfFirst { it.id == currentSongId }
            if (index < 0 || session !== current) return@withLock emptyList()
            val unique = fresh.filterNot { it.id in current.seenIds }
            if (unique.isEmpty()) return@withLock emptyList()
            current.seenIds.addAll(unique.map { it.id })
            current.tailSeed = unique.last()
            current.consecutiveEarlySkips = 0
            current.intentShiftCount += 1
            knownSongs = (knownSongs.take(index + 1) + unique).distinctBy { it.id }
            AutomaticPlaylistVarietyStore.remember(
                current.strictGenre, emptyList(), unique, recordOpening = false,
            )
            return@withLock unique
        }

        val currentIndex = knownSongs.indexOfFirst { it.id == currentSongId }
        if (currentIndex < 0) return@withLock emptyList()
        val prefix = knownSongs.take(currentIndex + 1)
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

        if (session !== current || serverOwnsQueue() || (activeMediaId!=null && activeMediaId!=currentSongId)) return@withLock emptyList()
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
            val next = try {
                VerifiedGenrePlaylistClient.fetch(
                    current.strictGenre, current.seenIds, limit = 20,
                )
            } catch (e: CancellationException) { throw e }
              catch (error: Exception) {
                source.value = "Server · generi non disponibili"
                notice.value = "Impossibile caricare altri brani del genere richiesto. Riprova con Next."
                Log.w(
                    "LYRA_AUTO_PLAYLIST",
                    "verifiedRefillFailed genre=${current.strictGenre} reason=${error.javaClass.simpleName} " +
                        "message=${error.message}",
                    error,
                )
                return@withLock emptyList()
            }
            if (session !== current) return@withLock emptyList()
            val fresh = next.filterNot { it.id in current.seenIds }
            if (fresh.isNotEmpty()) {
                current.seenIds.addAll(fresh.map { it.id })
                current.tailSeed = fresh.last()
                knownSongs = knownSongs + fresh
                AutomaticPlaylistVarietyStore.remember(
                    current.strictGenre, emptyList(), fresh, recordOpening = false,
                )
            }
            notice.value = if (fresh.isEmpty()) {
                "Nessun altro brano ${current.strictGenre} verificato nel catalogo. Riprova più tardi."
            } else null
            Log.d(
                "LYRA_AUTO_PLAYLIST",
                "verifiedRefill genre=${current.strictGenre} returned=${next.size} " +
                    "added=${fresh.size} total=${knownSongs.size}",
            )
            return@withLock fresh
        }

        val client=remote
        if (client?.authoritative == true) {
            try {
                val fresh=client.next(current.seenIds)
                if (session !== current || remote !== client) return@withLock emptyList()
                val unique=fresh.filterNot { it.id in current.seenIds }
                current.seenIds.addAll(unique.map { it.id })
                knownSongs=knownSongs+unique
                source.value=serverLabel()
                unique.lastOrNull()?.let { current.tailSeed=it }
                return@withLock unique
            } catch (e: CancellationException) { throw e }
            catch (e: NoVerifiedCandidatesException) {
                if (client.kind !in setOf("artist","context")) { source.value="Catalogo da verificare";return@withLock emptyList() }
                client.localFallback()
            }
            catch (_: Exception) { client.localFallback();source.value="Locale · server non disponibile" }
        }
        if (client?.kind == "artist") {
            val fresh=repository.artistSongs(client.value).filterNot { it.id in current.seenIds }
            if (session !== current || remote !== client || client.authoritative) return@withLock emptyList()
            current.seenIds.addAll(fresh.map { it.id })
            knownSongs=knownSongs+fresh
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
            if (session !== current || remote !== client || client?.authoritative==true) return@withLock emptyList()
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
                .filterNot { it.id in current.seenIds }
            firstDiverse.lastOrNull()?.let { current.tailSeed = it }
            secondDiverse.lastOrNull()?.let { current.secondaryTailSeed = it }
            if (session !== current || remote !== client || client?.authoritative==true) return@withLock emptyList()
            current.seenIds.addAll(fresh.map { it.id })
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
            .filterNot { it.id in current.seenIds }
        if (session !== current || remote !== client || client?.authoritative==true) return@withLock emptyList()
        current.seenIds.addAll(fresh.map { it.id })
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

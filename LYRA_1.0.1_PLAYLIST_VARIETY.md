# Lyra 1.0.1 — Varietà delle playlist automatiche

## Diagnosi sulla 1.0.0

L'analisi è stata effettuata sui sorgenti di **Lyra 1.0.0, build 37**.

1. `ListeningProfileStore.bestSeedForTag()` selezionava sempre il massimo punteggio del preset: un brano molto apprezzato (per esempio *Black Mamba* se presente nel profilo House) poteva essere sempre la sorgente iniziale.
2. `AutomaticPlaylistManager.startFromSeed()` aggiungeva sempre il seed in testa (`listOf(seed) + ranked`), indipendentemente dalla classifica dei brani suggeriti.
3. `AutomaticPlaylistRepository.findSeedFromQueries()` ruotava le parole di ricerca ma sceglieva soltanto fra i primi 12 risultati. Un catalogo ripetitivo produceva ancora pochi seed alternativi.
4. `RecommendationEngine.rank()` conservava una componente fissa forte basata sulla posizione di YouTube e una componente casuale contenuta: selezioni simili rimanevano spesso nello stesso ordine.
5. Gli ID venivano memorizzati nella **singola sessione** (`seenIds`), non tra più aperture della stessa playlist automatica: le canzoni *suggerite ma non riprodotte* non incidevano sulla ripetitività futura.
6. `pivotAfterRepeatedSkips()` per un preset sceglieva un *altro preset* anziché aggiornare la sorgente mantenendo il genere. Due skip House potevano cambiare genere.

Non è possibile stabilire se un determinato titolo sia effettivamente il seed nel profilo personale senza leggere il suo stato locale. Il comportamento riportato è coerente con la logica riscontrata.

## Interventi introdotti

- **Sorgente non fissa**: `seedCandidatesForTag()` restituisce una lista ordinata di seed pertinenti; la selezione campiona in modo pesato i migliori, evitando quando possibile quelli già utilizzati.
- **Esplorazione limitata**: occasionalmente viene interrogato YouTube Music anche quando sono disponibili seed appresi; nella ricerca si possono campionare fino a 20 candidati pertinenti.
- **Primo brano dinamico**: soltanto le playlist create da un preset usano il nuovo ordinamento e non forzano il seed in testa. La radio avviata esplicitamente scegliendo una singola canzone continua correttamente a partire da quella canzone.
- **Ordinamento a finestre**: prima traccia fra i primi 12 del ranking, successive fra i primi 6 disponibili, limitando la perdita di pertinenza. Intervengono filtro delle aperture precedenti, canzoni suggerite di recente e distanza dagli artisti consecutivi.
- **Memoria locale per preset**: ultimi 10 seed, 10 aperture e massimo 80 canzoni proposte. Per avvio vengono registrate non più di 24 proposte. Dati conservati in `SharedPreferences`, separati per House, Pop, Rock ecc. Non modificano storico, Cloudflare o backup.
- **Preferenza evita ripetizioni**: se disattivata, il riordino cambia comunque fra le sessioni, ma non impone gli specifici cooldown della memoria delle playlist.
- **Pop bilanciato**: diversificazione separata dei candidati italiani e internazionali, conservando l'alternanza preesistente.
- **Continuazione e Android Auto**: continuazione delle code e generazione da Android Auto usano lo stesso `AutomaticPlaylistManager` aggiornato.
- **Skip contestuale**: dopo due skip ravvicinati Lyra cerca un'altra sorgente **dello stesso preset**, senza passare automaticamente da House a un altro genere.
- **Diagnostica**: tag `LYRA_AUTO_VARIETY`, con preset, ID sorgente, ID apertura e numerosità della coda, privo di password o URL di streaming.

### Comportamenti volutamente invariati

- Play esplicito su un singolo brano → rimane quel brano come primo.
- Playlist salvate → mantengono il loro contenuto e ordine, perché sono una fotografia intenzionalmente salvata dall'utente.
- Nessuna modifica al player Media3, crossfade, gapless, Android Auto UI, firma, Cloudflare e algoritmo delle affinità storiche.

## Limiti

Lyra non può produrre nuove registrazioni che YouTube Music non restituisce. Con un catalogo estremamente ristretto sono possibili ripetizioni, ma l'ordine varierà quando esistono alternative. La nuova memoria è solo locale: un dispositivo ripristinato senza i suoi dati ripartirà senza cooldown delle proposte precedenti. Non si promettono brani totalmente inediti a ogni sessione.

## Versionamento e prove

- Versione **1.0.1**, `versionCode` **38**, base richiesta **1.0.0** / build 37.
- 15 test JUnit della politica di selezione/ordinamento aggiunti a `:app:testDebugUnitTest`.
- Test Kotlin isolati compilati ed eseguiti con stub (16 controlli di algoritmo, 11 di memoria locale e 12 avvii House simulati). Gli stub non sostituiscono una compilazione Android o un test con YouTube Music reale.
- Compilazione Android e collaudo su smartphone/Android Auto da effettuare prima della pubblicazione.

## Checklist manuale

1. Avvia House almeno 5 volte da Scopri, confrontando primi cinque titoli e primo brano. Ripeti con Rock/Dance.
2. Assicurati che le tracce siano ancora coerenti col genere scelto, e che artisti uguali non si accumulino dove ci sono alternative.
3. Skippa rapidamente due canzoni House: deve rimanere House.
4. Ripeti da Android Auto dopo riavvio del servizio.
5. Verifica che una radio partita da un titolo cercato inizi ancora da quel titolo.
6. Verifica le playlist salvate, il Mix per me, il gapless/crossfade e il ripristino della coda.
7. Cerca il tag `LYRA_AUTO_VARIETY` nei log diagnostici in caso di comportamento ripetitivo.

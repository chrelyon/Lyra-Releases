# Lyra 0.3.23 — Music Intelligence V3

Base: sorgenti Lyra(1).zip 0.3.22/versionCode 27, **con applicate** le patch 0.3.22 Android Auto/skip/Scopri e warning/ADB. Questa patch innalza `versionName` a `0.3.23` e `versionCode` a `28`.

## Correzione 1: metadata Android Auto

- `PlaybackMediaItemFactory`: ripristinati `title`/`displayTitle` del brano, `artist` del cantante e `albumTitle` dell'album reale. Il contesto rimane nell'extra `lyra.playback.contextLabel` per Lyra, non nei campi che l'host potrebbe usare al posto dell'artista.
- `PlaybackService`: stesso comportamento per i media item del browser Android Auto; `lyra.auto.queueTitle` rimane negli extras. Non si forza la visualizzazione del nome della playlist sulla card del brano, perche' l'interfaccia e' decisa dall'host.

## Correzione 2: Novita' da canzoni recenti

- La cartella `Novita'` NON propone piu' playlist prese da YouTube Music.
- Ogni sottogruppo prepara fino a due raccolte Lyra distinte (`lyra-new-release:`) cercando **SongItem** con `FILTER_SONG`, con query Italia/internazionali e tematiche differenti.
- Prima di ammettere una canzone, interroga l'album collegato tramite `YouTube.album` e richiede `AlbumItem.year == anno corrente`. Album senza anno, album vecchi, video senza album e riedizioni evidenti nel titolo vengono esclusi. Se ci sono meno di tre canzoni affidabili, la raccolta non compare.
- Caching per 30 minuti e refresh esplicito. Gli ID deterministici consentono Android Auto e ripristino dopo riavvio; la raccolta viene rigenerata se la cache non esiste.
- Il mix usa il ranking locale/Cloudflare gia' sincronizzato, senza modificare schema, token o API Cloudflare.

**Limite dati:** la data di uscita e' verificata al livello di *anno dell'album*, non di giorno/mese/anno del singolo ne' di prima uscita storica di un brano. YouTube Music non fornisce sempre tale dato. La provenienza italiana/internazionale e' una ricerca contestuale, non una verifica anagrafica della nazionalita' dell'artista. Queste raccolte possono restare vuote in assenza di metadati sufficienti.

## Affinamento IA e catalogo Scopri

- Recommendation Engine V3: legge una sola volta lo snapshot degli skip manuali precoci delle ultime 24 ore, gia' nel diario `ListeningEventStore` (inclusi quelli ripristinati da Cloudflare), penalizza maggiormente ripetizioni di canzoni e artisti, applica raffreddamento temporaneo dell'artista dopo almeno 2 skip precoci e distribuisce meglio gli artisti vicini nella coda.
- Altre cartelle Scopri: massimo **8 playlist per gruppo**, senza riempimento artificiale; valutazione su piu' query prima di scegliere, penalita' per titoli poco specifici o raccolte troppo piccole, verifica di apertura e campione di almeno cinque brani musicali distinti. I risultati non validabili non vengono promossi.
- **Visualizzazioni playlist:** InnerTubeX `PlaylistItem` non espone il numero delle visualizzazioni. Questa release NON inventa visualizzazioni e non attribuisce ranking di popolarita' non dimostrabili; usa invece segnali disponibili (pertinenza, dimensione e validita' della raccolta).

## Compatibilita'

- Nessun nuovo dato personale o credenziale, nessuna variazione al DB Cloudflare e agli script di firma.
- `0.3.24`: audio, gapless/crossfade e buffering; `0.3.25`: grafici preferenze smartphone e Cloudflare.
- Collaudo richiesto: Gradle `assembleDebug`, script Release firmata, Android Auto reale, Scopri Novita' con rete/account in diverse regioni e test di skip su piu' sessioni.

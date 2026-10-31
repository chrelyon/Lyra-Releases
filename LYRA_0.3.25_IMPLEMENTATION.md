# Lyra 0.3.25 — Music Intelligence Analytics + Android Auto navigation

## Base e versione
- Patch incrementale **0.3.24.1 (versionCode 32) → 0.3.25 (versionCode 33)**.
- Nessuna modifica a chiave, firma, repository GitHub, database Cloudflare o configurazione del Worker.
- Conserva normalizzazione, gapless, crossfade, preload e i collegamenti Home.

## Correzione Android Auto: skip avanti/indietro
- Il servizio distingue coda Media3 nativa e coda persistita anche in prossimità della fine della timeline.
- Se la timeline nativa non ha il brano successivo ma lo store persistito sì, interviene il fallback **solo quando il brano corrente è allineato**, evitando doppi salti.
- Con un cambio di brano asincrono già in corso, un ulteriore NEXT/PREV non viene scartato: viene accodato un singolo comando, consumato dopo il cambio di brano riuscito.
- I log `LYRA_EXTERNAL` ora includono indici della timeline e della queue, dimensione della coda e stato del caricamento: utili per riprodurre eventuali problemi residui.
- Non interferisce con la normale navigazione nativa già funzionante. **Richiede collaudo Android Auto reale**: l'origine esatta del problema intermittente non era identificabile in modo univoco senza log del veicolo.

## Statistiche musicali su smartphone
- Nuova voce: **Impostazioni → Le mie statistiche → Apri statistiche musicali**.
- Nuova schermata autonoma con pulsante Indietro e selettore del grafico.
- **Artisti più apprezzati**: barre di affinità relativa dal profilo cumulativo (avvii, completamenti, skip, Like).
- **Canzoni preferite**: barre dei brani con segnali cumulativi più positivi.
- **Andamento ascolti**: linea temporale con 7/30/90 giorni, avvii e minuti ascoltati.
- **Completamenti e skip**: anello con completati, parziali e skip precoci.
- **Contesti musicali**: barre delle playlist/modalità d'ascolto registrate (NON generi certificati dei brani).
- **Dettaglio sotto ogni grafico** con valori assoluti e descrizione.

## Dati e Cloudflare
- La lettura Cloudflare **non invia nuove tabelle né modifica lo schema D1**: utilizza il `GET /v2/restore` già usato per il ripristino del backup, ma lo legge senza chiamare le routine di restore che sovrascrivono il profilo locale.
- L'autenticazione usa la stessa identità anonima Lyra già presente nel dispositivo. Non vengono mostrati token, password o ID cloud.
- Quando la sincronizzazione cloud è abilitata, la schermata prova a leggere il profilo e gli eventi remoti. Se non riesce, indica esplicitamente il problema e mostra i dati locali, senza dichiararli remoti.
- I grafici temporali usano gli eventi realmente disponibili e possono essere incompleti rispetto all'intera vita dell'utente in caso di retention o mancata sincronizzazione.
- **Attenzione alla versione Worker:** nell'archivio sorgente è incluso un vecchio Worker `/v1`, mentre l'app già in uso comunica con un backend `/v2`. La verifica live del backend `/v2/restore` va fatta sul telefono; NON sostituire il Worker Cloudflare funzionante con i vecchi file sorgente `/v1`.
- Una dashboard web amministrativa Cloudflare completa (UI nel browser) resta lavoro separato: non viene aggiunta in questa patch, evitando di distribuire il vecchio Worker.

## Test e limitazioni
- Test Kotlin isolati dell'aggregazione eseguiti; patch applicabile su ricostruzione 0.3.24.1.
- La compilazione Android e la prova Android Auto richiedono il dispositivo/SDK dell'utente.
- Nessuna modifica irreversibile ai dati, nessuna cancellazione o migrazione cloud.

# Lyra 0.3.24 - Audio Experience, fase 1 (candidato al collaudo)

## Bug corretto: cantante non visibile in Android Auto

- I media item di riproduzione e quelli esposti alla libreria Android Auto ora includono `title`, `displayTitle`, `artist`, `subtitle` e `description` coerenti.
- La seconda riga contiene **l'artista**, non la playlist. Se il provider non restituisce alcun artista, il testo indica `Artista non disponibile`.
- Il titolo della playlist passa soltanto come `lyra.playback.contextLabel` (e come `lyra.auto.queueTitle` negli elementi della libreria), senza sostituire `artist` o `subtitle`.
- Il mini-player recupera il contesto da `MediaMetadata.extras`, non da `subtitle`.
- La posizione esatta della seconda riga rimane una scelta dell'host Android Auto; serve un test sul veicolo.

## Audio: novita' effettivamente implementate

1. **Prerisoluzione dei due brani successivi**: risolve e memorizza per breve tempo l'URL firmato tramite InnerTubeX, senza scaricare l'audio; e' una preparazione della sorgente, NON un preload dei PCM o una promessa di gapless.
2. **Riutilizzo delle sorgenti**: cache con TTL di 10 minuti (come in precedenza) e controllo della qualita' corrente. I comandi di navigazione del servizio possono usare la stessa cache.
3. **Buffer selezionato in base al tipo di rete all'avvio**: profilazione per connessione a consumo e non a consumo, con soglie locali ridotte. Il cambiamento di rete durante la stessa sessione NON riconfigura il LoadControl.
4. **Controlli audio persistenti**: toggle `Prerisolvi i prossimi brani` e `Buffer in base alla connessione` nelle Impostazioni Audio.
5. **Compatibilita'**: restano inalterati Cloudflare, il profilo di ascolto, lo streaming diretto e il controllo della firma.

## Funzioni ancora non implementate nella fase 1

- **Gapless completo su smartphone**: la coda su telefono e' ancora riprodotta come un solo MediaItem alla volta; Media3 dispone di playlist native in Android Auto, ma non c'e' garanzia che la transizione sia sempre senza silenzio.
- **Crossfade reale intelligente**: per sovrapporre due brani servono due flussi audio e una gestione coordinata di volume, audio focus, normalizzazione, seek e skip. Non e' implementato in questa patch.
- **Prefetch dei byte dello stream**: al momento vengono preparati gli URL, non scaricati segmenti audio futuri.
- **Riconfigurazione continua del buffer**: il profilo viene scelto alla creazione del player, non cambia automaticamente mentre il player e' attivo.

## Versione e distribuzione

- VersionName: `0.3.24`.
- VersionCode: `31` (presuppone che l'ultimo pubblicato sia `30`).
- La patch e' incrementale sulla catena 0.3.23.2 e non include alcuna chiave di firma.
- **Non pubblicare prima di aver compilato, verificato la firma e collaudato Android Auto e transizioni reali.**

## Checklist sul dispositivo

1. Avvia un brano YouTube su telefono, poi apri Android Auto: titolo e cantante devono comparire insieme.
2. Apri una playlist da Android Auto e riproduci un brano: l'artista deve rimanere la seconda riga, la copertina deve esserci o mostrare il fallback.
3. Cambia brano, ripeti, torna indietro, riavvia Android Auto; nessuna playlist deve apparire come cantante.
4. Controlla che sullo smartphone la label della playlist compaia nel mini-player e nel player completo.
5. Testa transizioni tra brani YouTube e locali su Wi-Fi e rete mobile, con pre-risoluzione ON e OFF.
6. Cambia la qualita' streaming e verifica che i nuovi brani usino la qualita' selezionata.
7. Verifica che il controllo della firma e `update.json` terminino correttamente in `tools/build-release.ps1`.

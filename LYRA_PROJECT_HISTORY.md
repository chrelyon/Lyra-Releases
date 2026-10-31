# Lyra - storia, architettura, changelog e piano di collaudo

**Documento unico di progetto**
Versione candidata: **0.3.15** (`versionCode 22`)
Package Android: `app.lyra.music`
Aggiornato: **30 settembre 2026**

> Questo file sostituisce tutti i precedenti file Markdown di changelog, test e pianificazione. Da questa versione in avanti la documentazione testuale del progetto deve essere mantenuta qui, evitando file `.md` paralleli e informazioni duplicate.

---

## 1. Obiettivo del progetto

Lyra e' un'applicazione Android dedicata all'ascolto musicale collegata alla libreria YouTube dell'utente, progettata fin dall'inizio con particolare attenzione a:

- riproduzione audio stabile tramite Android Media3/ExoPlayer;
- playlist musicali e Preferiti sincronizzati dall'account Google/YouTube;
- risoluzione on-demand degli stream YouTube tramite il modulo Innertube;
- coda completa, shuffle, repeat, seek, resume e persistenza;
- controlli da lock screen, cuffie, Bluetooth e dispositivi esterni;
- integrazione Android Auto tramite `MediaLibraryService`;
- UI Compose semplice, leggibile e non eccessiva;
- diagnostica opzionale con log locali e report crash;
- build Release firmata e distribuibile fuori dal Play Store;
- aggiornamenti futuri tramite GitHub Releases.

Lyra non incorpora credenziali Google, token OAuth o chiavi di firma. Le credenziali runtime vengono gestite dalle API Google e la firma Release viene configurata localmente tramite `keystore.properties`, escluso da Git.

---

## 2. Principi architetturali

### 2.1 Un solo motore audio

Media3/ExoPlayer e' l'unico motore di playback. La UI non possiede direttamente il player: comunica con `PlaybackService` tramite `MediaController`/`MediaSession`.

Questo permette a Lyra di continuare a funzionare quando:

- la schermata e' spenta;
- l'Activity non e' visibile;
- il controller e' la lock screen;
- il controller e' un dispositivo Bluetooth;
- il controller e' Android Auto.

### 2.2 Risoluzione stream separata dal player

La coda conserva metadata stabili dei brani. Gli URL audio YouTube vengono risolti solo quando servono e non vengono trattati come dati permanenti. Per Android Auto e per le code Media3 viene usata una sorgente differita `lyra://stream/...` che viene risolta al momento dell'apertura del brano.

### 2.3 Account Google separato dal resolver audio

L'autorizzazione Google serve a leggere la libreria YouTube dell'utente. Il resolver audio Innertube resta separato e non deve loggare o riutilizzare token OAuth dell'utente.

### 2.4 Persistenza della sessione

La coda, il brano corrente, la modalita' shuffle/repeat e la posizione di resume vengono persistiti localmente. Questo permette un ripristino coerente dopo chiusura dell'app o ricreazione del servizio.

### 2.5 Android Auto come superficie Media3

Android Auto non duplica la schermata Compose del telefono. Lyra espone invece una libreria Media3 con metadata, artwork e controlli, e l'host automotive genera la UI conforme alle regole di sicurezza del veicolo.

---

## 3. Cronologia sintetica Git

La cronologia iniziale del repository contiene questi checkpoint principali:

- `8ad47fd` - **Initial Lyra prototype with Innertube integration** - 9 settembre 2026.
- `c8f4fa0` - **Fix playback resolver and Media3 controls** - 9 settembre 2026.
- `be1709d` - **Add queue controls shuffle repeat and media metadata** - 9 settembre 2026.

Le iterazioni successive sono state sviluppate e collaudate come patch funzionali, fino al candidato `0.2.0` descritto in questo documento.

---

## 4. Evoluzione funzionale passo per passo

### Fase 1 - Prototipo player e Innertube

Obiettivo iniziale: separare i problemi del player Android dai problemi di risoluzione stream YouTube.

Implementato:

- progetto Android/Kotlin con Jetpack Compose;
- modulo `innertube` integrato;
- Media3/ExoPlayer come motore unico;
- playback di un singolo brano;
- primi log `LYRA_PLAYER` e `LYRA_STREAM`;
- distinzione tra errore Media3 e URL YouTube non valido.

### Fase 2 - Resolver YouTube robusto

Sono stati corretti i casi di URL CDN scaduti/non accettati e i fallimenti legati a formati non direttamente riproducibili.

Il player e' rimasto invariato mentre la logica di risoluzione e' stata isolata in `YouTubePlaybackRepository` e nel modulo Innertube.

### Fase 3 - Now Playing e seek

Aggiunti:

- mini-player persistente;
- schermata Now Playing;
- metadata titolo/artista/copertina;
- posizione e durata;
- seek;
- play/pausa/stop;
- riapertura del brano terminato.

### Fase 4 - Libreria Google/YouTube

Aggiunti:

- AuthorizationClient Google;
- scope YouTube read-only;
- profilo base OpenID per nome/avatar;
- sincronizzazione playlist;
- cache metadata locale;
- Preferiti musicali;
- filtro dei contenuti non musicali;
- aggiornamento manuale e al ritorno in foreground;
- revoca/disconnessione Google;
- avatar reale dell'account nella Home.

La cache non contiene password, token o cookie.

### Fase 5A - Coda avanzata

Aggiunti:

- apertura della coda completa;
- salto diretto a un indice;
- spostamento elementi su/giu;
- rimozione elementi;
- mantenimento coerente dell'indice corrente;
- NEXT/PREV secondo l'ordine effettivo della coda.

### Fase 5B - Shuffle e Repeat

Aggiunti:

- Shuffle ON/OFF;
- Repeat OFF;
- Repeat ALL;
- Repeat ONE;
- aggiornamento coerente della UI e della sessione Media3.

### Fase 5C/5D - Background e controller esterni

Aggiunti e verificati:

- foreground media service;
- MediaSession;
- notification/lock screen;
- controlli Bluetooth;
- metadata esterni;
- gestione NEXT/PREV da controller non-UI;
- resume con Activity non aperta.

### Fase 5E - Persistenza completa

Aggiunti:

- `PlaybackSessionStore`;
- salvataggio coda e stato;
- resume dopo cold start;
- ripristino metadata nel mini-player;
- factory centralizzata dei `MediaItem`.

Il bug NEXT dalla lock screen e' stato corretto con `LyraSessionPlayer`; il test smartphone ha confermato il funzionamento del comando NEXT e quindi il punto e' considerato **PASS**.

### Fase 5F - Restyling smartphone e avatar

Aggiunti:

- tema chiaro/scuro Lyra;
- palette dedicata;
- card playlist;
- mini-player e Now Playing rivisti;
- controlli a icone;
- adaptive launcher icon con sagoma della lira;
- layer monocromatico per themed icons;
- avatar Google reale con fallback.

### Fase 5G - Android Auto

`PlaybackService` e' stato convertito a `MediaLibraryService` + `MediaLibrarySession`.

Aggiunti:

- root automotive;
- Preferiti come scorciatoia dedicata;
- cartella `Le mie playlist`;
- browsing playlist/brani;
- ricerca;
- coda completa al momento della selezione;
- risoluzione stream differita;
- cold start della libreria;
- sincronizzazione stato telefono/Auto;
- metadata e artwork compatibili con Media3.

### Fase 5H - Diagnostica

Aggiunti:

- menu Impostazioni;
- toggle `Abilita log diagnostici`, default OFF;
- log locali con rotazione;
- cattura crash Java/Kotlin;
- rilevamento di crash/ANR precedenti su Android 11+ tramite `ApplicationExitInfo`;
- report condivisibile a `riparoerivendo@proton.me`;
- redazione di token e URL audio firmati;
- cancellazione dei log quando il toggle viene disattivato.

### Fase 5I - Correzioni Android Auto

Correzioni introdotte prima del collaudo finale:

- artwork automotive esposto tramite `content://` e `AutoArtworkProvider`;
- dimensionamento dinamico dell'artwork sulla misura richiesta dall'host, con fallback 512x512;
- `Preferiti` esclusa dalla cartella `Le mie playlist` lato Auto, per evitare il doppione; resta invariata sullo smartphone;
- Shuffle e Repeat esposti come controlli Media3 nativi;
- Repeat ciclico OFF -> TUTTI -> UNO -> OFF;
- correzione compatibilita' `CommandButton.Builder.setSlots(...)` con Media3 1.11.0.

### Candidato 0.2.0 - Consolidamento finale

La patch finale aggiunge:

- documentazione Markdown unificata in questo file;
- lettering `Lyra` piu' moderno nella Home usando la famiglia di sistema `sans-serif-rounded`, senza includere file font esterni;
- logout Google anche dal menu Impostazioni;
- controllo aggiornamenti tramite GitHub Releases (`chrelyon/Lyra`);
- controllo automatico massimo una volta ogni 24 ore;
- pulsante manuale `Controlla aggiornamenti`;
- supporto a `update.json` con `versionCode`, `versionName` e SHA-256;
- apertura guidata del download APK tramite browser/installer Android;
- script `build-release.ps1` / `build-release.sh` per produrre APK + metadata release;
- migrazione alle system bar edge-to-edge moderne;
- sostituzione delle icone Compose deprecate con le varianti AutoMirrored;
- versione candidata `0.2.0`, `versionCode 6`.

---

## 5. Stato collaudo smartphone

### Test gia' superati

- [x] Build debug completata.
- [x] Installazione e avvio senza crash.
- [x] Icona Lyra e Home.
- [x] Login Google.
- [x] Avatar Google.
- [x] Sincronizzazione playlist.
- [x] Preferiti musicali.
- [x] Mini-player.
- [x] Now Playing.
- [x] Play/Pausa.
- [x] Seek.
- [x] Previous.
- [x] Next dalla UI.
- [x] Next dalla lock screen dopo fix `LyraSessionPlayer`.
- [x] Stop.
- [x] Coda: salto traccia.
- [x] Coda: riordino.
- [x] Coda: rimozione.
- [x] Shuffle.
- [x] Repeat OFF/TUTTI/UNO.
- [x] Playback con schermo spento.
- [x] Controlli lock screen.
- [x] Controlli esterni/Bluetooth verificati.
- [x] Resume/sessione persistita.

---

## 6. Test Android Auto post-correzione 5I - OBBLIGATORIO PRIMA DELLA RELEASE

Questi test restano **aperti** finche' non vengono eseguiti sull'auto reale dopo la patch 5I/finale.

### 6.1 Avvio e browsing

- [ ] Lyra compare nell'elenco delle app multimediali Android Auto.
- [ ] Apertura senza crash e senza timeout.
- [ ] Root con `Preferiti` e `Le mie playlist`.
- [ ] Dentro `Le mie playlist` **non** compare nuovamente `Preferiti`.
- [ ] Apertura di piu' playlist consecutive senza contenuti vecchi o duplicati.
- [ ] Nessun caricamento infinito.

### 6.2 Artwork

- [ ] Copertine nitide nella root.
- [ ] Copertine nitide nella griglia playlist.
- [ ] Copertine corrette nella lista brani.
- [ ] Copertina corretta nel Now Playing automotive.
- [ ] Nessuna immagine stirata.
- [ ] Nessuna immagine eccessivamente grande o microscopica.
- [ ] Cambio playlist aggiorna sempre la copertina corretta.

### 6.3 Playback e queue

- [ ] Selezionare un brano centrale: parte esattamente il brano scelto.
- [ ] NEXT passa al successivo della stessa playlist.
- [ ] PREVIOUS torna al precedente.
- [ ] Auto-next a fine brano.
- [ ] Il cambio brano non richiede l'apertura dell'app sul telefono.
- [ ] La risoluzione dello stream non blocca l'interfaccia Auto.

### 6.4 Shuffle / Repeat

- [ ] Shuffle visibile direttamente o nell'overflow, in base all'host.
- [ ] Repeat visibile direttamente o nell'overflow.
- [ ] Shuffle OFF -> ON -> OFF.
- [ ] Shuffle modifica realmente l'ordine di avanzamento.
- [ ] Repeat OFF -> TUTTI -> UNO -> OFF.
- [ ] Repeat TUTTI riparte correttamente nella coda.
- [ ] Repeat UNO ripete il singolo brano.
- [ ] Icona/stato aggiornati dopo ogni pressione.

### 6.5 Sincronizzazione telefono / Auto

- [ ] Aprendo Lyra sul telefono durante Auto viene mostrato lo stesso brano.
- [ ] Coda e indice coincidono.
- [ ] NEXT/PREV dal telefono aggiornano Auto.
- [ ] NEXT/PREV da Auto aggiornano il telefono.
- [ ] Play/Pausa resta sincronizzato.

### 6.6 Brano presente in piu' playlist

- [ ] Aprire una playlist A con un brano condiviso.
- [ ] Aprire una playlist B con lo stesso brano.
- [ ] Avviare il brano dalla B.
- [ ] NEXT deve continuare nella B e non nella A.

### 6.7 Ricerca / voce

- [ ] Ricerca titolo/artista dall'host o voce.
- [ ] Risultati corretti.
- [ ] Selezione risultato avvia il brano scelto.
- [ ] NEXT segue la coda dei risultati quando disponibile.
- [ ] Ricerca vuota non crea elementi fantasma.

### 6.8 Background e riconnessione

- [ ] Schermo telefono spento: playback Auto continua.
- [ ] Scollegamento Auto: sessione continua sul telefono.
- [ ] Ricollegamento Auto: titolo, play/pausa e posizione coerenti.
- [ ] Cold start con Auto collegata prima di aprire Lyra.

### 6.9 Rete/autorizzazione

- [ ] Libreria cached sfogliabile senza rete.
- [ ] Nessun crash se uno stream non puo' essere risolto offline.
- [ ] Rete ripristinata: nuova selezione torna a funzionare.
- [ ] Refresh silenzioso dell'autorizzazione quando necessario.

Durante il test tenere attivi i log diagnostici. Per un secondo livello di controllo:

```powershell
.\tools\lyra-auto-logcat.ps1
```

---

## 7. Test diagnostica post-patch

- [ ] Aprire Impostazioni.
- [ ] Abilitare `Abilita log diagnostici`.
- [ ] Usare Home, playlist, player, NEXT/PREV, Auto.
- [ ] Verificare creazione di `files/diagnostics/lyra.log` in debug tramite `run-as`.
- [ ] Simulare un crash di test, se desiderato: `adb shell am crash app.lyra.music`.
- [ ] Riaprire Lyra.
- [ ] Verificare proposta report.
- [ ] Verificare destinatario `riparoerivendo@proton.me`.
- [ ] Verificare allegato `lyra-diagnostic-report.txt`.
- [ ] Verificare assenza di token OAuth e URL firmati in chiaro.
- [ ] Disabilitare i log e verificare cancellazione dei file diagnostici.

---

## 8. Test Impostazioni finale

- [ ] Menu Impostazioni scorre correttamente su display piccoli.
- [ ] Toggle log invariato.
- [ ] Se Google e' collegato viene mostrato lo stato account.
- [ ] `Disconnetti da Google` mostra conferma.
- [ ] Confermando, Google viene revocato e la cache locale viene svuotata.
- [ ] Tornando in Home compare il login Google.
- [ ] Nuovo login ripristina correttamente libreria/avatar.

---

## 9. Test aggiornamenti GitHub

Il repository configurato e': `https://github.com/chrelyon/Lyra`.

### Prima della prima release pubblica

- [ ] Creare GitHub Release con tag `v0.2.0`.
- [ ] Allegare `Lyra-0.2.0.apk`.
- [ ] Allegare `update.json` generato dallo script release.
- [ ] Verificare che la release non sia Draft.
- [ ] Per essere rilevata da `releases/latest`, la release finale non deve essere una prerelease.

### Dall'app

- [ ] Impostazioni -> `Controlla aggiornamenti` con 0.2.0 installata restituisce `Lyra e' aggiornata`.
- [ ] Pubblicare una release di test con `versionCode` superiore.
- [ ] Verificare avviso automatico al successivo controllo valido.
- [ ] Verificare pulsante manuale.
- [ ] Verificare che `Apri download aggiornamento` apra l'asset APK o la pagina Release.
- [ ] Installare la nuova APK firmata con lo stesso alias `lyra`.
- [ ] Verificare conservazione dati e configurazioni.

Nessun token GitHub e' incluso nell'APK. Per questo il meccanismo presuppone Release pubbliche. Il sorgente puo' comunque essere gestito separatamente se in futuro si desidera mantenere privato il codice e pubblico solo un repository di distribuzione.

---

## 10. Build Debug

Da PowerShell:

```powershell
.\gradlew.bat assembleDebug
```

APK:

```text
app\build\outputs\apk\debug\app-debug.apk
```

Installazione:

```powershell
adb install -r .\app\build\outputs\apk\debug\app-debug.apk
```

---

## 11. Build Release firmata

### 11.1 File locale `keystore.properties`

Il file deve restare escluso da Git.

Esempio:

```properties
storeFile=C:/PERCORSO/keystore.jks
storePassword=PASSWORD_LOCALE
keyAlias=lyra
keyPassword=PASSWORD_LOCALE
```

Non inserire mai valori reali nella documentazione o nel repository.

### 11.2 Build diretta

```powershell
.\gradlew.bat assembleRelease
```

Output:

```text
app\build\outputs\apk\release\app-release.apk
```

### 11.3 Build pronta per GitHub Releases

Windows:

```powershell
.\tools\build-release.ps1
```

Linux/macOS:

```bash
./tools/build-release.sh
```

Gli script generano in `dist/`:

```text
Lyra-0.2.0.apk
update.json
```

`update.json` contiene almeno:

- `versionCode`;
- `versionName`;
- nome APK;
- SHA-256 APK;
- flag `mandatory` (attualmente false);
- note sintetiche.

La cartella `dist/` e' ignorata da Git.

---

## 12. Firma APK

La firma ufficiale deve usare sempre lo stesso alias `lyra`.

Verifica consigliata:

```powershell
& $apksigner.FullName verify --verbose --print-certs .\app\build\outputs\apk\release\app-release.apk
```

La firma v2 e' sufficiente per il `minSdk 26` di Lyra. Il fingerprint SHA-256 deve restare coerente tra tutte le release future.

---

## 13. Diagnostica e log

Tag principali:

- `LYRA_APP`: lifecycle generale.
- `LYRA_AUTH`: autorizzazione Google.
- `LYRA_LIBRARY`: cache e sincronizzazione playlist.
- `LYRA_PLAYER`: Media3 e stato player.
- `LYRA_STREAM`: risoluzione URL audio.
- `LYRA_QUEUE`: coda e indice.
- `LYRA_EXTERNAL`: controller esterni.
- `LYRA_AUTO`: Android Auto / MediaLibrary.
- `LYRA_UPDATE`: controllo GitHub Releases.

Script disponibili:

```powershell
.\tools\lyra-logcat.ps1
.\tools\lyra-auto-logcat.ps1
```

I log diagnostici interni sono opt-in.

---

## 14. Sicurezza e privacy

- Nessun keystore deve essere committato.
- `keystore.properties` e' ignorato da Git.
- `local.properties` e' ignorato da Git.
- Nessun token Google deve essere scritto nei log.
- Nessun URL CDN YouTube firmato deve essere riportato integralmente nel report.
- La diagnostica e' OFF per default.
- L'invio del crash report richiede sempre azione esplicita dell'utente.
- Il controllo GitHub non usa token embedded.
- Il logout Google revoca l'accesso e pulisce la cache metadata locale.

---

## 15. Moduli principali

### `app`

Contiene tutta l'applicazione Lyra: UI Compose, autorizzazione, libreria, player, servizio Media3, Android Auto, diagnostica e updater.

### `innertube`

Modulo separato per comunicazione e parsing dei servizi YouTube/YouTube Music necessari alla risoluzione playback. Va trattato come componente infrastrutturale: evitare modifiche cosmetiche o non necessarie.

---

## 16. Dipendenze principali

- Kotlin/JVM 21.
- Jetpack Compose + Material 3.
- AndroidX Navigation.
- Lifecycle/ViewModel.
- Media3 1.11.0.
- Google Play Services Auth.
- Coil 3 per artwork Compose.
- Modulo locale `innertube`.

---

## 17. Warning di build conosciuti

### SDK XML version

Su alcune installazioni Windows puo' comparire:

```text
This version only understands SDK XML versions up to 3 but an SDK XML file of version 4 was encountered.
```

E' un disallineamento locale tra Android Studio e SDK command-line tools, non un bug Lyra. Va risolto aggiornando/allineando i tool SDK della macchina di sviluppo.

### Native debug symbols

In debug puo' comparire il warning relativo a `libandroidx.graphics.path.so` o `libquickjs.so` non strippabili. Non impedisce la build debug e non e' un errore applicativo.

---

## 18. Criteri per dichiarare ufficiale la 0.2.0

La prima release viene considerata ufficiale solo quando sono contemporaneamente veri:

- [x] Build Debug finale SUCCESSFUL.
- [x] Test smartphone di regressione superato.
- [x] Test Android Auto 5I completo superato.
- [x] Test diagnostica superato.
- [x] Logout Google da Impostazioni superato.
- [x] Test updater GitHub almeno nella modalita' `nessuna release / gia' aggiornato` superato.
- [x] Build Release SUCCESSFUL.
- [x] `apksigner verify` superato.
- [x] Installazione pulita della Release firmata su smartphone reale.
- [x] Login/libreria/playback/lock screen sulla Release superati.
- [x] Test Android Auto eseguito anche con la Release firmata.
- [x] Commit e push Git puliti.
- [x] Tag Git `v0.2.0` creato.
- [x] GitHub Release pubblicata con APK + `update.json`.

---

## 19. Backlog non bloccante dopo la 0.2.0

Questi punti non sono necessari per dichiarare stabile la prima release:

- qualita' streaming `Auto / Alta / Risparmio dati`;
- selettore tema `Sistema / Chiaro / Scuro`;
- gestione avanzata cache artwork;
- opzioni configurabili di resume;
- salto automatico configurabile dei brani non riproducibili;
- crossfade;
- normalizzazione volume;
- equalizzatore;
- download offline.

Ognuno richiede un ciclo di test dedicato e non va introdotto nella release finale senza necessita'.

---

## 20. Regola di manutenzione documentazione

Da questa versione:

1. aggiornare **solo** `LYRA_PROJECT_HISTORY.md` per storia, changelog, TODO e test;
2. aggiornare `LYRA_TECHNICAL_REFERENCE.pdf` quando cambia in modo sostanziale la struttura dei file;
3. non creare nuovi `.md` temporanei nel repository;
4. ogni release deve aggiungere una sezione nella cronologia e aggiornare i test pertinenti;
5. i dettagli di firma reali (password, percorso privato del keystore, certificati privati) non devono mai essere documentati nel repository.

---

## 21. Lyra 0.3.0 - Library & Discovery

Versione candidata: **0.3.0** (`versionCode 7`). La 0.2.0 resta il primo checkpoint ufficiale stabile.

### 21.1 Nuova Home

La Home espone tre ingressi aggiuntivi senza rimuovere la libreria personale:

- **Scopri**: classifiche e playlist pubbliche YouTube;
- **Cerca**: ricerca diretta di titoli/brani YouTube;
- **Dispositivo**: musica locale indicizzata da Android MediaStore.

### 21.2 Scopri / classifiche YouTube

`YouTubeDataApiClient` usa `videos.list(chart=mostPopular, videoCategoryId=10, regionCode=IT)` per costruire **Top musica Italia**. La sezione propone inoltre playlist musicali pubbliche tramite `search.list(type=playlist)`. Per ridurre il consumo quota, il risultato Discovery viene mantenuto in cache per 30 minuti; il pulsante Aggiorna forza invece una richiesta nuova.

Il contenuto viene convertito negli stessi modelli `Song` / `Playlist` usati dal resto di Lyra, quindi playback, queue, artwork, lock screen e Android Auto non richiedono un motore separato.

### 21.3 Ricerca YouTube

Nuova schermata `Cerca su YouTube` con ricerca esplicita tramite IME/pulsante. Non viene effettuata una richiesta a ogni carattere. I risultati usano il resolver InnerTube gia' esistente e possono essere avviati singolarmente oppure con **Riproduci casualmente**.

Su Android Auto la ricerca `MediaLibrarySession` combina risultati YouTube e, se autorizzata, musica locale, mantenendo il supporto alle ricerche vocali dell'host.

### 21.4 Libreria locale MediaStore

Lyra legge in sola lettura i file audio indicizzati da Android:

- Android 13+: `READ_MEDIA_AUDIO`;
- Android 12 e precedenti: `READ_EXTERNAL_STORAGE` limitato a `maxSdkVersion=32`.

La UI permette di sfogliare:

- Brani;
- Album;
- Artisti.

I brani locali usano direttamente URI `content://` e vengono riprodotti dallo stesso ExoPlayer/MediaSession dei brani YouTube. La queue persistita salva anche URI locale e metadata album.

### 21.5 Shuffle diretto

Le playlist YouTube, i risultati di ricerca e le raccolte locali espongono **Riproduci casualmente**. Su smartphone Lyra crea immediatamente una queue con `shuffleEnabled=true`; su Android Auto ogni raccolta presenta un elemento riproducibile `Riproduci casualmente` che avvia la stessa queue con shuffle nativo Media3.

### 21.6 Android Auto 0.3

La root prevista resta entro quattro nodi principali:

1. Preferiti (quando disponibile);
2. Le mie playlist;
3. Scopri;
4. Sul dispositivo.

`Scopri` contiene Top musica Italia e Playlist da scoprire. `Sul dispositivo` contiene Brani, Album e Artisti quando il permesso audio e' gia' stato concesso sul telefono; in caso contrario Android Auto mostra l'indicazione di autorizzare Lyra dal telefono. La ricerca Auto interroga sia YouTube sia MediaStore quando disponibili.

### 21.7 Aggiornamenti

Dalla 0.3.0 `GitHubUpdateChecker` usa il repository pubblico **`chrelyon/Lyra-Releases`**. Il repository sorgente `Lyra` puo' quindi rimanere privato e nessun token GitHub viene incorporato nell'APK.

### 21.8 Compatibilita' sessione 0.2 -> 0.3

Lo schema della queue passa a versione 2 per salvare anche sorgenti locali, ma il restore accetta ancora lo schema 1 della 0.2.0. L'aggiornamento non deve quindi cancellare la queue YouTube esistente solo per il cambio di schema.

### 21.9 Test fase 0.3 da eseguire

#### Smartphone

- [ ] Build Release/Debug 0.3.0 completata senza errori.
- [ ] Installazione `-r` sopra 0.2.0 firmata mantenendo dati e login.
- [ ] Home: Scopri / Cerca / Dispositivo visibili e navigabili.
- [ ] Scopri: Top musica Italia caricata e riproducibile.
- [ ] Scopri: playlist pubbliche apribili e riproducibili.
- [ ] Cerca: ricerca titolo/artista YouTube, playback e queue corretti.
- [ ] Playlist: `Riproduci casualmente` avvia shuffle attivo.
- [ ] Permesso libreria locale richiesto solo quando si apre Dispositivo.
- [ ] Brani locali letti e riprodotti senza rete.
- [ ] Album e Artisti locali correttamente raggruppati.
- [ ] Artwork locale: fallback pulito quando la copertina non e' disponibile.
- [ ] NEXT/PREV, lock screen e Bluetooth con queue locale.
- [ ] Queue mista non prevista: verificare che ogni sorgente mantenga la propria queue senza errori.
- [ ] Resume dopo chiusura processo per brano YouTube e brano locale.
- [ ] Updater punta a `Lyra-Releases` e non richiede token.

#### Android Auto

- [ ] Root con massimo quattro nodi e nessun doppione Preferiti.
- [ ] Scopri -> Top musica Italia navigabile.
- [ ] Scopri -> Playlist da scoprire navigabili.
- [ ] Ogni raccolta mostra `Riproduci casualmente` e avvia shuffle reale.
- [ ] Sul dispositivo e' sempre presente; senza permesso mostra l'invito ad autorizzare dal telefono.
- [ ] Sul dispositivo -> Brani / Album / Artisti navigabili.
- [ ] Playback locale con artwork dimensionato/fallback corretto.
- [ ] Ricerca vocale/testuale Auto restituisce YouTube + locale quando disponibile.
- [ ] NEXT/PREV, Shuffle, Repeat e auto-next funzionano con YouTube e locale.
- [ ] Scollega/ricollega Android Auto durante playback locale e YouTube.
- [ ] Cold start Android Auto con libreria personale, Scopri e MediaStore.

### 21.10 Gate prima della release 0.3.0

Non creare il tag `v0.3.0` finche' tutti i test sopra non sono completati. Eventuali fix devono restare nella stessa fase e aggiornare questo documento e `LYRA_TECHNICAL_REFERENCE.pdf`.

---

## 22. Lyra 0.3.1 - Libreria locale pulita e test updater

Micro-release di collaudo: **0.3.1** (`versionCode 8`). La modifica e' volutamente piccola per verificare il flusso reale di aggiornamento dalla 0.3.0 gia' installata tramite il repository pubblico `chrelyon/Lyra-Releases`.

### 22.1 Filtro cartella Music

`LocalMediaRepository` non si affida piu' soltanto a `MediaStore.Audio.Media.IS_MUSIC`, perche' Android puo' classificare come musica anche note vocali, audio di messaggistica e altri file indicizzati.

Lyra 0.3.1 legge esclusivamente:

- `Music/`;
- tutte le sottocartelle di `Music/`.

Su Android 10+ il filtro usa `MediaStore.Audio.Media.RELATIVE_PATH LIKE "Music/%"`. Sulle versioni precedenti usa il percorso legacy `DATA` limitato ai file contenuti in un segmento `/Music/`.

Il filtro vale per Brani, Album, Artisti, ricerca locale e Android Auto, perche' tutte queste superfici leggono la stessa lista fornita da `LocalMediaRepository`.

### 22.2 Copertine nella vista Album

La pagina `Dispositivo -> Album` mostra ora la copertina dell'album a fianco del titolo. L'immagine viene presa dal primo brano della raccolta che dispone di artwork MediaStore; se non e' presente rimane il fallback grafico gia' gestito da `SongArtwork`. La sezione Artisti resta invariata.

### 22.3 Test end-to-end aggiornamenti

Questa versione deve essere usata per verificare il sistema di aggiornamento, quindi **non va installata manualmente prima del test**.

Procedura prevista:

1. lasciare sul telefono la 0.3.0 Release firmata;
2. compilare la 0.3.1 con `tools/build-release.ps1`;
3. pubblicare in `chrelyon/Lyra-Releases` una GitHub Release con tag `v0.3.1`;
4. allegare `Lyra-0.3.1.apk` e `update.json`;
5. dalla 0.3.0 aprire `Impostazioni -> Controlla aggiornamenti`;
6. verificare che Lyra rilevi la 0.3.1;
7. aprire il download dall'app e completare l'installazione Android;
8. verificare che login Google, dati e configurazione siano rimasti presenti;
9. controllare che `Dispositivo -> Brani` mostri solo `Music/`;
10. controllare che `Dispositivo -> Album` mostri le copertine;
11. ripetere `Controlla aggiornamenti` dalla 0.3.1: il risultato deve essere `Lyra e' aggiornata (0.3.1)`.

### 22.4 Checklist 0.3.1

- [ ] Build Release 0.3.1 SUCCESSFUL.
- [ ] APK firmato con la chiave ufficiale Lyra.
- [ ] GitHub Release `v0.3.1` pubblicata in `Lyra-Releases`.
- [ ] 0.3.0 rileva 0.3.1 dall'interno dell'app.
- [ ] Download dell'APK aperto correttamente dall'app.
- [ ] Installazione sopra 0.3.0 senza perdita di login/dati.
- [ ] Brani WhatsApp/note vocali fuori da `Music/` non presenti.
- [ ] Brani in `Music/` e sottocartelle presenti.
- [ ] Album con artwork mostrano la copertina nella lista.
- [ ] Album senza artwork usano un fallback pulito.
- [ ] Artisti invariati e funzionanti.
- [ ] Android Auto `Sul dispositivo` riflette lo stesso filtro locale.
- [ ] Dopo l'installazione, updater segnala 0.3.1 come versione aggiornata.


---

## 23. Lyra 0.3.2 - Controllo aggiornamenti ad ogni apertura e banner Home

Micro-release: **0.3.2** (`versionCode 9`). Nasce dopo il primo aggiornamento reale 0.3.0 -> 0.3.1 completato con successo direttamente dall'app. La 0.3.2 rende la disponibilita' di un aggiornamento molto piu' evidente senza introdurre popup invasivi.

### 23.1 Esito del primo aggiornamento in-app

Il flusso GitHub Releases e' stato verificato sul dispositivo reale:

- la versione installata ha rilevato la release successiva nel repository pubblico `chrelyon/Lyra-Releases`;
- il download e' stato aperto dall'app;
- Android ha installato l'APK come aggiornamento della stessa applicazione;
- la firma ufficiale Lyra ha consentito l'aggiornamento in-place;
- la patch 0.3.1 e il filtro della libreria locale sono risultati funzionanti.

Questo conferma il canale di distribuzione fuori dal Play Store come operativo.

### 23.2 Controllo automatico ad ogni apertura

`GitHubUpdateChecker` non usa piu' la finestra di 24 ore della 0.3.1. Ogni volta che `MainActivity` entra in foreground (`onStart`), Lyra interroga la latest release del repository pubblico e confronta immediatamente il `versionCode` remoto con quello installato.

Se la rete non e' disponibile o GitHub restituisce un errore, l'avvio dell'app non viene bloccato: l'errore resta non invasivo e il controllo manuale nelle Impostazioni rimane disponibile.

### 23.3 Banner aggiornamento nella Home

Quando viene rilevata una release piu' recente, la Home mostra in alto un banner persistente con:

- versione disponibile;
- messaggio `Nuovo aggiornamento pronto`;
- icona download;
- pressione sull'intero banner per aprire direttamente la sezione Aggiornamenti;
- pulsante `X` per nascondere il banner solo per la sessione corrente.

Alla successiva apertura dell'app il banner torna automaticamente se l'aggiornamento non e' stato ancora installato.

### 23.4 Badge Impostazioni

Quando esiste un aggiornamento, l'icona Impostazioni nella Home mostra un piccolo indicatore. Il badge rimane visibile anche se il banner viene nascosto per la sessione, in modo da non perdere completamente il richiamo all'update disponibile.

### 23.5 Accesso diretto alla sezione Aggiornamenti

Toccando il banner:

1. Lyra apre `Impostazioni`;
2. la schermata scorre automaticamente fino alla sezione `Aggiornamenti`;
3. il focus viene portato sul pulsante `Aggiorna ora` quando l'update e' gia' noto;
4. il pulsante apre l'asset APK della GitHub Release e Android mantiene la conferma finale di installazione.

Il controllo manuale `Controlla aggiornamenti` resta disponibile e aggiorna anche lo stato globale usato dal banner Home.

### 23.6 Android Auto

Nessun banner, popup o badge di aggiornamento viene esposto su Android Auto. La funzione rimane esclusivamente nella UI smartphone, per non introdurre elementi non necessari o distraenti nell'interfaccia automotive.

### 23.7 Test 0.3.2 da eseguire

- [ ] Build Release 0.3.2 SUCCESSFUL.
- [ ] Installazione/aggiornamento mantiene login e dati.
- [ ] Con nessuna release piu' recente la Home non mostra alcun banner.
- [ ] Pubblicando una release con `versionCode` superiore, alla successiva apertura compare il banner.
- [ ] Il banner mostra il numero versione corretto.
- [ ] Tap sul banner apre Impostazioni e porta direttamente alla sezione Aggiornamenti.
- [ ] Il focus arriva sul pulsante `Aggiorna ora`.
- [ ] `Aggiorna ora` apre il download dell'APK corretto.
- [ ] La `X` nasconde il banner per la sessione corrente.
- [ ] Dopo chiusura completa e nuova apertura il banner ricompare se l'update non e' stato installato.
- [ ] Il badge sull'icona Impostazioni resta visibile finche' esiste una release piu' recente.
- [ ] Il controllo manuale continua a funzionare.
- [ ] Android Auto non mostra UI relativa agli aggiornamenti e non subisce regressioni.

---

## 24. Lyra 0.3.3 - Banner update e download APK nella cache privata

Micro-release: **0.3.3** (`versionCode 10`). Ha due obiettivi distinti: usare la release 0.3.3 per verificare il banner automatico introdotto nella 0.3.2 e rendere disponibile, da questa versione in avanti, un updater che non lascia APK nella cartella Download.

### 24.1 Test del banner automatico 0.3.2 -> 0.3.3

La 0.3.3 deve essere pubblicata in `chrelyon/Lyra-Releases` mentre sul telefono e' installata la 0.3.2. Alla successiva apertura/ritorno in foreground della 0.3.2:

1. `GitHubUpdateChecker` deve rilevare `versionCode 10`;
2. la Home deve mostrare il banner `Lyra 0.3.3 disponibile`;
3. l'icona Impostazioni deve mostrare il badge;
4. il tap sul banner deve aprire Impostazioni e scorrere alla sezione Aggiornamenti;
5. il focus deve arrivare su `Aggiorna ora`;
6. la `X` deve nascondere il banner solo per la sessione;
7. chiudendo e riaprendo Lyra il banner deve tornare finche' la 0.3.3 non viene installata.

La 0.3.2 usa ancora il vecchio flusso che apre l'asset GitHub/browser. Per questo l'installazione **0.3.2 -> 0.3.3** serve principalmente a collaudare il banner. Il nuovo download interno diventa operativo una volta installata la 0.3.3 e verra' collaudato con la release successiva.

### 24.2 Download aggiornamenti nella cache privata

La nuova classe `UpdateInstaller` scarica l'APK in:

`cacheDir/updates/`

Il file non viene piu' scritto nella cartella pubblica Download. Il download usa direttamente l'asset APK della GitHub Release e mantiene il controllo utente finale dell'installer Android.

### 24.3 Verifica SHA-256

Quando `update.json` contiene `sha256`, `UpdateInstaller` calcola SHA-256 del file scaricato prima di aprire l'installer. Se il digest non coincide:

- l'APK temporaneo viene cancellato;
- l'installer non viene aperto;
- Lyra mostra un errore nella sezione Aggiornamenti.

Un APK gia' presente in cache viene riutilizzato solo se supera la stessa verifica.

### 24.4 Installazione da Lyra e autorizzazione sorgente sconosciuta

La 0.3.3 dichiara `REQUEST_INSTALL_PACKAGES`. Su Android 8+ Lyra controlla `canRequestPackageInstalls()`. Se l'utente non ha ancora autorizzato Lyra come sorgente di installazione, viene aperta la schermata Android dedicata. Dopo l'autorizzazione e' sufficiente tornare nelle Impostazioni Lyra e premere nuovamente `Aggiorna ora`; l'APK gia' verificato resta in cache e non viene riscaricato.

L'APK viene condiviso con l'installer tramite il `FileProvider` gia' presente, esteso alla sottocartella `cache/updates/`. Play Protect e la conferma finale Android non vengono bypassati.

### 24.5 Pulizia automatica dopo aggiornamento riuscito

Prima di aprire l'installer, Lyra salva il `versionCode` atteso e il percorso del file temporaneo. Al primo avvio successivo `UpdateInstaller.cleanupAfterSuccessfulUpdate()` confronta il `versionCode` installato con quello atteso. Se l'aggiornamento e' riuscito:

- cancella l'APK temporaneo;
- elimina eventuali altri APK residui dalla cache `updates`;
- azzera lo stato pending dell'updater.

Se l'utente annulla l'installazione, il file resta nella cache privata e puo' essere riutilizzato al tentativo successivo.

### 24.6 Checklist 0.3.3

- [ ] Build Release 0.3.3 SUCCESSFUL.
- [ ] GitHub Release `v0.3.3` pubblicata con APK e `update.json`.
- [x] 0.3.2 rileva automaticamente la 0.3.3 all'apertura.
- [x] Banner Home visibile con versione corretta.
- [x] Badge Impostazioni visibile.
- [x] Tap banner porta direttamente alla sezione Aggiornamenti.
- [x] `X` nasconde il banner solo per la sessione.
- [ ] Chiusura/riapertura fa ricomparire il banner finche' l'update non e' installato.
- [ ] Aggiornamento 0.3.2 -> 0.3.3 mantiene login Google e dati.
- [ ] Dopo installazione 0.3.3 il controllo update segnala versione aggiornata.
- [ ] Alla release successiva, 0.3.3 scarica l'APK in cache privata e non in Download.
- [ ] SHA-256 verificato prima dell'installer.
- [ ] Se necessario, Android richiede una sola volta l'autorizzazione `Installa app sconosciute` per Lyra.
- [ ] Dopo aggiornamento riuscito l'APK temporaneo viene cancellato al primo avvio.
- [ ] Nessuna regressione smartphone, lock screen, Bluetooth o Android Auto.

## 25. Lyra 0.3.4 - collaudo updater privato e manutenzione InnerTubeX

Micro-release: **0.3.4** (`versionCode 11`). Questa versione ha due obiettivi: essere il bersaglio reale con cui collaudare il nuovo `UpdateInstaller` introdotto nella 0.3.3 e rendere tracciabile la dipendenza esterna InnerTubeX senza permettere aggiornamenti automatici rischiosi del motore streaming.

### 25.1 Test updater 0.3.3 -> 0.3.4

La 0.3.4 deve essere pubblicata in `chrelyon/Lyra-Releases` mentre sul telefono e' installata la 0.3.3. Il percorso previsto e':

1. la 0.3.3 rileva automaticamente `versionCode 11`;
2. il banner Home porta alla sezione Aggiornamenti;
3. `Aggiorna ora` scarica `Lyra-0.3.4.apk` in `cacheDir/updates/`;
4. il file non deve comparire nella cartella pubblica Download;
5. Lyra verifica SHA-256 usando `update.json`;
6. Android puo' richiedere l'autorizzazione `Installa app sconosciute` per Lyra;
7. l'installer Android/Play Protect mantiene la conferma finale dell'utente;
8. dopo l'installazione e il primo avvio della 0.3.4, il file APK temporaneo viene eliminato automaticamente.

La sezione Diagnostica mostra ora la versione installata di Lyra e la versione InnerTubeX realmente compilata, cosi' e' immediato verificare che l'aggiornamento sia avvenuto.

### 25.2 Versione InnerTubeX tracciata

Il modulo `:innertube` usa attualmente:

`com.github.MetrolistGroup.innertubex:innertubex:0.6.0`

La versione non viene duplicata manualmente nella UI. `app/build.gradle.kts` legge il valore dal `build.gradle.kts` del modulo `innertube` e lo espone tramite `BuildConfig.INNERTUBEX_VERSION`. In Impostazioni -> Diagnostica vengono quindi mostrati:

- versione Lyra (`versionName` e `versionCode`);
- versione InnerTubeX effettivamente inclusa nella build.

### 25.3 Controllo aggiornamenti InnerTubeX lato sviluppo

Sono aggiunti:

- `tools/check-innertubex-update.ps1`;
- `tools/check-innertubex-update.sh`.

Gli script leggono automaticamente la versione corrente dal modulo `innertube`, interrogano il repository pubblico `MetrolistGroup/innertubex` e confrontano l'ultima release/tag SemVer disponibile. Il controllo non modifica alcun file e non aggiorna automaticamente la dipendenza.

Su Windows:

```powershell
.\tools\check-innertubex-update.ps1
```

Su Linux/macOS:

```bash
./tools/check-innertubex-update.sh
```

Se viene rilevata una versione piu' nuova, l'aggiornamento va eseguito solo dopo aver letto changelog/tag upstream e in un branch dedicato. InnerTubeX e' parte critica del resolver YouTube: una variazione puo' influenzare login, parsing, ricerca, estrazione stream e compatibilita' futura con YouTube.

### 25.4 Procedura obbligatoria per un futuro upgrade InnerTubeX

1. eseguire lo script di controllo;
2. leggere changelog, tag e modifiche incompatibili upstream;
3. creare un branch dedicato, ad esempio `maintenance/innertubex-x.y.z`;
4. modificare solo la versione in `innertube/build.gradle.kts`;
5. compilare Debug e Release;
6. testare login Google e caricamento libreria;
7. testare ricerca YouTube e pagina Scopri;
8. testare risoluzione stream e auto-next;
9. testare NEXT/PREV, shuffle e repeat;
10. testare lock screen e Bluetooth;
11. testare Android Auto: browsing, ricerca, artwork e playback;
12. aggiornare questo documento e il PDF tecnico;
13. integrare l'upgrade solo dopo regressione completa superata.

InnerTubeX non deve essere aggiornato a caldo dall'app installata e non deve essere trattato come un plugin scaricabile: e' codice/dipendenza compilata dentro l'APK e richiede sempre una nuova build firmata di Lyra.

### 25.5 Checklist 0.3.4

- [ ] Build Release 0.3.4 SUCCESSFUL.
- [ ] GitHub Release `v0.3.4` pubblicata con `Lyra-0.3.4.apk` e `update.json`.
- [ ] 0.3.3 rileva automaticamente la 0.3.4.
- [ ] Banner e focus Aggiornamenti funzionano senza regressioni.
- [ ] APK scaricato nella cache privata di Lyra.
- [ ] Nessun APK 0.3.4 presente nella cartella Download.
- [ ] SHA-256 verificato prima dell'apertura dell'installer.
- [ ] Eventuale autorizzazione `Installa app sconosciute` gestita correttamente.
- [ ] Aggiornamento installato sopra la 0.3.3 senza disinstallazione.
- [ ] Login Google, impostazioni, libreria e sessione restano disponibili.
- [ ] Impostazioni -> Diagnostica mostra `0.3.4 (11)`.
- [ ] Impostazioni -> Diagnostica mostra la versione InnerTubeX compilata.
- [ ] Al primo avvio successivo l'APK temporaneo viene cancellato.
- [ ] `check-innertubex-update.ps1` rileva correttamente versione corrente e upstream.
- [ ] Nessuna regressione playback, lock screen, Bluetooth o Android Auto.

## 26. Lyra 0.3.5 - Android Auto artwork, Scopri per generi e brani piaciuti

Release candidata: **0.3.5** (`versionCode 12`). La patch nasce dai test reali su Android Auto e si concentra su cinque richieste: eliminare i placeholder con triangolo/punto esclamativo nelle sezioni `Dispositivo` e `Scopri`, ampliare `Scopri` con playlist YouTube Music organizzate per generi, aggiungere il cuore per i brani piaciuti, uniformare il nome `Dispositivo` e rendere completamente italiana l'azione casuale dei Preferiti in Android Auto.

### 26.1 Artwork Android Auto robusto

`AutoArtworkProvider` continua a fornire artwork locali `content://`, ma dalla 0.3.5 ogni elemento può registrare anche un artwork di fallback. Se la copertina remota YouTube o la copertina locale MediaStore non è disponibile/decodificabile, il provider usa automaticamente un'immagine Lyra incorporata nell'APK.

Sono state aggiunte risorse dedicate per:

- Scopri;
- Top musica Italia;
- Playlist;
- Generi musicali;
- Dispositivo;
- Brani;
- Album;
- Artisti;
- Preferiti;
- fallback musicale generico.

Questo evita che Android Auto debba renderizzare URI mancanti e riduce i casi in cui l'host mostra il triangolo con punto esclamativo.

### 26.2 Dispositivo

La voce principale `Sul dispositivo` viene rinominata in **Dispositivo** sia sul telefono sia in Android Auto. Le categorie Auto `Brani`, `Album` e `Artisti` hanno artwork dedicato; album e brani continuano a preferire la vera copertina MediaStore e usano il fallback soltanto quando manca.

La libreria locale rimane filtrata alla cartella Android `Music/` e relative sottocartelle, come introdotto nella 0.3.1.

### 26.3 Scopri - playlist YouTube Music per genere

Oltre a `Top musica Italia` e `Playlist da scoprire`, Lyra aggiunge la sezione **Generi musicali**. Le raccolte vengono cercate tramite InnerTubeX usando playlist in evidenza di YouTube Music, senza consumare la quota della YouTube Data API per ogni genere.

Generi iniziali:

- Pop;
- Rock;
- Hip Hop e Rap;
- Dance ed Elettronica;
- Indie;
- Jazz;
- Classica;
- Relax e Chill;
- Workout;
- Anni 80;
- Anni 90;
- Anni 2000.

Ogni genere espone fino a cinque playlist. I risultati vengono mantenuti in cache per 30 minuti. Android Auto presenta `Scopri -> Generi musicali -> Genere -> Playlist` senza aggiungere nuovi nodi alla root principale di Lyra.

### 26.4 Cuore e brani piaciuti YouTube

Le liste YouTube e la schermata `In riproduzione` mostrano un cuore:

- cuore vuoto: il video non risulta tra i Mi piace dell'account;
- cuore pieno: il video risulta piaciuto;
- pressione: aggiunge/rimuove il rating YouTube.

Lyra legge i rating tramite `videos.getRating` in batch e modifica il rating tramite `videos.rate`. I brani locali non mostrano il cuore, perché non appartengono all'account YouTube.

Per scrivere i Mi piace è necessario lo scope OAuth:

`https://www.googleapis.com/auth/youtube.force-ssl`

La 0.3.5 sostituisce quindi il precedente scope `youtube.readonly` con `youtube.force-ssl`. Dopo l'aggiornamento può essere richiesta **una nuova autorizzazione Google una sola volta**. Durante la fase Testing, lo scope deve essere presente nella configurazione Data Access di Google Auth Platform e l'account deve essere tra gli utenti di test.

Questa modifica non salva password o token su disco: il token rimane gestito dal flusso Google già usato da Lyra.

### 26.5 Preferiti Android Auto completamente in italiano

Quando viene aperta la playlist di sistema dei video piaciuti, Lyra forza il titolo contestuale `Preferiti` anche se la Data API restituisce un titolo localizzato diversamente (ad esempio `Liked videos`). L'azione iniziale viene mostrata come:

`Riproduci brani in ordine casuale`

con sottotitolo `Preferiti`.

### 26.6 Checklist 0.3.5

- [ ] `assembleRelease` SUCCESSFUL.
- [ ] Aggiornamento 0.3.4 -> 0.3.5 tramite updater Lyra.
- [ ] Nuova autorizzazione Google `youtube.force-ssl` completata.
- [ ] Login, playlist personali e avatar ancora funzionanti.
- [ ] Cuore vuoto/pieno coerente con i Mi piace YouTube.
- [ ] Aggiunta di un Mi piace: il brano compare in Preferiti dopo riapertura/refresh.
- [ ] Rimozione di un Mi piace: il brano viene rimosso da Preferiti dopo riapertura/refresh.
- [ ] Scopri mostra i generi musicali e apre le relative playlist.
- [ ] Android Auto -> Scopri: nessun triangolo/punto esclamativo artwork.
- [ ] Android Auto -> Dispositivo: nessun triangolo/punto esclamativo artwork.
- [ ] Android Auto -> album locale senza artwork: fallback Lyra visibile.
- [ ] Android Auto -> Preferiti -> azione casuale interamente in italiano.
- [ ] Android Auto -> root mostra `Dispositivo` e non `Sul dispositivo`.
- [ ] NEXT/PREV, Shuffle, Repeat e auto-next senza regressioni.
- [ ] Log diagnostici senza token OAuth o URL firmati.

### 26.7 File principali modificati nella 0.3.5

- `app/build.gradle.kts` - versione 0.3.5 / code 12.
- `core/model/DiscoverySnapshot.kt` - sezioni generi.
- `youtube/repository/YouTubeMusicRepository.kt` - ricerca playlist YouTube Music per genere e cache.
- `youtube/auth/YouTubeAuthorizationManager.kt` - scope `youtube.force-ssl`.
- `youtube/api/YouTubeDataApiClient.kt` - lettura/scrittura rating YouTube.
- `youtube/likes/YouTubeLikesManager.kt` - stato condiviso dei cuori.
- `ui/common/YouTubeLikeButton.kt` - componente cuore riutilizzabile.
- `ui/playlist/PlaylistScreen.kt` - cuore nelle playlist.
- `ui/search/SearchScreen.kt` - cuore nei risultati.
- `ui/player/NowPlayingScreen.kt` - cuore sul brano corrente.
- `ui/discover/DiscoverScreen.kt` - generi musicali.
- `ui/local/LocalLibraryScreen.kt` - nome `Dispositivo`.
- `playback/AutoArtworkProvider.kt` - fallback artwork.
- `playback/PlaybackService.kt` - artwork Auto, generi Auto, localizzazione Preferiti/Dispositivo.
- `res/drawable-nodpi/auto_art_*.png` - immagini fallback Android Auto.

---

## 27. Lyra 0.3.6 - controlli Android Auto e impostazioni audio

Versione: **0.3.6** (`versionCode 13`).

### 27.1 Cuore nel player Android Auto

La schermata di riproduzione Android Auto riceve una nuova azione cuore per i brani YouTube. Il pulsante usa un comando Media3 personalizzato, cambia icona fra cuore vuoto e pieno in base al rating corrente e richiama lo stesso `YouTubeLikesManager` usato dalla UI telefono. I brani locali non espongono il cuore.

Android Auto resta responsabile della disposizione finale: Lyra fornisce Shuffle, Repeat e Cuore come preferenze Media3; sui display che non hanno spazio sufficiente il cuore puo' comparire nel menu overflow.

### 27.2 Normalizzazione audio

In `Impostazioni -> Audio` compare il toggle **Normalizzazione audio**, disattivato per default. Per i brani YouTube Lyra legge il loudness disponibile dal player endpoint e applica una correzione conservativa:

- brani troppo forti: attenuazione tramite volume lineare del player;
- brani piu' quieti: amplificazione limitata tramite `LoudnessEnhancer`;
- differenze inferiori a 0,75 dB: nessuna correzione;
- guadagno massimo: +6 dB;
- attenuazione massima: -8 dB.

Se un file locale non contiene dati loudness/ReplayGain utilizzabili, Lyra non inventa un valore e lo riproduce al livello originale. Una futura scansione EBU R128/ReplayGain della libreria locale potra' estendere la normalizzazione anche a questi file.

### 27.3 Qualita' streaming

In `Impostazioni -> Audio` e' disponibile **Qualita' streaming** con tre modalita':

- `Automatica` -> InnerTubeX `AudioQuality.AUTO`;
- `Alta` -> `AudioQuality.HIGH`;
- `Risparmio dati` -> `AudioQuality.LOW`.

La qualita' predefinita resta **Alta**, quindi l'aggiornamento non cambia il comportamento audio esistente finche' l'utente non modifica l'impostazione. La nuova selezione viene usata dalla successiva risoluzione dello stream.

### 27.4 Checklist 0.3.6

- [ ] `assembleRelease` SUCCESSFUL.
- [ ] Aggiornamento 0.3.5 -> 0.3.6 tramite updater Lyra.
- [ ] Android Auto: cuore visibile o disponibile nell'overflow durante un brano YouTube.
- [ ] Android Auto: cuore vuoto -> tap -> cuore pieno e brano presente nei piaciuti.
- [ ] Android Auto: cuore pieno -> tap -> rimozione del Mi piace.
- [ ] Android Auto: Shuffle e Repeat continuano a funzionare senza regressioni.
- [ ] Normalizzazione OFF: volume e comportamento identici alla 0.3.5.
- [ ] Normalizzazione ON: passaggio fra brani YouTube con livelli differenti piu' uniforme.
- [ ] Normalizzazione ON: nessun clipping/pumping evidente su cuffie, speaker e Bluetooth.
- [ ] File locale senza loudness: riproduzione invariata e senza crash.
- [ ] Qualita' Alta: playback regolare.
- [ ] Qualita' Automatica: playback regolare.
- [ ] Risparmio dati: playback regolare e bitrate selezionato ridotto quando disponibile.
- [ ] Cambio qualita' non interrompe il brano corrente e si applica al successivo stream risolto.
- [ ] Lock screen, Bluetooth, NEXT/PREV, seek, queue e resume senza regressioni.

### 27.5 File principali modificati nella 0.3.6

- `app/build.gradle.kts` - versione 0.3.6 / code 13.
- `audio/AudioSettings.kt` - preferenze persistenti normalizzazione/qualita'.
- `audio/AudioNormalizationController.kt` - applicazione del guadagno per sessione audio.
- `playback/PlaybackSource.kt` - metadato loudness opzionale.
- `playback/YouTubePlaybackRepository.kt` - qualita' selezionabile e lettura loudness YouTube.
- `playback/LyraDeferredPlayback.kt` - callback quando una sorgente differita viene risolta.
- `playback/LyraPlayer.kt` - inoltro callback del resolver.
- `playback/PlaybackMediaItemFactory.kt` - loudness nei metadata del MediaItem diretto.
- `playback/PlaybackService.kt` - normalizzazione e cuore Android Auto.
- `ui/settings/SettingsScreen.kt` - nuova sezione Audio.
- `LyraApplication.kt` - inizializzazione preferenze audio.

---

## 28. Lyra 0.3.7 - ripristino sessione e Scopri solo YouTube Music

Versione: **0.3.7** (`versionCode 14`).

### 28.1 Ripristino ultima sessione opzionale

In `Impostazioni -> Riproduzione` compare il toggle **Ripristina ultima sessione**, attivo per default.

Quando l'opzione e' attiva Lyra conserva e ripristina:

- playlist/coda corrente;
- indice del brano;
- posizione del brano in millisecondi;
- stato Shuffle;
- stato Repeat;
- metadata e sorgente locale/YouTube necessari a ricostruire la sessione.

Alla riapertura dell'app il mini-player e il Now Playing mostrano l'ultimo brano nello stesso punto, **in pausa**. Il servizio Media3 ricostruisce inoltre una timeline differita della stessa coda: se Android Auto avvia Lyra senza che l'Activity sia stata aperta, la sessione precedente e' gia' disponibile in pausa e il primo PLAY prepara lo stream soltanto in quel momento.

La posizione viene salvata:

- ogni 2 secondi durante la riproduzione;
- immediatamente quando il player va in pausa;
- quando il task Android viene rimosso;
- durante `onDestroy()` del servizio;
- sui normali cambi di brano/queue gia' gestiti dalla persistenza esistente.

La chiusura realmente forzata del processo da parte del sistema non puo' garantire matematicamente l'ultimo millisecondo, ma il checkpoint a 2 secondi e il salvataggio sincrono su pausa/disconnessione riducono al minimo la perdita di posizione. Lo spegnimento dell'auto che porta il player in pausa produce un salvataggio sincrono del punto corrente.

Se l'opzione viene disattivata, Lyra continua a usare lo snapshot della queue durante la sessione corrente per NEXT/PREV e controller esterni, ma non lo ripristina al successivo cold start e rifiuta il `playback resumption` Media3.

### 28.2 Android Auto e playback resumption

`PlaybackService` utilizza due livelli complementari:

1. all'avvio del servizio ricostruisce la timeline persistita con MediaItem differiti, posizione, Shuffle e Repeat, lasciando `playWhenReady=false`;
2. `MediaLibrarySession.Callback.onPlaybackResumption()` continua a fornire la stessa coda/posizione ai controller che usano esplicitamente il meccanismo Media3 di resumption.

Un listener intercetta il primo PLAY su una sessione ripristinata in stato `IDLE` e chiama `prepare()`. In questo modo il servizio puo' mostrare immediatamente brano/posizione senza aprire una connessione di rete finche' l'utente non decide realmente di riprodurre.

### 28.3 Scopri esclusivamente dal catalogo YouTube Music

La pagina **Scopri** non usa piu' la classifica di singoli video ottenuta dalla YouTube Data API. La sezione `Top musica Italia`, che poteva includere video musicali YouTube, non viene piu' mostrata.

Le raccolte di Scopri provengono ora esclusivamente da InnerTubeX con client YouTube Music (`WEB_REMIX`) e filtro `FILTER_FEATURED_PLAYLIST`. Lyra accetta soltanto risultati `PlaylistItem`.

La schermata contiene:

- **Playlist YouTube Music** - raccolte in evidenza recuperate con query musicali dedicate;
- **Generi musicali** - Pop, Rock, Hip Hop/Rap, Dance/Elettronica, Indie, Jazz, Classica, Relax/Chill, Workout, anni 80/90/2000.

Le playlist provenienti da Scopri ricevono internamente il prefisso `lyra-ytm:`. Questo permette a Lyra di distinguerle dalle playlist personali e di aprirne il contenuto direttamente tramite InnerTubeX/YouTube Music anche se l'account Google non e' necessario per quella raccolta pubblica.

Android Auto usa lo stesso catalogo: sotto `Scopri` sono esposte soltanto **Playlist YouTube Music** e **Generi musicali**. Non viene piu' proposto il nodo dei singoli brani Top YouTube.

### 28.4 Checklist 0.3.7

- [ ] `assembleRelease` SUCCESSFUL.
- [ ] Aggiornamento 0.3.6 -> 0.3.7 tramite updater Lyra.
- [ ] `Ripristina ultima sessione` visibile e attivo per default.
- [ ] Avviare un brano, avanzare almeno a meta', mettere in pausa, chiudere l'app: alla riapertura stesso brano e stessa posizione, in pausa.
- [ ] Durante la riproduzione chiudere brutalmente il task: alla riapertura posizione persa non superiore a circa 2 secondi.
- [ ] Android Auto: riprodurre un brano, spegnere l'auto/scollegare Auto, riavviare Auto: stesso brano e posizione disponibili in pausa.
- [ ] Android Auto: premendo PLAY dopo il restore, lo stream parte dalla posizione salvata.
- [ ] Shuffle e Repeat conservati dopo riavvio app e dopo riavvio Android Auto.
- [ ] Disattivare `Ripristina ultima sessione`, terminare il processo e riaprire: nessuna sessione precedente ricostruita.
- [ ] Scopri smartphone: assenza del riquadro `Top musica Italia` con singoli video.
- [ ] Scopri smartphone: `Playlist YouTube Music` contiene soltanto playlist restituite dal catalogo Music.
- [ ] Scopri -> generi: apertura e playback delle playlist regolari.
- [ ] Android Auto -> Scopri: solo `Playlist YouTube Music` e `Generi musicali`.
- [ ] Android Auto -> Scopri: playlist pubbliche apribili anche tramite InnerTubeX senza dipendere dalla Data API per il loro contenuto.
- [ ] Cuore, Shuffle, Repeat, normalizzazione e qualita' streaming senza regressioni.
- [ ] Lock screen, Bluetooth, NEXT/PREV, seek e coda senza regressioni.

### 28.5 File principali modificati nella 0.3.7

- `app/build.gradle.kts` - versione 0.3.7 / code 14.
- `playback/PlaybackResumeSettings.kt` - nuova preferenza persistente per il restore.
- `LyraApplication.kt` - inizializzazione preferenza restore.
- `playback/PlaybackViewModel.kt` - restore UI condizionato dall'impostazione.
- `playback/PlaybackService.kt` - hydration in pausa, checkpoint 2 s, `onTaskRemoved`, playback resumption condizionato e catalogo Auto Scopri aggiornato.
- `ui/settings/SettingsScreen.kt` - nuova sezione Riproduzione.
- `youtube/repository/YouTubeMusicRepository.kt` - Scopri solo playlist YouTube Music tramite InnerTubeX.
- `ui/discover/DiscoverScreen.kt` - rimozione Top video e nuova intestazione playlist YouTube Music.
- `ui/discover/DiscoverViewModel.kt` - diagnostica aggiornata del caricamento Scopri.



---

## 29. Lyra 0.3.8 - resume Android Auto, riapertura player e Scopri a cartelle

Versione: **0.3.8** (`versionCode 15`).

### 29.1 Fix resume Android Auto

Individuato un comportamento critico nella gestione dei controller esterni: nella 0.3.7 ogni comando `STOP` ricevuto da Android Auto/System UI cancellava lo snapshot persistito e svuotava la timeline. Alcuni host possono inviare `STOP` durante scollegamento o teardown, facendo perdere proprio la sessione necessaria al successivo resume.

Dalla 0.3.8:

- lo `STOP` proveniente da controller esterni conserva queue e posizione persistite;
- il player puo' fermarsi, ma lo snapshot resta disponibile al successivo cold start;
- lo Stop esplicito dalla UI Lyra continua invece a cancellare la sessione come prima;
- restano attivi `MediaButtonReceiver`, hydration della timeline e `onPlaybackResumption()`;
- Shuffle, Repeat, indice e posizione continuano a essere ripristinati insieme alla queue.

### 29.2 Tap sul lettore/notifica riapre Lyra

La `MediaLibrarySession` ora espone una `sessionActivity` tramite `PendingIntent` verso `MainActivity`. Questo permette al player di sistema/notifica di riaprire correttamente Lyra anche quando la UI era stata chiusa mentre il servizio continuava a riprodurre.

`MainActivity` usa inoltre `launchMode=singleTop` per evitare istanze duplicate quando viene richiamata dalla MediaSession.

### 29.3 Scopri 2.0 a cartelle

La sezione **Scopri** viene riorganizzata in cartelle di primo livello:

- **Novita'** - nuove uscite, hit del momento, novita' italiane e virali;
- **Mood** - Relax, Chill, Energia, Party, Romantica, Concentrazione, Malinconica;
- **Generi** - Pop, Rock, Hip Hop/Rap, Dance/Elettronica, Indie, R&B/Soul, Metal, Jazz, Classica, Reggae, Latina;
- **Attivita'** - Workout, Running, Guida, Studio, Lavoro, Cena, Sonno;
- **Decenni** - anni 60, 70, 80, 90, 2000 e 2010;
- **Nazioni e scene** - Italia, Stati Uniti, Regno Unito, Francia, Germania, Spagna, America Latina e Corea/K-Pop.

Le cartelle sono caricate in modalita' lazy: l'elenco delle categorie e' immediato e le query InnerTubeX partono soltanto quando l'utente apre una cartella. Ogni cartella mantiene una cache indipendente di 30 minuti. I risultati continuano a essere esclusivamente `PlaylistItem` provenienti dal catalogo YouTube Music.

### 29.4 Android Auto Scopri 2.0

Android Auto espone la stessa gerarchia:

`Scopri -> cartella -> gruppo -> playlist`.

La root principale di Lyra resta invariata e compatta (`Preferiti`, `Le mie playlist`, `Scopri`, `Dispositivo`); soltanto il contenuto interno di `Scopri` viene ampliato. Le playlist pubbliche mantengono il prefisso interno `lyra-ytm:` e vengono aperte tramite InnerTubeX senza richiedere la YouTube Data API per il contenuto.

### 29.5 Checklist 0.3.8

- [ ] `assembleDebug` SUCCESSFUL.
- [ ] `assembleRelease` SUCCESSFUL.
- [ ] Aggiornamento 0.3.7 -> 0.3.8 tramite updater Lyra.
- [ ] Smartphone: avviare un brano, chiudere la UI, toccare il player/notifica -> Lyra si riapre.
- [ ] Smartphone: playback in background continua senza regressioni.
- [ ] Android Auto: ascoltare una playlist, spegnere/scollegare Auto, ricollegare senza aprire l'app -> stessa queue disponibile.
- [ ] Android Auto: PLAY dopo cold restore riparte dalla posizione salvata.
- [ ] Android Auto: uno STOP/disconnect esterno non cancella piu' lo snapshot persistito.
- [ ] Stop esplicito dall'app continua a cancellare la sessione.
- [ ] Shuffle e Repeat restano corretti dopo restore.
- [ ] Scopri smartphone mostra le sei cartelle principali.
- [ ] Apertura cartelle Scopri carica gruppi e playlist senza bloccare l'apertura iniziale.
- [ ] Refresh della cartella forza il nuovo caricamento.
- [ ] Android Auto -> Scopri mostra le stesse cartelle.
- [ ] Android Auto -> cartella -> gruppo -> playlist navigabile e riproducibile.
- [ ] Cuore, normalizzazione, qualita' streaming, lock screen, Bluetooth, NEXT/PREV e seek senza regressioni.

### 29.6 File principali modificati nella 0.3.8

- `app/build.gradle.kts` - versione 0.3.8 / code 15.
- `AndroidManifest.xml` - `MainActivity` singleTop; invariato `MediaButtonReceiver`.
- `core/model/DiscoverySnapshot.kt` - nuovi modelli cartella/gruppo Scopri.
- `playback/PlaybackService.kt` - sessionActivity, STOP esterno non distruttivo e gerarchia Scopri Android Auto.
- `youtube/repository/YouTubeMusicRepository.kt` - catalogo Scopri lazy per cartelle e cache indipendente.
- `ui/discover/DiscoverViewModel.kt` - apertura/refresh cartella on-demand.
- `ui/discover/DiscoverScreen.kt` - nuova navigazione a cartelle.


---

## 30. Lyra 0.3.9 - Scopri robusto, resume e ricerca vocale Android Auto

Versione: **0.3.9** (`versionCode 16`).

### 30.1 Scopri: piu playlist per categoria

- il limite per ogni gruppo passa da 4 a 10 playlist;
- se la query principale restituisce pochi risultati Lyra prova query di fallback mirate;
- vengono esclusi podcast e risultati senza identificativo/titolo validi;
- per i risultati YouTube Music viene preferito il `playlistId` del play endpoint quando disponibile;
- cache per singolo gruppo oltre alla cache per cartella.

### 30.2 Playlist che restano in caricamento

Il caricamento delle playlist pubbliche InnerTubeX non puo piu inseguire continuation quasi senza limite:

- timeout di 15 s per la prima pagina;
- timeout di 6 s per ogni continuation;
- massimo 12 pagine di continuation;
- se una continuation successiva fallisce vengono mantenuti i brani gia caricati;
- eliminazione dei brani duplicati per video id.

Questo evita che una playlist problematica mantenga indefinitamente la UI in stato di caricamento.

### 30.3 Scopri Android Auto piu rapido

Entrando in una cartella (`Generi`, `Mood`, `Nazioni`, ecc.) Android Auto riceve subito l'elenco locale delle sottocategorie. La rete viene interrogata solo quando viene aperto il singolo gruppo, invece di caricare tutte le playlist della cartella in anticipo.

### 30.4 Auto resume rafforzato

Corretto un caso in cui Android Auto, durante riconnessione/cold start, poteva risalvare la stessa queue con posizione `0 ms` prima del ripristino. Se la richiesta appartiene alla stessa queue persistita e non e una nuova selezione esplicita, Lyra conserva ora la posizione salvata.

Restano validi:

- checkpoint ogni 2 secondi;
- commit sincrono su pausa, task removal e distruzione servizio;
- `MediaButtonReceiver`;
- `onPlaybackResumption()`;
- restore del player in pausa senza rete fino al primo PLAY.

Nota: il comando Android **Forza arresto** impedisce per design al sistema/Android Auto di riavviare automaticamente l'app fino a una nuova apertura esplicita; non e un caso correggibile dall'app.

### 30.5 Ricerca vocale Android Auto

Corretto il routing delle richieste provenienti da Google Assistant/Android Auto. Se una richiesta Media3 contiene `RequestMetadata.searchQuery`, la query vocale ha ora precedenza sul `mediaId` eventualmente ancora associato al brano corrente. In precedenza questo poteva far ripartire il vecchio brano e chiudere la ricerca. Le richieste prive sia di query sia di media id vengono rifiutate invece di trasformarsi in un playback di fallback.

### 30.6 Test 0.3.9

- [ ] Build Release SUCCESSFUL.
- [ ] Scopri -> Generi: fino a 10 playlist disponibili nei gruppi con risultati sufficienti.
- [ ] Aprire ripetutamente playlist Scopri: nessun caricamento infinito.
- [ ] Playlist con continuation problematica: mostrare i brani gia ottenuti oppure errore entro un tempo finito.
- [ ] Android Auto -> Scopri: cartelle e gruppi si aprono rapidamente e la rete parte sul singolo gruppo.
- [ ] Riprodurre una playlist, avanzare, scollegare/chiudere il task e riconnettere Android Auto senza aprire Lyra: stessa queue/brano/posizione.
- [ ] Verificare resume dopo processo ucciso dal sistema (non Forza arresto).
- [ ] Android Auto -> Cerca -> dettatura: la query viene usata e non viene riavviato il brano precedente.
- [ ] `playFromSearch` dell'Assistente avvia il primo risultato della query e non il contenuto precedente.
- [ ] NEXT/PREV, Shuffle, Repeat, cuore, normalizzazione e qualita senza regressioni.


---

## 31. Lyra 0.3.10 - Discovery Italia/Europa e Preferiti live

Versione: **0.3.10** (`versionCode 17`).

Questa patch lascia volutamente invariati autoresume e ricerca vocale Android Auto e si concentra su tre aree: qualita/quantita di Scopri, contatori e artwork delle categorie, aggiornamento immediato dei Preferiti.

### 31.1 Scopri: piu playlist realmente utilizzabili

La ricerca delle playlist YouTube Music non si ferma piu alla prima pagina dei risultati. Lyra segue ora anche la continuation della ricerca, con limiti temporali e di paginazione per evitare richieste troppo lunghe.

Per ogni gruppo:

- fino a **16 playlist** mostrate;
- fino a tre query regionali alternative;
- fino a due pagine di ricerca per query;
- deduplicazione tramite ID playlist stabile;
- priorita a risultati con riferimenti Italia/Europa;
- esclusione di podcast e risultati palesemente fuori target (ad esempio Bollywood/India, Ucraina/Russia, K-Pop e titoli in alfabeti non europei).

Il catalogo locale delle query viene spostato nel nuovo `DiscoveryCatalog.kt`, separando la configurazione editoriale dalla logica di rete.

### 31.2 ID playlist stabile e caricamento finito

Le playlist Scopri usano ora sempre l'ID stabile del `PlaylistItem` restituito da YouTube Music, rimuovendo l'eventuale prefisso `VL`. Non viene piu preferito il `playlistId` del `playEndpoint`, che puo identificare mix/radio dinamici e non sempre una playlist apribile come raccolta normale.

Il caricamento del contenuto e inoltre piu conservativo:

- timeout prima pagina: 8 secondi;
- timeout continuation: 3 secondi;
- massimo 3 pagine complessive;
- una playlist che risponde senza brani viene chiusa con errore invece di restare in attesa;
- se una continuation successiva fallisce restano disponibili i brani gia caricati.

### 31.3 Discovery orientata a Italia ed Europa

La cartella `Nazioni e scene` diventa **Italia & Europa**. Le scene proposte sono:

- Italia;
- Europa;
- Regno Unito;
- Francia;
- Germania;
- Spagna;
- Paesi Bassi;
- Scandinavia.

Le query delle altre cartelle sono state orientate allo stesso mercato. Nei **Generi** vengono separati e ampliati:

- Dance;
- House;
- Techno;
- EDM ed Elettronica;
- Pop;
- Rock;
- Hip Hop e Rap;
- Indie;
- R&B e Soul;
- Metal;
- Jazz;
- Classica;
- Reggae;
- Cantautorato italiano.

### 31.4 Navigazione Scopri realmente lazy

Su smartphone l'apertura di una cartella non carica piu in parallelo tutte le sottocategorie. La navigazione diventa:

`Scopri -> cartella -> gruppo -> playlist`.

Le query partono soltanto quando viene aperto il singolo gruppo. Questo riduce il numero di richieste contemporanee e impedisce che una sottocategoria lenta blocchi l'intera cartella.

### 31.5 Contatori corretti

I contatori non usano piu `0 playlist` per indicare uno stato non ancora caricato.

- le cartelle principali mostrano il numero locale e reale delle categorie;
- un gruppo mai aperto mostra **Apri per caricare**;
- dopo il caricamento mostra il numero reale di playlist trovate;
- Android Auto usa gli stessi metadata e la stessa cache dei gruppi.

### 31.6 Artwork e icone Scopri

Sul telefono le sei cartelle principali usano icone Material dedicate invece della cartella generica:

- Novita -> New Releases;
- Mood -> Mood;
- Generi -> Library Music;
- Attivita -> Fitness;
- Decenni -> History;
- Italia & Europa -> Public/Globe.

Per Android Auto sono stati aggiunti nuovi artwork 512x512, uniformi e piu sobri, con fondo Lyra scuro, bordo viola e pittogrammi lineari dedicati. Anche l'artwork principale di `Scopri` viene aggiornato allo stesso stile.

### 31.7 Preferiti aggiornati immediatamente

`YouTubeLikesManager` pubblica ora un evento applicativo dopo ogni modifica del rating riuscita e mantiene un piccolo overlay delle modifiche recenti.

Effetti:

- il cuore cambia subito nella UI;
- se `Preferiti` e gia aperta, il brano viene aggiunto/rimosso immediatamente;
- se `Preferiti` viene aperta subito dopo il Like/Unlike, l'overlay locale viene applicato anche se la Data API non ha ancora propagato la modifica;
- la cache Preferiti gia caricata da Android Auto viene aggiornata nello stesso processo;
- al cambio account l'overlay viene azzerato insieme alla cache rating.

### 31.8 Test 0.3.10

- [ ] `assembleDebug` SUCCESSFUL.
- [ ] `assembleRelease` SUCCESSFUL.
- [ ] Aggiornamento 0.3.9 -> 0.3.10 mantenendo login, impostazioni e firma.
- [ ] Scopri smartphone: sei cartelle con icone dedicate e nessuna icona cartella generica.
- [ ] Cartelle principali: numero categorie corretto.
- [ ] Gruppo mai aperto: mostra `Apri per caricare`, mai `0 playlist`.
- [ ] Dopo apertura gruppo: conteggio reale delle playlist.
- [ ] Generi: Dance, House, Techno ed EDM/Elettronica presenti separatamente.
- [ ] Generi principali: obiettivo 10-16 playlist quando YouTube Music restituisce risultati sufficienti.
- [ ] Italia & Europa: nessuna voce USA, America Latina o Corea/K-Pop nel catalogo locale.
- [ ] Campione di almeno 20 playlist Scopri: apertura entro tempo finito e presenza di brani oppure errore esplicito.
- [ ] Nessuna playlist resta indefinitamente con spinner vuoto.
- [ ] Android Auto -> Scopri: artwork nuovi visibili per le sei cartelle.
- [ ] Android Auto -> cartella: gruppi immediati senza caricamento rete globale.
- [ ] Android Auto -> gruppo: conteggio aggiornato dopo il primo caricamento.
- [ ] Like da playlist/ricerca/Now Playing: cuore aggiornato immediatamente.
- [ ] Aprire Preferiti subito dopo un Like: nuovo brano presente senza riavviare Lyra.
- [ ] Unlike mentre Preferiti e aperta: brano rimosso immediatamente.
- [ ] Android Auto: riaprendo Preferiti dopo Like/Unlike la cache locale riflette la modifica.
- [ ] Ricerca vocale Android Auto 0.3.9 senza regressioni.
- [ ] NEXT/PREV, Shuffle, Repeat, normalizzazione e qualita streaming senza regressioni.

### 31.9 File principali modificati nella 0.3.10

- `app/build.gradle.kts` - versione 0.3.10 / code 17.
- `core/model/DiscoverySnapshot.kt` - conteggi cartelle/gruppi.
- `youtube/repository/DiscoveryCatalog.kt` - nuovo catalogo Italia/Europa e query per gruppi.
- `youtube/repository/YouTubeMusicRepository.kt` - continuation ricerca, filtri regionali, ID playlist stabile e timeout piu stretti.
- `ui/discover/DiscoverViewModel.kt` - caricamento lazy del singolo gruppo.
- `ui/discover/DiscoverScreen.kt` - navigazione cartella/gruppo, contatori e icone Material dedicate.
- `youtube/likes/YouTubeLikesManager.kt` - eventi Like/Unlike, feedback ottimistico e overlay recente.
- `youtube/cache/YouTubeLibraryCache.kt` - lookup persistente della playlist Preferiti.
- `ui/playlist/PlaylistViewModel.kt` - aggiornamento live e overlay Preferiti.
- `playback/PlaybackService.kt` - contatori Scopri Android Auto, cache gruppi, nuovi artwork e Preferiti live.
- `res/drawable-nodpi/auto_art_discover*.png` - nuovo set artwork Scopri per Android Auto.


---

## 32. Lyra 0.3.11 - Radio automatica e Playlist Automatica

Versione: **0.3.11** (`versionCode 18`).

Questa patch non interviene sull'autoresume. Introduce invece una coda dinamica basata sul Watch Next/Automix di YouTube Music per due casi: brani scelti dalla ricerca e nuova voce `Playlist Automatica` dentro Scopri.

### 32.1 Cerca -> radio automatica

Quando l'utente tocca un singolo risultato nella schermata `Cerca`, il brano selezionato parte subito. In background Lyra interroga `YouTube.next(WatchEndpoint(videoId=...))` e prepara una queue di brani simili. Alla fine del brano cercato la riproduzione continua quindi automaticamente con l'Automix di YouTube Music, senza usare semplicemente i risultati successivi della query.

Su Android Auto la stessa logica viene applicata ai risultati di ricerca e alle richieste `playFromSearch`: il risultato scelto diventa il seed della radio.

### 32.2 Nuova voce Scopri: Playlist Automatica

`Playlist Automatica` compare come prima voce della pagina Scopri, sia su smartphone sia su Android Auto. Aprendola vengono mostrati preset di mood e genere. Un solo tap sul preset avvia la musica: non esiste una schermata di conferma.

Preset iniziali:

- Relax;
- Energia;
- Party;
- Concentrazione;
- Romantica;
- Pop;
- Rock;
- Hip Hop e Rap;
- Dance;
- House;
- Techno;
- EDM ed Elettronica;
- Indie;
- R&B e Soul;
- Metal;
- Jazz;
- Classica.

Per ogni preset Lyra esegue query orientate a Italia/Europa, sceglie casualmente un seed tra i primi risultati musicali e genera il relativo Automix.

### 32.3 Coda dinamica continua

La nuova `AutomaticPlaylistManager` conserva durante la vita del processo:

- endpoint Watch Next corrente;
- continuation YouTube Music;
- ID gia riprodotti/caricati;
- ultimo brano disponibile come seed di fallback.

Quando restano pochi brani nella queue, `PlaybackService` precarica un nuovo blocco. Se la continuation non e piu disponibile, Lyra usa l'ultimo brano come nuovo seed e genera un altro Automix. In questo modo la Playlist Automatica puo continuare senza chiedere altre scelte all'utente finche il processo/servizio rimane attivo.

La queue reale continua ad essere salvata in `PlaybackSessionStore`; endpoint e continuation non vengono trattati come dati permanenti. Un eventuale ripristino della radio automatica dopo morte completa del processo resta fuori dallo scope di questa patch e verra affrontato insieme al lavoro sull'autoresume.

### 32.4 Android Auto

Dentro `Scopri` Android Auto espone `Playlist Automatica` come nodo browsable. I preset sono elementi direttamente riproducibili: selezionandone uno Lyra crea la queue e parte senza ulteriori conferme.

Anche un brano scelto dai risultati di ricerca Android Auto viene trasformato in seed per la radio automatica.

### 32.5 Test 0.3.11

- [ ] `assembleDebug` SUCCESSFUL.
- [ ] `assembleRelease` SUCCESSFUL.
- [ ] Cerca smartphone: toccare un singolo risultato, terminato il brano parte automaticamente musica simile.
- [ ] La queue di una radio da Cerca contiene il seed scelto e successivi brani differenti.
- [ ] Scopri mostra `Playlist Automatica` come prima voce.
- [ ] Playlist Automatica -> Relax/Energia/Party: un solo tap avvia la musica.
- [ ] Playlist Automatica -> Pop/Rock/Rap/Dance/House/Techno/EDM: un solo tap avvia la musica.
- [ ] Raggiunta la coda finale, Lyra aggiunge nuovi brani senza intervento.
- [ ] Durante una Playlist Automatica, chiudere solo l'Activity: il servizio continua e puo rifornire la queue.
- [ ] Avviare una playlist normale dopo una radio automatica: la modalita automatica viene disattivata.
- [ ] Stop esplicito: modalita automatica azzerata.
- [ ] Android Auto -> Scopri -> Playlist Automatica: preset visibili e riproducibili.
- [ ] Android Auto -> Cerca: selezionare un risultato e verificare continuazione con brani simili.
- [ ] Preferiti live, Discovery 0.3.10, ricerca vocale, NEXT/PREV, Shuffle e Repeat senza regressioni.

### 32.6 File principali modificati nella 0.3.11

- `app/build.gradle.kts` - versione 0.3.11 / code 18.
- `youtube/repository/AutomaticPlaylistRepository.kt` - nuovo resolver seed/Watch Next/continuation e catalogo preset automatici.
- `playback/AutomaticPlaylistManager.kt` - stato process-local della coda dinamica e rifornimento.
- `playback/PlaybackViewModel.kt` - radio da Cerca, avvio preset automatici e sincronizzazione della queue estesa.
- `ui/search/SearchScreen.kt` - selezione singolo risultato collegata alla radio automatica.
- `ui/discover/DiscoverViewModel.kt` - nuova cartella locale `Playlist Automatica`.
- `ui/discover/DiscoverScreen.kt` - preset ad avvio immediato e stato di caricamento.
- `MainActivity.kt` - passaggio del `PlaybackViewModel` a Scopri.
- `playback/PlaybackService.kt` - browsing Android Auto, radio da ricerca Auto, prefetch e rifornimento coda automatica.

---

## 33. Lyra 0.3.12 - Cronologia, coda rapida, Scopri configurabile e playlist automatiche salvate

Versione: **0.3.12** (`versionCode 19`).

Questa patch non introduce il timer di spegnimento, rimosso dalla roadmap su richiesta. L'autoresume resta fuori dallo scope e non viene modificato. La 0.3.12 consolida invece funzioni utente isolate dal motore audio: cronologia locale, comandi coda, filtro geografico Scopri, salvataggio delle radio automatiche e filtro temporaneo delle playlist Scopri problematiche.

### 33.1 Preferiti completamente in italiano

Il titolo remoto `Liked songs` / `Liked videos` restituito dalla YouTube Data API viene normalizzato a **Preferiti** anche quando si apre direttamente la playlist. Il comportamento completa la localizzazione gia presente nella Home e in Android Auto.

### 33.2 Cronologia di ascolto locale

Lyra salva localmente gli ultimi brani realmente avviati, senza inviare dati a servizi aggiuntivi. La Home mostra i tre ascolti piu recenti in `Ascoltati di recente`; un tap riavvia il brano come coda singola. La cronologia conserva fino a 100 brani distinti e include anche sorgenti locali.

### 33.3 Riproduci dopo / Aggiungi alla coda

Le righe brano di `Cerca` e delle schermate playlist espongono un menu con:

- `Riproduci dopo` - inserisce il brano subito dopo quello corrente;
- `Aggiungi alla coda` - aggiunge il brano in fondo.

Poiche le playlist di Scopri vengono aperte nella stessa `PlaylistScreen`, gli stessi comandi valgono anche per Scopri. Un intervento manuale sulla coda termina il rifornimento della Playlist Automatica per evitare che la radio sovrascriva le scelte esplicite dell'utente.

### 33.4 Filtro geografico Scopri

In `Impostazioni -> Scopri` viene aggiunta `Area musicale preferita` con tre modalita persistenti:

- **Italia**;
- **Italia + Europa** - valore predefinito;
- **Internazionale**.

Il cambio modalita invalida le cache Discovery. In modalita Italia vengono privilegiate query italiane; Italia + Europa mantiene il catalogo editoriale della 0.3.10; Internazionale usa query globali e amplia `Nazioni e scene` anche a Stati Uniti, America Latina, Corea/K-Pop e Giappone.

### 33.5 Salva Playlist Automatica

Nella schermata Coda, durante una `Playlist Automatica` o una radio creata da `Cerca`, compare `Salva playlist automatica`. Lyra salva localmente la coda corrente con metadata e artwork, senza creare una playlist sul profilo YouTube.

Le raccolte salvate sono disponibili in:

`Scopri -> Playlist salvate -> Le tue playlist`

La stessa cartella viene esposta anche nel browser Android Auto sotto `Scopri`.

### 33.6 Discovery quality cache

Se una playlist proveniente da Scopri non apre la prima pagina entro il timeout o risulta senza brani, Lyra registra localmente l'ID come problematico per 24 ore. Durante quel periodo la playlist viene esclusa dalle nuove proposte Discovery. Una successiva apertura riuscita rimuove il blocco.

La blacklist e locale, temporanea e non contiene token, cookie o URL audio firmati.

### 33.7 Test 0.3.12

- [ ] `assembleDebug` SUCCESSFUL.
- [ ] `assembleRelease` SUCCESSFUL.
- [ ] Preferiti: apertura della playlist mostra sempre `Preferiti`, mai `Liked songs` / `Liked videos`.
- [ ] Home: `Ascoltati di recente` compare dopo il primo brano avviato.
- [ ] Cronologia: cambio brano aggiorna l'ordine senza duplicare lo stesso ID.
- [ ] Cerca: `Riproduci dopo` inserisce il brano immediatamente dopo quello corrente.
- [ ] Cerca: `Aggiungi alla coda` inserisce il brano in fondo.
- [ ] Playlist personale e Scopri: entrambi i comandi coda funzionano.
- [ ] Coda con Shuffle: aggiunta manuale non perde il brano corrente.
- [ ] Impostazioni -> Scopri: Italia / Italia + Europa / Internazionale persistono dopo riavvio.
- [ ] Cambio area: riaprendo Scopri i risultati riflettono la nuova modalita.
- [ ] Internazionale: `Nazioni e scene` include gruppi extra globali.
- [ ] Playlist Scopri vuota/timeout: errore finito e playlist non riproposta subito nelle nuove ricerche.
- [ ] Playlist Automatica: pulsante `Salva playlist automatica` visibile nella Coda.
- [ ] Dopo il salvataggio: raccolta presente in `Scopri -> Playlist salvate`.
- [ ] Playlist salvata: apertura, playback, NEXT/PREV e Shuffle funzionanti.
- [ ] Android Auto -> Scopri -> Playlist salvate: raccolte visibili e riproducibili.
- [ ] Playlist Automatica 0.3.11, ricerca vocale Auto, Preferiti live e Discovery 0.3.10 senza regressioni.

### 33.8 File principali modificati nella 0.3.12

- `app/build.gradle.kts` - versione 0.3.12 / code 19.
- `LyraApplication.kt` - inizializzazione dei nuovi store locali.
- `playback/ListeningHistoryStore.kt` - nuova cronologia locale persistente.
- `playback/SavedAutomaticPlaylistStore.kt` - salvataggio locale delle radio/playlist automatiche.
- `playback/PlaybackViewModel.kt` - cronologia, `Riproduci dopo`, `Aggiungi alla coda` e salvataggio queue automatica.
- `ui/home/HomeViewModel.kt` / `HomeScreen.kt` - sezione `Ascoltati di recente`.
- `ui/common/SongQueueMenu.kt` - menu riutilizzabile delle azioni coda.
- `ui/search/SearchScreen.kt` / `ui/playlist/PlaylistScreen.kt` - nuove azioni coda.
- `ui/queue/QueueScreen.kt` - salvataggio Playlist Automatica.
- `youtube/repository/DiscoverySettings.kt` - preferenza geografica persistente.
- `youtube/repository/DiscoveryQualityStore.kt` - esclusione temporanea delle playlist Scopri problematiche.
- `youtube/repository/DiscoveryCatalog.kt` / `YouTubeMusicRepository.kt` - query regionali dinamiche, gruppi internazionali e playlist salvate.
- `ui/settings/SettingsScreen.kt` - selettore area Scopri.
- `ui/discover/DiscoverViewModel.kt` - cartella `Playlist salvate`.
- `playback/PlaybackService.kt` - `Playlist salvate` nel browser Android Auto.
- `youtube/api/YouTubeDataApiClient.kt` - normalizzazione `Liked songs` / `Liked videos` -> `Preferiti`.


---

## 34. Lyra 0.3.13 - Cronologia completa e sezione Per te

Versione: **0.3.13** (`versionCode 20`).

La 0.3.13 estende la cronologia introdotta nella 0.3.12: ogni ascolto conserva ora lo snapshot completo della sessione e non soltanto il singolo brano. Aggiunge inoltre la prima personalizzazione locale di Lyra con la nuova cartella **Per te**. L'autoresume resta volutamente fuori dallo scope di questa patch.

### 34.1 Cronologia con coda completa

Ogni voce di Cronologia conserva:

- brano corrente;
- titolo della playlist/coda;
- tutti i brani presenti nella queue al momento dell'ascolto;
- indice del brano corrente;
- indicazione che la sessione fosse automatica;
- timestamp locale dell'ascolto.

Toccando un brano in `Ascoltati di recente`, Lyra ricrea quindi la stessa coda e riparte dallo stesso brano della playlist. Per le Playlist Automatiche viene ripristinata prima la coda storica gia costruita e poi riattivato il rifornimento automatico usando l'ultimo brano della coda come seed.

La cronologia 0.3.12 viene migrata in modo compatibile: le vecchie voci che contenevano un solo Song restano disponibili come code da un singolo brano.

### 34.2 Cronologia Android Auto

Android Auto espone `Cronologia` dentro `Le mie playlist`, mantenendo invariati i quattro nodi principali della root Lyra.

Ogni elemento mostra brano, artista e nome della coda originaria. Selezionandolo, Android Auto riceve la queue completa associata allo storico. Le sessioni automatiche riattivano anche la continuazione Automix dopo la coda gia salvata.

### 34.3 Profilo locale di ascolto

Nuovo `ListeningProfileStore`, completamente locale. Per ogni brano conserva segnali semplici:

- numero di avvii;
- ascolti completati;
- skip rapidi;
- stato Preferito conosciuto da Lyra;
- ultimo ascolto;
- contesto mood/genere quando ricavabile dal titolo della coda automatica.

Il punteggio personale favorisce ascolti ripetuti, completamenti e Preferiti e penalizza gli skip rapidi. Nessun dato viene inviato a servizi cloud aggiuntivi.

Alla prima esecuzione della 0.3.13, se il profilo e vuoto, Lyra usa la Cronologia gia presente come base iniziale senza inventare Preferiti o generi mancanti.

### 34.4 Scopri -> Per te

Nuova cartella **Per te** in Scopri, disponibile su smartphone e Android Auto.

Può contenere:

- **Il tuo mix quotidiano** - radio Automix avviata da uno dei brani con punteggio personale piu alto;
- **Preferiti recenti** - coda locale dei brani apprezzati recentemente e conosciuti dal profilo Lyra;
- **Riscoperte** - brani validi non ascoltati da almeno 14 giorni; se lo storico e ancora giovane vengono usati i meno recenti disponibili;
- **Il tuo Rock / Pop / House / Techno / Relax / ecc.** - fino a quattro mix automatici dei contesti musicali piu ascoltati.

I mix automatici usano come seed un brano realmente ascoltato e apprezzato dall'utente e poi delegano la continuazione al Watch Next / Automix di YouTube Music. `Preferiti recenti` e `Riscoperte` usano invece code locali ricavate dal profilo.

### 34.5 Segnali completamento e skip

Quando il brano cambia, Lyra valuta la posizione raggiunta rispetto alla durata:

- oltre circa l'80% viene considerato ascolto completato;
- un cambio molto rapido, sotto circa il 30% e nei primi 45 secondi, viene considerato skip;
- gli altri cambi non aggiungono segnali positivi o negativi.

Questa logica non modifica il player e serve esclusivamente al ranking locale di `Per te`.

### 34.6 Test 0.3.13

- [ ] `assembleDebug` SUCCESSFUL.
- [ ] `assembleRelease` SUCCESSFUL.
- [ ] Aggiornamento 0.3.12 -> 0.3.13 conserva la vecchia Cronologia.
- [ ] Avviare una playlist normale e cambiare brano: la voce Home mostra anche nome coda e numero brani.
- [ ] Toccare una voce Cronologia: riparte l'intera queue originaria dal brano registrato.
- [ ] Cronologia con brano appartenente a playlist di 20+ elementi: NEXT/PREV continuano nella queue storica.
- [ ] Cronologia di una Playlist Automatica: riparte la queue salvata e successivamente vengono aggiunti nuovi brani.
- [ ] Android Auto -> Le mie playlist -> Cronologia: cartella visibile.
- [ ] Android Auto -> Cronologia: selezione brano ricrea la coda originaria.
- [ ] Android Auto -> Cronologia automatica: la radio continua oltre la coda storica.
- [ ] Scopri -> Per te compare dopo che esiste almeno uno storico/profilo.
- [ ] `Il tuo mix quotidiano` parte con un singolo tap e continua con Automix.
- [ ] `Preferiti recenti` riflette i Like conosciuti da Lyra.
- [ ] `Riscoperte` produce una coda locale e non richiede rete per i metadata salvati.
- [ ] Dopo ascolti ripetuti di un preset automatico compaiono mix come `Il tuo House`, `Il tuo Rock`, ecc.
- [ ] Skip rapidi ripetuti riducono il peso del brano nel mix quotidiano.
- [ ] Un Preferito aumenta la priorita del brano nel profilo locale.
- [ ] Android Auto -> Scopri -> Per te: mix visibili e riproducibili.
- [ ] Playlist Automatica, Playlist salvate, filtro geografico Scopri, Preferiti live e ricerca vocale Auto senza regressioni.

### 34.7 File principali modificati nella 0.3.13

- `app/build.gradle.kts` - versione 0.3.13 / code 20.
- `LyraApplication.kt` - inizializzazione profilo locale e sincronizzazione eventi Like.
- `playback/ListeningHistoryStore.kt` - snapshot completo della queue e migrazione cronologia 0.3.12.
- `playback/ListeningProfileStore.kt` - nuovo profilo locale di gusto e ranking.
- `personalization/PersonalizedMixEngine.kt` - composizione dei mix `Per te`.
- `playback/AutomaticPlaylistManager.kt` - riattivazione di una coda automatica proveniente dalla Cronologia.
- `playback/PlaybackViewModel.kt` - restore Cronologia, segnali ascolto/skip/completamento e avvio mix personali.
- `ui/home/HomeViewModel.kt` / `HomeScreen.kt` - cronologia con contesto queue.
- `ui/discover/DiscoverViewModel.kt` / `DiscoverScreen.kt` - nuova cartella `Per te`.
- `MainActivity.kt` - selezione Cronologia collegata al restore della queue.
- `playback/PlaybackService.kt` - Cronologia e `Per te` nel browser Android Auto.

---

## 35. Lyra 0.3.14 - Cronologia Home deduplicata e radio automatica

Versione: **0.3.14** (`versionCode 21`).

Questa patch modifica esclusivamente l'esperienza `Ascoltati di recente` sulla Home. Gli snapshot completi della cronologia introdotti nella 0.3.13 restano memorizzati e continuano a essere usati da Android Auto e dal profilo locale `Per te`.

### 35.1 Ascoltati di recente come menu

La Home non mostra piu direttamente i tre ultimi brani. Al loro posto compare una singola card selezionabile **Ascoltati di recente** con il numero di brani unici presenti nello storico.

Toccando la card si apre una schermata dedicata con tutti i brani recenti disponibili.

### 35.2 Elenco senza duplicati

La schermata `Ascoltati di recente` deduplica per ID brano e mantiene l'occorrenza piu recente. Se lo stesso brano e stato ascoltato in playlist o code differenti, nella lista Home compare una sola volta.

La cronologia interna completa non viene eliminata o semplificata: la deduplicazione riguarda soltanto la presentazione della schermata smartphone.

### 35.3 Un brano recente avvia una nuova radio

Il tap su un brano della schermata `Ascoltati di recente` non ricostruisce la vecchia queue. Il brano scelto parte immediatamente e viene usato come seed del Watch Next / Automix di YouTube Music, con lo stesso meccanismo gia usato per un singolo risultato di `Cerca`.

La sequenza prevista e quindi:

1. parte il brano scelto dalla cronologia;
2. Lyra prepara in background la radio automatica;
3. terminato il brano, la riproduzione continua con musica simile per genere/stile;
4. la coda automatica continua a rifornirsi secondo la logica gia introdotta nella 0.3.11.

### 35.4 Android Auto

La Cronologia Android Auto introdotta nella 0.3.13 resta invariata in questa patch e continua a usare gli snapshot completi delle sessioni. La modifica 0.3.14 riguarda soltanto la voce `Ascoltati di recente` della Home smartphone.

### 35.5 Test 0.3.14

- [ ] `assembleDebug` SUCCESSFUL.
- [ ] `assembleRelease` SUCCESSFUL.
- [ ] Home: `Ascoltati di recente` compare come singola card/menu selezionabile.
- [ ] Il contatore della card mostra il numero di brani unici.
- [ ] Apertura della card: compare l'elenco completo dei brani recenti disponibili.
- [ ] Lo stesso brano ascoltato in due playlist differenti compare una sola volta.
- [ ] L'ordine mantiene in alto l'ascolto piu recente.
- [ ] Tap su un brano recente: il brano parte immediatamente.
- [ ] Durante il brano viene preparata una radio Automix.
- [ ] Alla fine del brano la riproduzione continua con musica simile senza conferme.
- [ ] La radio automatica continua a rifornire la coda.
- [ ] Android Auto -> Cronologia mantiene il comportamento 0.3.13 senza regressioni.
- [ ] `Per te`, Playlist Automatica, Preferiti live, Scopri e ricerca vocale Android Auto senza regressioni.

### 35.6 File principali modificati nella 0.3.14

- `app/build.gradle.kts` - versione 0.3.14 / code 21.
- `ui/home/HomeScreen.kt` - card/menu `Ascoltati di recente` con conteggio univoco.
- `ui/home/ListeningHistoryScreen.kt` - nuova schermata dei brani recenti deduplicati.
- `MainActivity.kt` - nuova route `history`.
- `playback/PlaybackViewModel.kt` - avvio del brano recente come seed di una nuova radio automatica.

---

## 36. Lyra 0.3.15 - Personalizzazione adattiva, Cloudflare e Android Auto compatto

Versione: **0.3.15** (`versionCode 22`).

La 0.3.15 consolida il ciclo 0.3.x con un nuovo motore di personalizzazione locale. Lyra continua a usare YouTube Music / Watch Next / Automix per ottenere candidati musicalmente correlati, ma non accetta piu' l'ordine in modo passivo: quando la personalizzazione e' attiva applica un secondo livello di ranking costruito dal comportamento reale dell'utente. Il motore resta completamente utilizzabile offline per i dati gia' appresi; la sincronizzazione Cloudflare e' una copia facoltativa e non e' necessaria al playback.

### 36.1 Segnali di ascolto raccolti nel PlaybackService

La raccolta dei segnali viene spostata nel `PlaybackService`, cosi' non dipende dall'apertura della UI Compose e funziona anche durante ascolti da lock screen, Bluetooth e Android Auto.

Per ogni brano Lyra puo' registrare localmente:

- avvio reale della riproduzione;
- tempo ascoltato e percentuale del brano;
- completamento;
- skip manuale distinto dallo skip precoce;
- skip precoce;
- ascolto parziale/neutro;
- Preferito / rimozione Preferito;
- ultimo ascolto e frequenza;
- contesto della sessione quando riconoscibile (House, Techno, Relax, Energia, ecc.);
- feedback esplicito `Piu' musica come questa`, `Meno di questo artista` e `Non riprodurre piu' questo brano`.

Uno skip non viene interpretato automaticamente come rifiuto: viene considerato segnale negativo forte soltanto quando il cambio e' realmente precoce (meno del 30% e nei primi 45 secondi). Un ascolto oltre circa l'85% viene considerato completato. Gli ascolti intermedi producono segnali molto piu' deboli.

### 36.2 Profilo locale con confidenza e decadimento temporale

`ListeningProfileStore` usa ora un profilo multidimensionale per brano e artista. Il punteggio non reagisce in modo definitivo a un singolo evento: i segnali impliciti vengono pesati con una **confidenza progressiva**, che cresce quando Lyra dispone di piu' ascolti dello stesso contenuto.

Il ranking considera:

- affinita' del singolo brano;
- affinita' dell'artista;
- percentuale media ascoltata;
- completamenti e skip precoci;
- Preferiti;
- feedback espliciti;
- frequenza e ultimo ascolto;
- affinita' con il mood/genere della sessione corrente;
- penalita' per brani e artisti ascoltati troppo recentemente.

I segnali impliciti piu' vecchi ricevono un decadimento morbido: non vengono eliminati, ma pesano meno dei gusti recenti. Il Preferito resta invece un segnale esplicito forte e non decade automaticamente.

### 36.3 RecommendationEngine - exploit, scoperta e fatigue

Nuovo `RecommendationEngine` fra Automix e coda Media3.

YouTube Music continua a fornire il pool di candidati musicalmente correlati. Lyra assegna quindi un punteggio personale e riordina il pool usando:

- posizione/rilevanza originale del candidato YouTube Music;
- affinita' brano e artista;
- contesto mood/genere;
- novita' controllata;
- penalita' di recenza;
- penalita' per duplicati nella coda recente;
- **artist fatigue**, per evitare sequenze troppo dense dello stesso artista;
- feedback `Meno di questo artista`;
- una piccola variabilita' deterministica per non produrre sempre la stessa classifica.

La quota di scoperta parte dalla scelta dell'utente (`Conservativo`, `Bilanciato`, `Esplorativo`) e viene corretta in modo limitato dal comportamento recente: molti skip precoci rendono Lyra piu' prudente, molti completamenti consentono leggermente piu' esplorazione. La preferenza scelta dall'utente resta sempre il limite principale.

### 36.4 Mood e genere come contesto dominante

La scelta esplicita dell'utente ha precedenza sul profilo generale. Se viene avviata `Playlist Automatica -> Relax`, `House`, `Techno`, `Energia`, ecc., il relativo preset diventa il contesto della sessione.

Il risultato e' quindi l'intersezione fra:

1. cio' che l'utente ha chiesto in quel momento;
2. i candidati coerenti prodotti da YouTube Music;
3. cio' che Lyra ha imparato sui gusti personali;
4. cio' che non e' stato ascoltato troppo recentemente.

Il profilo non forza quindi Rock durante una sessione Relax soltanto perche' Rock e' statisticamente il genere piu' ascoltato.

### 36.5 Feedback esplicito nel Now Playing

Accanto al cuore dei brani YouTube compare un menu di personalizzazione con:

- **Piu' musica come questa** - rafforza il brano e l'artista come riferimento;
- **Meno di questo artista** - applica una penalita' persistente ma non un blocco assoluto;
- **Non riprodurre piu' questo brano** - esclude il brano dai candidati futuri del motore Lyra.

Il feedback e' facoltativo: l'algoritmo continua a funzionare anche soltanto osservando ascolti, completamenti, skip e Preferiti.

### 36.6 Continuazione intelligente alla fine della coda

Nuova impostazione **Continua con musica simile**. Con Repeat OFF, quando una coda YouTube termina e non e' gia' una Playlist Automatica, Lyra usa l'ultimo brano come seed di una nuova radio e continua senza richiedere conferme.

Se la personalizzazione e' attiva, i candidati della continuazione vengono riordinati dal `RecommendationEngine`; se e' disattivata, la continuazione puo' restare attiva ma mantiene l'ordine nativo proposto da YouTube Music.

Sono quindi supportati nello stesso modello:

- risultato singolo di Cerca;
- brano scelto da Ascoltati di recente;
- Playlist Automatica;
- playlist personali e pubbliche;
- Preferiti;
- playlist Scopri.

### 36.7 Nuove impostazioni Personalizzazione

In `Impostazioni -> Personalizzazione` sono disponibili:

- **Personalizzazione musicale** ON/OFF;
- livello di scoperta `Conservativo / Bilanciato / Esplorativo`;
- `Usa la cronologia`;
- `Usa i Preferiti`;
- `Impara dagli skip`;
- `Evita ripetizioni frequenti`;
- `Continua con musica simile`;
- `Reimposta profilo musicale`.

Disattivando `Personalizzazione musicale`, Lyra non aggiorna il profilo comportamentale e non riordina Automix in base ai gusti. Le normali funzioni player, Cronologia e Preferiti continuano a funzionare.

### 36.8 Per te aggiornato

La sezione `Scopri -> Per te` usa il nuovo profilo. `Il tuo mix quotidiano` ruota il seed fra i migliori brani non locali invece di usare sempre lo stesso. I mix contestuali (`Il tuo House`, `Il tuo Rock`, `Il tuo Relax`, ecc.) usano un seed realmente associato a quel contesto e poi passano attraverso il ranking adattivo.

Restano disponibili `Preferiti recenti` e `Riscoperte`, ora alimentati dal profilo piu' ricco.

### 36.9 Sincronizzazione opzionale Cloudflare Worker + D1

Aggiunto il progetto `cloudflare/lyra-profile-sync/` con API Worker e schema D1.

Il motore locale resta la fonte primaria. Se l'utente abilita **Profilo Cloudflare**, Lyra sincronizza una copia pseudonima di:

- eventi di ascolto necessari all'apprendimento;
- completamenti, skip manuali, skip precoci, Like e feedback espliciti;
- brano e artista associati all'evento;
- contesto della sessione;
- snapshot aggregato del profilo appreso.

Non vengono inviati token Google/OAuth, e-mail, nome utente, cookie, URL CDN/audio firmati o identificatori hardware. Ogni installazione usa un UUID casuale e un segreto casuale; il Worker salva soltanto l'hash SHA-256 del segreto.

La sincronizzazione e' **opt-in e OFF per default**. Senza endpoint Cloudflare configurato la relativa opzione resta disabilitata. Da Impostazioni e' possibile forzare una sincronizzazione o richiedere l'eliminazione dei dati remoti associati a quell'installazione.

Il client riceve l'endpoint a build time tramite `LYRA_PROFILE_SYNC_URL` in `local.properties` oppure variabile ambiente. Nessuna API key amministrativa Cloudflare viene inserita nell'APK.

Il backend usa D1 tramite binding `DB`. La configurazione primaria fornita e' `wrangler.jsonc.example`, coerente con il formato consigliato per i nuovi progetti Workers; resta anche un esempio TOML per compatibilita'. Per una distribuzione principalmente europea e' consigliata la creazione del database D1 con giurisdizione `eu`, da scegliere alla creazione del database.

Gli upload sono spezzati in blocchi da **40 eventi per richiesta**: in questo modo auth, batch eventi e snapshot del profilo restano entro un margine prudente anche sui limiti D1 piu' bassi. Se esiste arretrato, il client prosegue automaticamente con richieste successive. La 0.3.15 scrive direttamente in D1: il volume di un player e' ridotto e questa scelta rende immediata/deterministica la cancellazione del profilo. Cloudflare Queues resta prevista come evoluzione per elaborazioni aggregate o collaborative su volumi maggiori.

### 36.10 Android Auto - cartelle piu' compatte e artwork moderno

Le copertine musicali di album/playlist/brani restano invariate. Solo i nodi-cartella di navigazione ricevono ora l'hint Media3 per essere rappresentati come **category list item**, piu' compatto rispetto alla precedente resa a griglia quando l'host Android Auto lo rispetta.

La dimensione finale e' comunque controllata dall'host Android Auto/AAOS: Lyra fornisce il suggerimento per-item corretto, evitando di ridurre artificialmente bitmap che l'host potrebbe poi riscalare.

Sono stati inoltre ridisegnati gli artwork Auto-only di:

- Scopri;
- Novita';
- Mood;
- Generi;
- Attivita';
- Decenni;
- Italia & Europa;
- Cronologia;
- Playlist Automatica;
- Per te;
- Playlist salvate;
- cartella generica.

Il nuovo set usa simboli semplici e coerenti su fondo Lyra scuro; non modifica le icone/copertine visibili nell'app smartphone.

### 36.11 Checklist 0.3.15

- [ ] `assembleDebug` SUCCESSFUL.
- [ ] `assembleRelease` SUCCESSFUL.
- [ ] Aggiornamento 0.3.14 -> 0.3.15 conserva Cronologia, Preferiti, playlist salvate e impostazioni esistenti.
- [ ] Personalizzazione OFF: radio e Automix funzionano senza ranking personale.
- [ ] Personalizzazione ON: completamenti ripetuti aumentano gradualmente la priorita' senza sovrareagire al primo ascolto.
- [ ] Skip entro 30%/45 s penalizza; skip tardivo non viene trattato come rifiuto forte.
- [ ] Preferito e `Piu' musica come questa` aumentano chiaramente la priorita'.
- [ ] `Meno di questo artista` riduce la presenza dell'artista nelle radio successive.
- [ ] `Non riprodurre piu' questo brano` impedisce la ricomparsa del brano nelle code generate da Lyra.
- [ ] Sessione House/Techno/Relax mantiene il contesto scelto anche se il profilo generale preferisce altri generi.
- [ ] Molti skip precoci rendono la scoperta leggermente piu' conservativa; molti completamenti la rendono leggermente piu' esplorativa.
- [ ] `Evita ripetizioni frequenti` riduce duplicati e sequenze dello stesso artista.
- [ ] Fine coda con `Continua con musica simile` ON: la riproduzione continua senza conferme.
- [ ] Fine coda con opzione OFF: comportamento tradizionale, riproduzione terminata.
- [ ] Lock screen/Bluetooth/Android Auto alimentano il profilo anche senza Activity aperta.
- [ ] `Per te` usa il nuovo ranking senza regressioni.
- [ ] Android Auto: le cartelle supportate vengono rese come category-list quando l'host accetta il content-style hint.
- [ ] Android Auto: copertine musicali reali restano normali e non vengono sostituite dagli artwork cartella.
- [ ] Android Auto: nuove icone Scopri/Cronologia/Per te leggibili e coerenti.
- [ ] Build senza `LYRA_PROFILE_SYNC_URL`: Cloudflare indicato come non configurato e nessuna rete aggiuntiva per il profilo.
- [ ] Cloud sync configurato ma OFF: nessun upload del profilo.
- [ ] Cloud sync ON: eventi e snapshot arrivano al Worker e vengono salvati in D1.
- [ ] Arretrato >40 eventi: il client esegue piu' richieste fino a svuotare la coda locale senza superare 40 eventi per POST.
- [ ] Doppio invio dello stesso evento non crea duplicati D1 (`INSERT OR IGNORE`).
- [ ] Nessun token OAuth, URL audio firmato o cookie presente nel payload Cloudflare.
- [ ] `Elimina dati cloud` cancella eventi, profilo e identita' server-side per l'installazione.
- [ ] Riavvio offline: personalizzazione locale continua a funzionare normalmente.
- [ ] Ricerca vocale Android Auto, Playlist Automatica, Cronologia, Preferiti live e Scopri senza regressioni.

### 36.12 File principali modificati nella 0.3.15

- `app/build.gradle.kts` - versione 0.3.15 / code 22 e `BuildConfig.PROFILE_SYNC_URL`.
- `LyraApplication.kt` - inizializzazione impostazioni, event store e sync Cloudflare.
- `personalization/PersonalizationSettings.kt` - preferenze del motore adattivo.
- `personalization/ListeningEventStore.kt` - diario locale degli eventi e coda sync.
- `personalization/RecommendationEngine.kt` - ranking candidati, novelty, recency e fatigue.
- `personalization/PersonalizedMixEngine.kt` - rotazione seed e mix `Per te`.
- `playback/ListeningProfileStore.kt` - profilo multidimensionale, confidenza, decadimento e feedback esplicito.
- `playback/AutomaticPlaylistManager.kt` - ranking di seed, continuation e restore radio.
- `playback/PlaybackService.kt` - raccolta segnali indipendente dalla UI, continuazione intelligente e content style Android Auto.
- `playback/PlaybackViewModel.kt` - rimozione della vecchia raccolta duplicata lato UI.
- `ui/common/PersonalizationFeedbackMenu.kt` - feedback esplicito nel player.
- `ui/player/NowPlayingScreen.kt` - accesso ai feedback accanto al cuore.
- `ui/settings/SettingsScreen.kt` - sezione Personalizzazione e Profilo Cloudflare.
- `cloud/CloudProfileSyncSettings.kt` / `CloudProfileSync.kt` - client sync pseudonimo.
- `cloudflare/lyra-profile-sync/` - Worker TypeScript, schema D1, configurazioni Wrangler JSONC/TOML di esempio e guida PowerShell al deploy.
- `res/drawable-nodpi/auto_art_*.png` - nuovo set artwork cartelle Android Auto.


---

## 37. Lyra 0.3.16 - Session Intent, Pop globale, ricerca intelligente e playlist unificate

**Versione:** `0.3.16`
**versionCode:** `23`

La 0.3.16 consolida il motore di personalizzazione introdotto nella 0.3.15 e corregge il limite principale osservato durante l'ascolto automatico: il profilo storico non basta a rappresentare cio' che l'utente vuole **in questo momento**.

### 37.1 Session Intent: memoria breve separata dal profilo storico

`AutomaticPlaylistManager` mantiene ora una memoria effimera della sessione corrente. Gli skip precoci non modificano soltanto il profilo a lungo termine ma influenzano immediatamente la coda attiva.

Regole principali:

- primo skip precoce: penalizzazione temporanea dell'artista e riordino della coda futura;
- due skip precoci consecutivi: cambio di intenzione forte;
- con preset esplicito, il secondo skip forte abbandona il pool del preset corrente e apre un contesto alternativo;
- con radio da brano, viene scelto un seed di artista differente e viene aperto un nuovo `Watch Next`;
- la penalizzazione di sessione non viene trasformata in un rifiuto permanente del genere;
- un completamento o un ascolto consistente azzera la sequenza di skip precoci.

Il ranking riceve ora anche `avoidArtists` e `diversificationLevel`. All'aumentare dei segnali negativi il peso della posizione originale Automix diminuisce e la varieta' aumenta.

### 37.2 Tempo ascoltato reale

`PlaybackService` non usa piu' `currentPosition` come sinonimo di tempo ascoltato. Viene accumulato il tempo effettivamente trascorso in riproduzione tramite `SystemClock.elapsedRealtime()`.

Questo evita che un seek in avanti venga interpretato come ascolto reale e rende piu' affidabili:

- percentuale ascoltata;
- rilevazione skip precoce;
- completamenti;
- segnali usati dal profilo personale.

### 37.3 Cambio immediato dopo due skip

Quando due skip precoci consecutivi generano un cambio di intenzione, Lyra prepara un nuovo pool e, se l'utente e' ancora sul brano appena iniziato del vecchio contesto, sostituisce anche quel brano senza registrare la sostituzione algoritmica come skip dell'utente.

In caso di errore nella nuova sorgente il sistema mantiene la coda corrente ma applica comunque il riordino con le penalizzazioni di sessione.

### 37.4 Pop realmente italiano + internazionale

Il preset `Pop` diventa **Pop italiano e internazionale**.

Non viene piu' scelto un singolo seed da query regionali miste. La sessione Pop apre due pool indipendenti:

- seed/Automix italiano;
- seed/Automix internazionale/worldwide.

I due insiemi vengono personalizzati separatamente e poi alternati. Anche `loadMore()` continua entrambi i rami, evitando che dopo il primo batch la playlist torni a essere soltanto italiana o soltanto internazionale.

Anche il catalogo `Scopri -> Generi -> Pop` usa query globali/internazionali al posto del precedente vincolo europeo.

### 37.5 Ricerca intelligente con suggerimenti live

`SearchViewModel` usa ora `YouTube.searchSuggestions()` gia' disponibile in InnerTubeX.

Dopo almeno due caratteri e con debounce di circa 280 ms vengono mostrati:

- titoli brano suggeriti;
- artisti suggeriti;
- query testuali suggerite.

Una suggestion brano puo' avviare direttamente la radio; artista e query eseguono la ricerca completa. Le richieste precedenti vengono cancellate quando l'utente continua a digitare.

### 37.6 Playlist salvate spostate in Home

`Playlist salvate` non e' piu' una cartella di `Scopri`.

Le playlist automatiche salvate vengono unite a `Home -> Le mie playlist`, prima delle playlist YouTube, e rimangono visibili anche senza account Google collegato. Il messaggio di conferma diventa `Playlist salvata in Le mie playlist`.

Su Android Auto le playlist Lyra salvate vengono esposte sotto `Le mie playlist`; il vecchio nodo salvato non viene piu' pubblicato dentro `Scopri`, ma il relativo ID resta gestito per compatibilita' con eventuali cache dei controller precedenti.

### 37.7 Protezione eventi cloud offline

`ListeningEventStore` non applica piu' il limite locale eliminando indiscriminatamente gli eventi piu' vecchi. Se il dispositivo resta offline a lungo, gli eventi non ancora sincronizzati vengono conservati; il trimming riguarda soltanto eventi gia' confermati dal cloud.

Questa modifica prepara il client alla successiva migrazione del backend Cloudflare verso backup completo/versionato.

### 37.8 Cloudflare: compatibilita' e fase successiva

La 0.3.16 Android resta compatibile con il Worker `/v1` attuale per evitare che il rilascio APK dipenda dalla migrazione server.

Il passaggio a **backup Cloudflare completo**, con restore cross-installazione/cambio telefono, snapshot versionati e persistenza dell'intero stato Lyra, viene eseguito come fase server immediatamente successiva al build della 0.3.16. La migrazione D1 dovra' preservare le tabelle 0.3.15 e introdurre lo schema v2 senza perdita dati.

### 37.9 Checklist build 0.3.16

- [ ] Applicare `Lyra-0.3.15_to_0.3.16.patch` alla sorgente 0.3.15 corrente.
- [ ] Verificare `versionName = 0.3.16` e `versionCode = 23`.
- [ ] `assembleDebug` SUCCESSFUL.
- [ ] `assembleRelease` SUCCESSFUL con il keystore ufficiale Lyra.
- [ ] Test: primo skip precoce riordina la coda futura.
- [ ] Test: due skip precoci consecutivi cambiano pool/contesto senza penalizzare permanentemente il genere.
- [ ] Test: seek in avanti non aumenta artificialmente il tempo ascoltato.
- [ ] Test: Pop alterna contenuti italiani e internazionali e continua a farlo dopo il prefetch.
- [ ] Test: Cerca mostra suggerimenti artista/brano/query mentre si digita.
- [ ] Test: una playlist automatica salvata appare in `Home -> Le mie playlist` e non in `Scopri`.
- [ ] Test Android Auto: playlist Lyra salvate presenti in `Le mie playlist`, assenti dalla root `Scopri`.
- [ ] Test offline: eventi cloud non sincronizzati non vengono scartati superando il limite di retention degli eventi gia' sincronizzati.
- [ ] Generare APK release firmato e SHA-256.
- [ ] Aggiornare `dist/update.json` soltanto dopo il build con nome APK, dimensione e hash reali.
- [ ] Pubblicare APK + `update.json` nella GitHub Release `Lyra-Releases`.
- [ ] Verificare updater da una versione precedente.
- [ ] Procedere con migrazione Cloudflare backup v2 dopo la validazione APK.

### 37.10 File principali modificati nella 0.3.16

- `app/build.gradle.kts` - versione 0.3.16 / code 23.
- `personalization/RecommendationEngine.kt` - penalita' di sessione e livello di diversificazione.
- `personalization/ListeningEventStore.kt` - conservazione garantita degli eventi cloud non sincronizzati.
- `playback/AutomaticPlaylistManager.kt` - Session Intent, pivot dopo skip e doppio pool Pop.
- `playback/PlaybackService.kt` - tempo ascoltato reale, feedback di sessione e sostituzione coda.
- `playback/PlaybackViewModel.kt` - nuova destinazione delle playlist salvate.
- `youtube/repository/AutomaticPlaylistRepository.kt` - pool Pop Italia/internazionale e seed alternativi.
- `youtube/repository/DiscoveryCatalog.kt` - query Pop globali.
- `ui/search/SearchViewModel.kt` / `SearchScreen.kt` - autocomplete intelligente.
- `ui/discover/DiscoverViewModel.kt` - rimozione `Playlist salvate` da Scopri.
- `ui/home/HomeViewModel.kt` / `HomeScreen.kt` - unione playlist Lyra salvate in `Le mie playlist`.


---


## 38. Lyra 0.3.17 - Cloud Music Profile Backup

### 38.1 Obiettivo

La 0.3.17 introduce un backup Cloudflare limitato esclusivamente ai dati che servono a Lyra per continuare ad ascoltare in modo personalizzato dopo cancellazione dei dati locali o reinstallazione.

Non vengono ripristinati account Google, libreria YouTube, playlist Google, impostazioni audio o stato UI: dopo il login questi dati vengono nuovamente ottenuti dalle rispettive sorgenti.

### 38.2 Identita' anonima Lyra

Ogni installazione genera un `listener_id` casuale e un segreto casuale. Il codice di recupero ha formato `LYRA2.<listener_id>.<secret>`.

Cloudflare non riceve e non memorizza nome, e-mail, UID Google, password, cookie, access token o refresh token. Nel D1 viene conservato soltanto l'hash SHA-256 del segreto di recupero.

Il `listener_id` non deriva dall'account Google e non consente di identificarlo.

### 38.3 Dati musicali nel cloud

Sono volutamente leggibili e interrogabili per analisi anonime:

- titolo e artista;
- avvii e completamenti;
- durata e percentuale realmente ascoltata;
- skip manuali e skip precoci;
- Like/Dislike;
- blocco brano, riduzione artista e boost simili;
- contesto/genere Lyra della sessione;
- snapshot del profilo algoritmo con punteggi e affinita'.

Il nuovo D1 `lyra-backup` e' in giurisdizione UE ed e' associato al Worker `lyra-profile-sync` tramite binding `DB`.

### 38.4 Schema e analisi

Tabelle principali:

- `listeners` - solo listener ID casuale, hash del segreto e metadati tecnici;
- `listening_events` - eventi musicali anonimi;
- `profiles` - ultimo snapshot del profilo musicale.

Viste analitiche:

- `v_track_stats` - brani piu' ascoltati/completati/skippati per listener;
- `v_artist_stats` - artisti piu' ascoltati per listener;
- `v_context_stats` - contesti/generi preferiti per listener.

### 38.5 API v2

- `POST /v2/sync` - inserisce eventi e aggiorna il profilo;
- `GET /v2/restore` - restituisce l'ultimo snapshot del profilo musicale;
- `DELETE /v2/profile` - elimina eventi, profilo e listener;
- `GET /v2/health` - stato del servizio.

Le API dati richiedono `X-Lyra-Listener` e `Authorization: Bearer <secret>`.

Gli endpoint legacy v1 restano temporaneamente disponibili in modalita' no-store per non rompere la 0.3.16 gia' rilasciata.

### 38.6 Client Android

La 0.3.17 usa WorkManager con rete disponibile per sincronizzare gli eventi anche dopo la terminazione del processo. Ogni sincronizzazione invia al massimo 40 eventi per batch e aggiorna sempre lo snapshot del profilo.

Gli eventi locali vengono marcati sincronizzati solo dopo risposta HTTP positiva.

Il restore da codice `LYRA2...` ricostruisce `ListeningProfileStore`; non ripristina dati Google o altri store dell'app.

### 38.7 Privacy operativa

Workers Observability e invocation logs sono disabilitati per `lyra-profile-sync`.

Il database precedente `lyra-profile` non e' piu' il binding attivo del Worker. Il servizio 0.3.17 usa il nuovo D1 `lyra-backup`, creato appositamente per il solo profilo musicale anonimo.

### 38.8 Checklist 0.3.17

- [ ] Applicare la patch 0.3.16 -> 0.3.17.
- [ ] Verificare `versionName = 0.3.17` e `versionCode = 24`.
- [ ] `compileDebugKotlin` SUCCESSFUL.
- [ ] `assembleDebug` SUCCESSFUL.
- [ ] Avviare la 0.3.17 e usare `Backup ora`.
- [ ] Verificare nel D1 una riga `listeners`, eventi in `listening_events` e snapshot in `profiles`.
- [ ] Copiare il codice `LYRA2...`.
- [ ] Cancellare i dati locali su un dispositivo di test.
- [ ] Reinstallare/aprire Lyra e usare `Ripristina da codice`.
- [ ] Verificare che il profilo musicale ricostruito influenzi nuovamente raccomandazioni e ranking.
- [ ] Verificare che nessuna tabella/colonna contenga dati account Google.
- [ ] Pubblicare la 0.3.17 solo dopo il test restore end-to-end.


---

## 39. Lyra 0.3.20 - Android Auto Resume + Recommendation Engine V2 + Cloud Music Intelligence

**Versione:** `0.3.20`
**versionCode:** `25`

La 0.3.20 accorpa in un'unica release la correzione rimasta sul resume Android Auto e i blocchi di roadmap previsti per Recommendation Engine V2 e Cloud Music Intelligence. Le versioni nominali 0.3.18/0.3.19 non vengono pubblicate separatamente: il `versionCode` resta monotono e passa da 24 a 25.

### 39.1 Android Auto: ultimo brano subito disponibile in pausa

La sessione persistita non viene piu' soltanto caricata nella timeline del player. Durante il cold start del `PlaybackService` il player viene anche **preparato** mantenendo `playWhenReady=false`.

Obiettivo:

- smartphone: mini-player sull'ultimo brano come prima;
- Android Auto: Lyra espone subito titolo, artista, artwork, coda e posizione salvata;
- stato iniziale: pausa, nessun autoplay;
- PLAY da Android Auto riparte dalla posizione persistita;
- Shuffle e Repeat vengono ripristinati insieme alla queue.

`onPlaybackResumption()` distingue inoltre le due modalita' Media3:

- `isForPlayback=false`: restituisce rapidamente il solo brano corrente con metadata e posizione, per la card di ripresa;
- `isForPlayback=true`: restituisce l'intera queue e lascia a Media3 `setMediaItems/prepare/play`.

### 39.2 Recommendation Engine V2: storico, recente e sessione

Il ranking separa tre livelli di preferenza:

1. **storico persistente** - `ListeningProfileStore`, con confidenza e decadimento gia' presenti;
2. **recente** - eventi degli ultimi 7/30/90 giorni con peso decrescente;
3. **sessione corrente** - artisti graditi o rifiutati durante l'ascolto in corso.

Per ogni candidato vengono combinati segnali track/artist/context storici e recenti. Un completamento o ascolto >=70% rende l'artista temporaneamente positivo nella sessione; uno skip precoce lo rende temporaneamente negativo.

### 39.3 Anti-ripetizione e saturazione

Il ranking aumenta la protezione contro la monotonia:

- penalita' forte per brani gia' presenti nella finestra recente;
- penalita' aggiuntiva se l'artista coincide con quello appena ascoltato;
- penalita' crescente quando lo stesso artista ricorre piu' volte negli ultimi brani;
- penalita' moderata per album ripetuti;
- resta attiva la penalita' temporale per brani ascoltati nelle ultime ore/giorni.

Queste penalita' sono applicate solo quando `Evita ripetizioni` e' attivo.

### 39.4 Esplorazione controllata

Il parametro di scoperta non si limita piu' a un jitter del punteggio. Dopo il ranking Lyra inserisce una quota minima di brani mai ascoltati, scegliendoli pero' soltanto dalla fascia alta dei candidati gia' classificati.

La frequenza cresce con il livello di esplorazione:

- profilo conservativo: quasi tutto familiare;
- livello medio: una novita' circa ogni 4-5 posizioni;
- livello esplorativo: una novita' circa ogni 3 posizioni.

### 39.5 Session Intent rafforzato

Restano valide le regole 0.3.16 sui due skip precoci consecutivi. In piu':

- gli artisti completati vengono memorizzati come positivi nella sessione;
- uno skip precoce rimuove l'eventuale boost positivo dello stesso artista;
- un ascolto >=70% rimuove la penalizzazione temporanea dell'artista;
- tutti i rerank, continuation e pivot ricevono lo stesso stato sessione.

### 39.6 Cloud Music Intelligence

Il D1 `lyra-backup` continua a non contenere nome, e-mail, UID Google, password, cookie o token OAuth. I dati musicali restano invece leggibili e interrogabili per `listener_id` anonimo.

Sono aggiunte tre viste con decadimento temporale 7/30/90 giorni:

- `v_track_affinity`;
- `v_artist_affinity`;
- `v_context_affinity`.

I punteggi combinano avvii, completamenti, percentuale ascoltata, Like/Dislike, skip precoci, riduzione artista e boost simili.

Il Worker espone anche:

- `GET /v2/insights` - migliori brani, artisti e contesti per il listener autenticato;
- `GET /v2/restore` - oltre allo snapshot profilo restituisce gli ultimi 600 eventi musicali.

### 39.7 Restore cloud completo per l'algoritmo

`CloudProfileSync` ripristina ora sia `ListeningProfileStore` sia gli eventi recenti in `ListeningEventStore`.

Gli eventi ricevuti dal cloud vengono marcati `synced=true`, quindi non vengono reinviati. Eventuali eventi locali non ancora sincronizzati hanno precedenza in caso di ID duplicato.

Questo consente, dopo cancellazione dati/reinstallazione, di ricostruire immediatamente:

- preferenze storiche;
- preferenze recenti;
- skip/completamenti recenti;
- affinita' per brano/artista/contesto.

L'account Google resta fuori dal backup: dopo il restore l'utente effettua normalmente il login Google per recuperare libreria e servizi dell'account.

### 39.8 Checklist 0.3.20

- [ ] Applicare la patch alla sorgente 0.3.17.
- [ ] Verificare `versionName = 0.3.20` e `versionCode = 25`.
- [ ] `compileDebugKotlin` SUCCESSFUL.
- [ ] `assembleDebug` SUCCESSFUL.
- [ ] Android Auto: ascoltare un brano, mettere in pausa, chiudere Lyra e scollegare Auto.
- [ ] Ricollegare Android Auto senza aprire la UI telefono: la card deve mostrare l'ultimo brano in pausa, non soltanto `Apri`.
- [ ] PLAY da Android Auto riparte dalla posizione salvata.
- [ ] Due skip precoci consecutivi cambiano intenzione come nella 0.3.16.
- [ ] Artista appena completato riceve un boost di sessione contenuto.
- [ ] Stesso artista/album non domina la coda quando `Evita ripetizioni` e' attivo.
- [ ] Livello Esplora inserisce novita' senza prelevare candidati dal fondo del ranking.
- [ ] Backup Cloudflare: `POST /v2/sync` salva profilo ed eventi.
- [ ] Restore da codice `LYRA2`: profilo + eventi recenti vengono ricostruiti.
- [ ] `v_track_affinity`, `v_artist_affinity`, `v_context_affinity` restituiscono punteggi per listener.
- [ ] Nessuna tabella o risposta API contiene dati account Google.

### 39.9 File Android principali modificati

- `app/build.gradle.kts` - versione 0.3.20 / code 25.
- `playback/PlaybackService.kt` - hydration preparata in pausa e playback resumption ottimizzato.
- `personalization/RecommendationEngine.kt` - storico + recente + sessione, anti-repeat ed exploration quota.
- `personalization/ListeningEventStore.kt` - segnali recenti e restore eventi cloud.
- `playback/AutomaticPlaylistManager.kt` - artisti positivi/negativi di sessione.
- `cloud/CloudProfileSync.kt` - restore degli eventi recenti oltre allo snapshot profilo.


---

## 40. Lyra 0.3.21 - Local Audio Intelligence + Playback Context + Android Auto polish

**Versione:** `0.3.21`
**versionCode:** `26`

### 40.1 Normalizzazione locale avanzata

La normalizzazione introdotta per lo streaming viene estesa ai file nella libreria locale. Lyra non modifica i file originali: analizza il PCM decodificato e conserva soltanto una cache locale dei risultati.

- preferenza per ReplayGain quando il decoder espone tag affidabili;
- fallback con analisi loudness orientata EBU R128: K-weighting, blocchi 400 ms, passo 100 ms, gate assoluto -70 LUFS e gate relativo -10 LU;
- stima conservativa del true peak con 1 dB di headroom addizionale;
- Track Gain, Album Gain e modalita' Automatica;
- target predefinito -14 LUFS;
- limiti separati di amplificazione e attenuazione;
- cache persistente fino a 4000 file e scansione incrementale tramite WorkManager;
- opzione di scansione soltanto durante la ricarica.

### 40.2 Impostazioni Audio complete

`Impostazioni -> Audio` espone controlli separati per:

- master Normalizzazione audio avanzata;
- streaming YouTube;
- musica locale;
- preferenza ReplayGain;
- analisi EBU R128;
- protezione True Peak;
- modalita' Traccia / Album / Automatica;
- target -16 / -14 / -12 LUFS;
- limite gain +3 / +6 / +9 dB;
- limite attenuazione -6 / -8 / -12 dB;
- scansione in background;
- scansione solo in carica;
- avvio manuale analisi;
- cancellazione cache.

Se il master e' disattivato Lyra non applica correzioni e non avvia scansioni automatiche.

### 40.3 Contesto di riproduzione visibile

Il titolo della coda gia' persistito viene propagato nei metadata Media3 e mostrato in modo evidente nel mini-player e nel player completo. Esempio: `Playlist Automatica · Energia`. Lo stesso valore viene ripristinato insieme alla sessione e inviato ad Android Auto come subtitle/description del media corrente.

### 40.4 Android Auto media card

Il media item corrente usa il nuovo artwork `auto_art_lyra.png` come fallback quando la copertina non e' disponibile. L'icona applicazione dichiarata nel manifest resta `@mipmap/ic_launcher`; il posizionamento finale dell'app badge nella Home e' deciso dall'host Android Auto.

### 40.5 Checklist 0.3.21

- [ ] `compileDebugKotlin` SUCCESSFUL.
- [ ] `assembleDebug` SUCCESSFUL.
- [ ] File locale: scansione R128 completa e cache persistente.
- [ ] Normalizzazione OFF: playback invariato.
- [ ] Local OFF / Streaming ON e viceversa: comportamento indipendente.
- [ ] True Peak ON: il gain positivo viene limitato quando necessario.
- [ ] Modalita' Album: una coda dello stesso album mantiene la dinamica relativa.
- [ ] Modalita' Automatica: Album Gain solo per code omogenee dello stesso album.
- [ ] Mini-player e Now Playing mostrano `Playlist Automatica · <preset>`.
- [ ] Android Auto mostra lo stesso contesto nei metadata quando supportato dall'host.
- [ ] Cold resume Android Auto mantiene brano, posizione, contesto e stato pausa.
- [ ] In assenza di artwork viene fornito il fallback Lyra.

### 40.6 File principali

- `audio/AudioSettings.kt` - configurazione completa e persistente.
- `audio/AudioNormalizationController.kt` - gain streaming/locale e true-peak guard.
- `audio/LocalLoudnessAnalyzer.kt` - analisi PCM R128-oriented.
- `audio/LocalLoudnessStore.kt` - cache e Album Gain.
- `audio/LocalAudioScanWorker.kt` - scansione incrementale WorkManager.
- `playback/PlaybackMediaItemFactory.kt` - contesto nei metadata.
- `playback/PlaybackService.kt` - applicazione normalizzazione locale e contesto Android Auto.
- `playback/PlaybackController.kt`, `PlaybackUiState.kt`, `PlaybackViewModel.kt` - propagazione contesto smartphone.
- `ui/player/MiniPlayer.kt`, `NowPlayingScreen.kt` - contesto visibile.
- `ui/settings/SettingsScreen.kt` - menu Audio avanzato.
- `res/drawable-nodpi/auto_art_lyra.png` - fallback Android Auto.


---

## 41. Lyra 0.3.22 - Android Auto context, recommendation tuning e release hygiene

Versione: **0.3.22** (`versionCode 27`).

### Android Auto

- il contesto della queue viene esposto anche come albumTitle;
- viene aggiunto displayTitle ai metadata del brano;
- subtitle e description continuano a trasportare il contesto, ad esempio `Playlist Automatica Â· Energia`;
- il posizionamento grafico finale resta deciso dall host Android Auto.

### Recommendation

- dopo uno skip precoce l artista entra in cooldown per la sessione;
- quando esistono alternative viene spostato dietro nella coda;
- vengono evitati due brani consecutivi dello stesso artista quando possibile;
- la memoria degli artisti skippati viene limitata agli ultimi sei.

### Cleanup

- rimozione del non-null assertion ridondante in LocalLoudnessStore;
- rimozione sicura delle safe-call ridondanti su RestoredPlaybackSession;
- nessuna eliminazione aggressiva di callback Media3 o codice di compatibilita.

### Release

- build-release.ps1 verifica automaticamente il certificato dell APK;
- il fingerprint SHA-256 del signer viene aggiunto a update.json;
- una firma differente blocca la creazione della Release.

### Rinviato alla 0.3.23

- bilanciamento Pop Italia/internazionale V2;
- gapless;
- crossfade intelligente;
- prefetch e buffering adattivo avanzato.

### 41.1 Patch di correzione 0.3.22 sulla sorgente allegata (12 ottobre 2026)

Questa patch e' costruita sui sorgenti gia' marcati `0.3.22` / `versionCode 27`.
Non cambia i numeri di versione, gli endpoint Cloudflare, i protocolli di backup,
la normalizzazione audio o la firma esistente.

- **Android Auto**: i media item di navigazione espongono in modo coerente
  `displayTitle`, `albumTitle`, `subtitle`, `description` e artwork fallback Lyra.
  La costruzione dei media item di resume e playlist Auto usa il titolo della
  coda corretto senza rileggere la sessione per ogni brano. L'host Android Auto
  conserva il controllo sulla disposizione effettiva della card.
- **Skip/learning**: normalizzazione dell'artista principale anche su feat. e
  collaborazioni, cooldown applicabile anche con poche alternative, ricerca di
  alternative sull'intera coda e non solo tra i primi otto elementi. Se il
  brano gia' passato in riproduzione dopo NEXT e' ancora dello stesso artista
  rifiutato, il servizio puo' caricare subito un'alternativa presente nella
  coda. Il pivot non forza in testa un seed dell'artista in cooldown. La UI
  sincronizza inoltre i riordini senza aspettare che cresca la lunghezza.
- **Scopri**: filtro sulle categorie esplicitamente incompatibili (generi,
  mood, attivita', decenni); query di Novita' riferite all'anno corrente;
  esclusione prudenziale delle playlist Novita' non etichettate con l'anno
  corrente oppure contenenti anni diversi nel titolo/descrizione.
  **Limite dichiarato**: il titolo/descrizione di PlaylistItem non garantisce
  l'anno di pubblicazione di ogni traccia. Per la certificazione di tutti i
  brani servira' una sorgente affidabile per le date di release, fuori da
  questo hotfix. Alcune playlist senza data vengono volutamente escluse.
- **Release**: `tools/build-release.ps1` conserva le proprie verifiche della
  firma e include anche `signerSha256` nel `dist/update.json` generato.

Test statici/Kotlin locali accompagnano la patch; sono necessari build Gradle,
prova smartphone e prova sul proprio head unit Android Auto prima di pubblicare.

## 0.3.25 — Music Intelligence Analytics (2026-10-19)
- Nuovo percorso Impostazioni → Le mie statistiche con cinque grafici, filtri temporali e dettagli tabellari.
- Lettura del backup Cloudflare `/v2/restore` separata dal ripristino, mantenendo invariati i dati sul telefono; fallback locale etichettato.
- Controlli Android Auto NEXT/PREV irrobustiti nelle transizioni asincrone e diagnostica degli indici Media3/store persistito.
- versionName 0.3.25, versionCode 33; ulteriori verifiche runtime necessarie prima della pubblicazione.
- La vecchia cartella sorgente Cloudflare nello ZIP contiene Worker `/v1` e NON sostituisce il Worker `/v2` già usato dall'app.

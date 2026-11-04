# Lyra Android 1.0.9 — preparazione release

**Versione sorgenti:** `1.0.9` · **build:** `47` · **pacchetto:** `app.lyra.music`.

Questa versione include le correzioni Techno/House (Next, avvisi, refill), i 17
preset, la scelta sullo smartphone dell'algoritmo server/locale e la telemetria
verso il Monitor riproduzioni del server **0.3.25**. Le sorgenti sono nel ramo
`release/1.0.9-android-auto-mode-and-monitor`; la firma e la pubblicazione
richiedono una compilazione release su Windows con la *stessa chiave* della
release Android installata. La CI GitHub genera solo APK debug **non firmate
per l'aggiornamento della release**.

## Allineamento, compilazione, firma e pubblicazione da Windows

Usa una shell PowerShell aperta nella cartella del progetto già clonata.
Non cancellare o sovrascrivere `keystore.properties`, `local.properties`
e `release-signing.sha256`, che restano **esclusivamente sul PC**.

```powershell
# Aggiorna i sorgenti locali a origin/main (solo fast-forward, mai reset).
.\tools\prepara-release-1.0.9.ps1 -Allinea

# Test, debug e release locale firmata. Controlla la fingerprint.
.\tools\prepara-release-1.0.9.ps1 -Firma

# Solo DOPO il test installato sul telefono: crea v1.0.9 con APK e update.json.
.\tools\prepara-release-1.0.9.ps1 -Pubblica -ConfermaTest
```

L'ultimo comando richiede **GitHub CLI** `gh` già autenticato
(`gh auth login`) e un `main` locale identico a `origin/main`.
La pubblicazione **non ricompila**: carica esattamente la APK firmata e
collaudata con `-Firma`, confrontando SHA-256, certificato e commit del
repository (salvati localmente in `dist/release-source-1.0.9.json`). Si ferma
se il tag `v1.0.9` esiste già, se la firma non corrisponde a quella precedente
o se `update.json` non corrisponde all'APK.
Prima della pubblicazione, il ramo dev'essere integrato in `main` e la CI
verde. Il backend 0.3.25 deve essere installato sul server per vedere gli
stati dei lettori. I client precedenti non inviano heartbeat.

Dettagli modifiche: [note release 1.0.9](docs/release-notes-1.0.9.md).

---

## Archivio istruzioni Android 1.0.8 (storico, non usare per la nuova release)



**Versione dei sorgenti:** `1.0.8` · **build:** `46` · **pacchetto:** `app.lyra.music` · **aggiornato:** 3 novembre 2026

Questa è la **sola guida Markdown operativa** del repository Android. Le note delle versioni precedenti sono conservate nella storia Git, ma non sono più istruzioni di rilascio valide. Non confondere i **sorgenti su GitHub**, l'**APK firmata pubblicata** e la versione effettivamente installata sul telefono o sul server.

## Step 1 — Versioni e stato dei componenti

| Componente | Versione/stato |
| --- | --- |
| Sorgenti Android attuali | **1.0.8 build 46**, `app/build.gradle.kts` |
| Base da cui deriva | **1.0.7 build 45**, commit `46310e3758cc9f8d96a95150747ccf64ce082bf2` |
| Sorgenti + release Android | [chrelyon/Lyra-Releases](https://github.com/chrelyon/Lyra-Releases) |
| Ramo ufficiale dei sorgenti | `main`; ramo RC `release/1.0.8-verified-playlists-rc` |
| Backend Lyra | [chrelyon/Lyra](https://github.com/chrelyon/Lyra), codice server **0.3.21** |
| API per generi rigorosi | `POST /v1/playlists/verified-genre`, server 0.3.21 o compatibile |
| Release Android | [v1.0.8](https://github.com/chrelyon/Lyra-Releases/releases/tag/v1.0.8), pubblicata il **03/11/2026** con `Lyra-1.0.8.apk` e `update.json` |
| Test automatici | [GitHub Actions 41634746647](https://github.com/chrelyon/Lyra-Releases/actions/runs/41634746647): **success**. Build debug Windows dichiarata riuscita |
| Da collaudare | Fingerprint dell'APK pubblicata, corrispondenza dei suoi byte con l'APK locale, test completi smartphone/Android Auto e versione realmente installata su Ubuntu |

**Incongruenza del tag esistente:** il 03/11/2026 `v1.0.8` su GitHub puntava al commit **`331e7b60f8e1fa11fecc92ec92ed1476d8bec3d9`**, che conteneva ancora Android **1.0.5 build 42**. L'APK 1.0.8 esiste come asset, ma quel tag **non prova la provenienza dei sorgenti**. La correzione di un tag già pubblicato richiede verifica separata e non viene fatta di nascosto. Considerare `main` aggiornato e il ramo RC come riferimenti sorgenti.

## Step 2 — Architettura e funzionalità

Lyra è un'applicazione **Android/Kotlin con Jetpack Compose**. Usa **Media3/ExoPlayer** per la riproduzione e **InnerTubeX/YouTube Music** per ricerca e risoluzione degli stream. Il server Python/FastAPI su Ubuntu contiene catalogo, profilo, metadati e classificazioni: **non distribuisce l'audio**.

| Cartella | Compito |
| --- | --- |
| `app/src/main/java/app/lyra/music/playback/` | Player, servizi, stato coda, playlist automatiche, Android Auto |
| `app/src/main/java/app/lyra/music/ui/` | Schermate Compose, playlist, ricerca e impostazioni |
| `app/src/main/java/app/lyra/music/youtube/` | Catalogo, ricerca e Discovery |
| `app/src/main/java/app/lyra/music/personalization/` | Raccomandazioni e sessioni server playlist `v3` |
| `app/src/main/java/app/lyra/music/genre/` | Token classificatore e playlist a genere verificato |
| `app/src/main/java/app/lyra/music/cloud/` | Backup e sincronizzazione profilo Ubuntu |
| `app/src/main/java/app/lyra/music/update/` | Controllo versioni su GitHub Releases |
| `innertube/` | Modulo per YouTube Music |
| `app/src/test/` e `innertube/src/test/` | Test |
| `tools/` | Build, firma, verifica, logcat, test e archivio sorgenti |
| `.github/workflows/` | CI Android senza credenziali |
| `MANIFEST.json` | Manifesto **dei sorgenti**, non manifesto APK |
| `dist/` | Output locale di build, escluso da Git |

Funzioni preesistenti che vanno preservate: playlist salvate e automatiche, Cerca avanzato artisti e album, libreria locale, preferiti, coda persistente, shuffle/repeat, ascolto in background, Android Auto, personalizzazione, sincronizzazione Ubuntu e aggiornamenti in-app.

## Step 3 — Requisiti di compilazione

Su Windows: **JDK 21**, Android SDK **Platform 37.0**, **Build Tools 37.0.0**, Git e PowerShell. Il progetto usa **Gradle Wrapper 9.3.1**, Android Gradle Plugin **9.1.1**, Kotlin/Compose plugin **2.4.10**. Servono accesso a Maven/Google/JitPack e `local.properties` oppure `ANDROID_HOME`/`ANDROID_SDK_ROOT`.

```powershell
java -version
git --version
Get-Content .\app\build.gradle.kts | Select-String "versionName|versionCode|applicationId"
```

Dovresti leggere `versionName = "1.0.8"`, `versionCode = 46` e `applicationId = "app.lyra.music"`.

**Warning noti non bloccanti:** differenze nelle versioni XML degli strumenti Android SDK; `Unable to strip ... libandroidx.graphics.path.so, libquickjs.so`; warning `@OptIn ... UnstableApi` in `LyraMediaButtonReceiver.kt`. La presenza del report `build/reports/problems/problems-report.html` non equivale a un errore: verificare comunque `BUILD SUCCESSFUL`.

## Step 4 — Allineamento Git su Windows

La cartella può ancora chiamarsi `Lyra-Android-1.0.7`: **il numero nella cartella non determina la versione dell'app**.

```powershell
cd "C:\Users\culiana\AndroidTools\work\Lyra-Android-1.0.7"
git status --short
git remote -v
git branch --show-current
```

**Se `git status` elenca modifiche, conservarle prima del cambio ramo.** Non utilizzare `reset --hard` o `clean -fdx`. Questo clone aveva un fetch limitato a `feature/server-playlists`: per lavorare con il nuovo `main` aggiungere il riferimento senza rimuovere quelli esistenti.

```powershell
git config --add remote.origin.fetch "+refs/heads/main:refs/remotes/origin/main"
git fetch origin
git status --short
git switch main
```

Se `main` non esiste localmente, usare al posto dell'ultimo comando:

```powershell
git switch -c main --track origin/main
```

Se invece rimani sul ramo RC già scaricato:

```powershell
git switch release/1.0.8-verified-playlists-rc
git pull --ff-only
```

Per confermare dopo il cambio: `git branch --show-current` e `Get-Content .\app\build.gradle.kts | Select-String "versionCode|versionName"`. Un errore di `pull --ff-only` richiede una verifica della storia, non una forzatura.

## Step 5 — Test e compilazione Debug

Dalla radice:

```powershell
.\gradlew.bat :app:testDebugUnitTest :innertube:testDebugUnitTest :app:assembleDebug
```

Oppure utilizzare lo script integrato della 1.0.8 (accetta `main` e il ramo RC):

```powershell
.\tools\prepara-release-1.0.8.ps1
```

Risultato: `app\build\outputs\apk\debug\app-debug.apk`. Questa APK usa la **firma debug**, quindi **non deve essere installata sopra la 1.0.7/1.0.8 release firmata**. La CI [verificata](https://github.com/chrelyon/Lyra-Releases/actions/runs/41634746647) ha superato test e build debug della candidata. I test JVM non provano da soli il funzionamento su smartphone.

## Step 6 — Server Ubuntu e collegamento

**Repository separato:** [Lyra Server](https://github.com/chrelyon/Lyra). I sorgenti server indicano `server/VERSION = 0.3.21`, ma **non è stato verificato quale versione giri realmente sul tuo Ubuntu**. Prima di provare i nuovi generi: backup del database/configurazioni, aggiornamento verificato del server e controllo HTTPS dal telefono.

URL Funnel fisso nel codice Android `GenreClassifierSettings.SERVER_URL`: `https://vivisochat-lab.tail056f21.ts.net:10000`. Il Bearer token viene configurato localmente e protetto sul dispositivo. La sincronizzazione profilo usa **Ubuntu**, non il vecchio database Cloudflare.

Controllare, nell'ordine: (1) versione realmente installata e stato API; (2) connessione HTTPS e token; (3) endpoint `POST /v1/playlists/verified-genre`; (4) eventuale abilitazione delle sessioni server `v3` nella dashboard e del flag `LYRA_SERVER_PLAYLISTS_ENABLED`, funzione distinta dall'API verificata `v1`; (5) DB/catalogo con evidenza di genere sufficiente.

Il server usa per le playlist rigorose evidenze a livello di **singolo brano**, non il solo genere associato all'artista. Richiesta d'esempio (senza token):

```json
{"genre":"techno","limit":20,"exclude_ids":[],"include_external":false}
```

Le integrazioni esterne sono **facoltative e disabilitate per impostazione iniziale**: `LYRA_PLAYLIST_GENRE_DISCOVERY_ENABLED=false` e `LYRA_PLAYLIST_GEMINI_ENABLED=false`. Last.fm richiede una chiave lato server; Gemini una **credenziale separata lato server**, mai in APK o repository. Il modello può proporre titolo/artista, ma non certificare il genere. Per i dettagli usare [la documentazione tecnica del server](https://github.com/chrelyon/Lyra/blob/main/docs/PLAYLIST_QUALITY_0321.md).

## Step 7 — Playlist 1.0.8 e criteri di precisione

La 1.0.7 includeva già le **playlist server `v3`**: sessioni e cursori, feedback, recupero dopo disconnessioni, aggiornamento della sola coda futura al confine fra brani, rispetto di repeat/shuffle e modifiche manuali. **La 1.0.8 conserva queste funzioni.**

La 1.0.8 aggiunge `VerifiedGenrePlaylistClient` per i preset **Techno, House, Dance e Rock**: quando il token classificatore è configurato e la connessione è attiva, l'avvio e il refill interrogano `POST /v1/playlists/verified-genre`, verificando brano per brano; lo stesso percorso è impiegato per skip ripetuti e ripresa da cronologia.

- **Techno non è sinonimo di elettronica generica**: identità artista o etichette `electronic` non sono sufficienti.
- Se non esistono candidati verificati o il server non risponde, il percorso rigoroso **non riempie la coda con brani casuali di Automix**.
- **Senza token configurato**, il percorso rigoroso non è attivo e possono valere i comportamenti precedenti. Non attribuire a quella modalità la precisione del catalogo verificato.
- Mood, artisti, album, generi non inclusi tra i quattro e playlist manuali continuano a usare i flussi già esistenti.
- **Cerca avanzato:** in Scopri → Playlist Automatica, filtro artista/album con suggerimenti; il testo cercato come artista non è automaticamente classificazione di genere. Per un album scelto rimane la scaletta dell'album.

## Step 8 — Copertine, player e Android Auto

La 1.0.8 usa `Song.artworkUrlForPlayback`: mantiene la `thumbnailUrl` già presente oppure, per un **ID video YouTube valido di 11 caratteri** e non locale, utilizza `https://i.ytimg.com/vi/{videoId}/hqdefault.jpg`. Il fallback si propaga ai metadata Media3, playlist, ricerca, player e Android Auto. Il client API accetta URL di immagini soltanto HTTPS su host autorizzati.

**La thumbnail del video non è necessariamente la copertina ufficiale dell'album.** Provare sia brani con immagine originaria sia brani senza, nelle liste, sulla lock screen, nella notifica e in Android Auto.

## Step 9 — Firma Windows e produzione della APK

**Metodo principale già presente nel progetto:** `tools/build-release.ps1`, senza obbligo di usare un wrapper più lungo. Richiede `keystore.properties` locale e la **stessa chiave della 1.0.7**. Prima di procedere controllare che sia disponibile `release-signing.sha256` attendibile **oppure** una precedente APK release sicuramente originale in `dist/` (per esempio `Lyra-1.0.7.apk`).

Dalla cartella `tools`:

```powershell
.\build-release.ps1
```

Dalla radice:

```powershell
.\tools\build-release.ps1
```

Lo script esegue `assembleRelease`, firma con il keystore locale, lancia `verify-release-signature.ps1`, confronta il fingerprint della firma, calcola SHA-256 dell'APK e produce `dist\Lyra-1.0.8.apk` più `dist\update.json` in UTF-8 **senza BOM**. **Non pubblica niente su GitHub**.

Prima di richiamarlo, fare copia dell'eventuale `dist/update.json` da conservare, perché `build-release.ps1` lo sovrascrive. Per un flusso che esegue prima i test e crea automaticamente il backup del precedente JSON, usare `.\prepara-release-1.0.8.ps1 -Firma` da `tools`.

Verifiche dei file generati:

```powershell
Get-FileHash ..\dist\Lyra-1.0.8.apk -Algorithm SHA256
Get-Content ..\dist\update.json
```

**Non inizializzare il fingerprint da una APK sconosciuta:** la verifica è affidabile soltanto se il riferimento iniziale è quello realmente usato per firmare le release precedenti.

## Step 10 — Linux, sorgenti e altri strumenti

Build Debug Linux: `./gradlew :app:testDebugUnitTest :innertube:testDebugUnitTest :app:assembleDebug`. APK release **non firmata**: `bash tools/build-release.sh --unsigned` → `dist/unsigned/Lyra-1.0.8-unsigned.apk`. Per firmare su Linux esiste `tools/sign-release-local.sh` con input APK, keystore, alias e build-tools; controllare certificato e output prima di distribuire. Una APK unsigned **non aggiorna** l'app firmata installata.

Per creare l'archivio sorgenti sul PC:

```powershell
.\tools\package-source.ps1
```

Genera `dist\Lyra-1.0.8-sorgenti.zip`. Non deve includere APK, password, chiavi private, `release-signing.sha256` o configurazioni locali. L'archivio sorgenti non è un'app installabile.

In `tools/` sono disponibili anche `lyra-logcat.ps1`, `lyra-auto-logcat.ps1`, `build-install-debug.ps1`, `check-innertubex-update.ps1` e test di protocollo con componenti sintetici in `tools/protocol-test/`. Questi ultimi non sostituiscono una prova del player reale.

## Step 11 — Pubblicazione e controllo aggiornamenti

La [GitHub Release 1.0.8](https://github.com/chrelyon/Lyra-Releases/releases/tag/v1.0.8) esiste già. **Non crearne un'altra con lo stesso numero e non sostituire gli asset senza verifica.**

Verificare: APK scaricata = APK locale firmata (SHA-256), `applicationId=app.lyra.music`, `versionCode=46`, **stesso certificato della 1.0.7**, `update.json` corrispondente all'APK e firma verificata. La release pubblicata e il suo tag Git sono oggetti distinti; alla verifica iniziale il tag v1.0.8 puntava ai sorgenti vecchi (step 1), quindi la provenienza va riallineata con una procedura separata e consapevole.

L'updater Android legge le GitHub Releases, preferendo il `versionCode` indicato in `update.json`. Il progetto **non pubblica automaticamente** la release firmata dalla CI. Non installare debug/unsigned o release con firma diversa sopra l'app di produzione se si vogliono conservare i dati.

## Step 12 — Checklist di collaudo reale

- [ ] Aggiornamento **in-place** 1.0.7 → 1.0.8 build 46 con firma originale, dati e impostazioni preservati.
- [ ] Avvio, ricerca, playlist salvate, preferiti, libreria locale, cronologia e Cerca avanzato artisti/album.
- [ ] Server Ubuntu raggiungibile, backup/ripristino profilo, sessioni playlist `v3`, ripresa dopo rete assente.
- [ ] **Techno**: controllare *ogni brano*, artista e fonte del genere; niente elettronica genericamente assimilata.
- [ ] **House/Dance/Rock**: nuova playlist, refill, skip ripetuti, resume; nessuna coda non verificata in modalità rigorosa.
- [ ] Server/token non disponibili e catalogo vuoto: messaggio controllato, nessun riempimento Automix nella modalità rigorosa.
- [ ] Playlist classiche e artisti, coda manuale, repeat/shuffle, preferiti e continuità dello stream.
- [ ] Copertine nella ricerca, in playlist, player, notifiche, blocco schermo, Android Auto.
- [ ] Android Auto: navigazione, avvio, comandi, seek, cambio traccia, reconnessione e artwork.
- [ ] Aggiornamenti in-app: con 1.0.8 installata nessuna richiesta di aggiornamento alla stessa versione.

**Esito documentato:** CI Android verde e build debug Windows riuscita. **Non sono documentati qui test completi su dispositivo/Android Auto, confronto fingerprint dell'asset della release o deploy live Ubuntu**. Registrarli prima di dichiarare chiuso il collaudo.

## Step 13 — Sicurezza e manutenzione

Non versionare `keystore.properties`, `*.jks`, `*.keystore`, `release-signing.sha256`, `local.properties`, `.env`, token, password, DB, log e cartella `dist/`. La firma deve sempre usare la chiave originaria. Fare backup di keystore e DB server fuori dal repository.

**Distinzione tra manifesti:** `MANIFEST.json` descrive l'identità dei sorgenti 1.0.8; `dist/update.json` descrive il file APK realmente distribuito. Il vecchio `SHA256SUMS.txt` del pacchetto importato dalla 1.0.5 è obsoleto e non deve essere usato per i sorgenti 1.0.8.

Per diagnosi: verificare versione Android e server reali, ottenere log senza token, provare un brano noto, controllare la relativa classificazione nel DB, ripetere CI e test sul dispositivo dopo ogni correzione.

## Step 14 — Changelog e storia conservata

| Versione | Modifiche principali |
| --- | --- |
| **1.0.8 · build 46** | Generi verificati Techno/House/Dance/Rock, fallback delle copertine, continuità playlist server `v3`, test e script firma Windows |
| **1.0.7 · build 45** | Cerca avanzato artista/album, catalogo server, procedura unsigned/firma locale |
| **1.0.6 · build 43 (sviluppo storico)** | Prima integrazione del protocollo playlist server `v3`, poi evoluta nella 1.0.7; **non è la base della 1.0.8** |
| **1.0.5 · build 42** | URL Funnel fisso :10000 e configurazione Ubuntu/registrazioni |
| **1.0.4 e precedenti** | Migrazione dati verso Ubuntu, classifier, player, Discovery, Android Auto, updater |

L'importazione iniziale proveniva da `Lyra-Android-1.0.5-build42-SORGENTI-COMPLETI-PULITI.zip`. Il relativo hash originale rimane in `MANIFEST.json` **solo per tracciabilità storica**, non come hash degli attuali file.

Le precedenti note `RELEASE_NOTES_*.md`, `CHANGELOG.md` e `LYRA_PROJECT_HISTORY.md` sono state **tolte dal checkout attivo**, non dalla cronologia Git. Per recuperare un documento storico:

```powershell
git show 46310e3758cc9f8d96a95150747ccf64ce082bf2:LYRA_PROJECT_HISTORY.md
```

Anche `LYRA_TECHNICAL_REFERENCE.pdf` è un riferimento storico e non sostituisce questa guida. Per gli aggiornamenti futuri mantenere **questo unico `README.md`** e il manifesto sorgenti, senza aggiungere note parallele per versioni ormai superate.

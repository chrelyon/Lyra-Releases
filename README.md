# Lyra Android 1.0.9 — preparazione release

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

## Requisiti di compilazione

Su Windows: **JDK 21**, Android SDK **Platform 37.0**, **Build Tools 37.0.0**, Git e PowerShell. Il progetto usa **Gradle Wrapper 9.3.1**, Android Gradle Plugin **9.1.1**, Kotlin/Compose plugin **2.4.10**. Servono accesso a Maven/Google/JitPack e `local.properties` oppure `ANDROID_HOME`/`ANDROID_SDK_ROOT`.

```powershell
java -version
git --version
Get-Content .\app\build.gradle.kts | Select-String "versionName|versionCode|applicationId"
```

Dovresti leggere `versionName = "1.0.8"`, `versionCode = 46` e `applicationId = "app.lyra.music"`.

**Warning noti non bloccanti:** differenze nelle versioni XML degli strumenti Android SDK; `Unable to strip ... libandroidx.graphics.path.so, libquickjs.so`; warning `@OptIn ... UnstableApi` in `LyraMediaButtonReceiver.kt`. La presenza del report `build/reports/problems/problems-report.html` non equivale a un errore: verificare comunque `BUILD SUCCESSFUL`.

## Allineamento Git su Windows

```powershell
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

## Test e compilazione Debug

Dalla radice:

```powershell
.\gradlew.bat :app:testDebugUnitTest :innertube:testDebugUnitTest :app:assembleDebug
```

Oppure utilizzare lo script integrato della 1.0.8 (accetta `main` e il ramo RC):

```powershell
.\tools\prepara-release-1.0.8.ps1
```

Risultato: `app\build\outputs\apk\debug\app-debug.apk`. Questa APK usa la **firma debug**, quindi **non deve essere installata sopra la 1.0.7/1.0.8 release firmata**. La CI [verificata](https://github.com/chrelyon/Lyra-Releases/actions/runs/41634746647) ha superato test e build debug della candidata. I test JVM non provano da soli il funzionamento su smartphone.

## Server e collegamento

Il server usa per le playlist rigorose evidenze a livello di **singolo brano**, non il solo genere associato all'artista. Richiesta d'esempio (senza token):

```json
{"genre":"techno","limit":20,"exclude_ids":[],"include_external":false}
```

Le integrazioni esterne sono **facoltative e disabilitate per impostazione iniziale**: `LYRA_PLAYLIST_GENRE_DISCOVERY_ENABLED=false` e `LYRA_PLAYLIST_GEMINI_ENABLED=false`. Last.fm richiede una chiave lato server; Gemini una **credenziale separata lato server**, mai in APK o repository. Il modello può proporre titolo/artista, ma non certificare il genere. Per i dettagli usare [la documentazione tecnica del server](https://github.com/chrelyon/Lyra/blob/main/docs/PLAYLIST_QUALITY_0321.md).


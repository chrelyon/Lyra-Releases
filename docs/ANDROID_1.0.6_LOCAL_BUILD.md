# Lyra Android 1.0.6 RC — sincronizzazione e firma locale

Questa guida non sovrascrive file locali di firma e non pubblica alcuna release.
Base ufficiale 1.0.5 (build 42); candidata 1.0.6 (build 43). Identificativo
Android invariato: app.lyra.music.

## 1. Prerequisiti

- Windows, Git, PowerShell, JDK 21, Android SDK Platform 37 / Android 17
  (per il canale preview il pacchetto SDK puo' chiamarsi android-37.0).
- Repository locale Lyra-Releases, con keystore.properties e chiave di firma
  gia' presenti soltanto sul computer.
- File release-signing.sha256 locale oppure una APK precedente sicuramente
  firmata con la chiave originale nella cartella dist/.
- Server Lyra 0.3.21: installarlo prima di testare Techno/House/Dance/Rock.
  La 0.3.20 non include la nuova API playlist e risponderebbe HTTP 404.

## 2. Allineamento sorgenti Windows

Aprire PowerShell nella directory del progetto. Esempio:

    cd "C:\Percorso\Lyra-Releases"
    git remote -v
    git status --short
    git fetch origin
    git switch --track origin/release/1.0.6-verified-playlists-rc
    git log -1 --oneline

PRIMA di cambiare ramo, se git status mostra modifiche ai file versionati,
salvarle in un commit o in una copia separata. Non usare git reset --hard,
git clean -fdx oppure git push --force. Se il ramo locale esiste gia',
usare git switch release/1.0.6-verified-playlists-rc e git pull --ff-only.

I file ignorati di firma, SDK e dist NON vengono cancellati da git switch.
Se la directory non e' un clone Git, creare una nuova cartella con:

    git clone --branch release/1.0.6-verified-playlists-rc https://github.com/chrelyon/Lyra-Releases.git

Copiare SOLO sul PC i file di firma/SDK gia' esistenti, senza aggiungerli a Git.

## 3. Test Debug prima della firma

    powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\prepara-release-1.0.6.ps1

Lo script verifica ramo/versione, esegue i test app e innertube,
compila la APK Debug e non tocca il telefono.
Risultato: app\build\outputs\apk\debug\app-debug.apk

La APK Debug usa una firma diversa e NON aggiorna l'app Release installata.

## 4. APK Release con la firma originale

    powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\prepara-release-1.0.6.ps1 -Firma

Il comando esegue nuovamente i test, chiama build-release.ps1, controlla
l'impronta SHA-256 della firma con verify-release-signature.ps1 e genera:

- dist\Lyra-1.0.6.apk
- dist\update.json

Viene salvato un backup del vecchio update.json, quando esiste.
Il nuovo JSON e' UTF-8 senza BOM. Nulla viene pubblicato automaticamente.

Se la firma non coincide con quella di una vecchia release, la procedura si
interrompe. Non generare una seconda chiave: gli aggiornamenti Android
richiedono la stessa firma della precedente APK.

## 5. Collaudo dopo l'aggiornamento server

- Confermare che il server Lyra 0.3.21 sia raggiungibile tramite Funnel.
- Installare la APK RELEASE firmata: adb install -r dist\Lyra-1.0.6.apk
- Verificare Techno, House, Dance e Rock (generi verificati); confrontare Pop
  come percorso musicale ancora tradizionale.
- Controllare brano/artista, immagine in playlist, player, lock screen,
  Android Auto, refill, skip, cronologia e ripresa.
- Se non vi sono brani verificati la playlist deve dirlo: non deve inserire
  falsi Techno per riempire la coda.
- Solo dopo i test, pubblicare APK e update.json nella stessa GitHub Release
  con tag v1.0.6.

## Sicurezza

La chiave di firma, le password e i token devono restare sul PC. Le eventuali
chiavi Last.fm, YouTube API e Gemini appartengono al server, mai all'APK.
Il ramo RC contiene sia la correzione cover sia il client per playlist
verificate; non sostituisce la 1.0.5 sul ramo main fino a validazione.

# Lyra Android 1.0.8 RC — sincronizzazione e firma locale

## Base corretta

Questa release candidata deriva **esattamente** dal commit `46310e3758cc9f8d96a95150747ccf64ce082bf2` del ramo `feature/server-playlists`, cioe' **Lyra 1.0.7 build 45**. Non deriva dalla vecchia 1.0.5 su `main` e non dalla candidata 1.0.6, dichiarata obsoleta. Nuova versione `1.0.8`, `versionCode=46`; `applicationId=app.lyra.music` invariato.

L'APK non e' ancora una release ufficiale: sorgenti e firma restano separati. La correzione copertine recupera una thumbnail dal video ID YouTube quando manca un artwork originale, senza affermare che sia sempre la cover dell'album.

## Requisiti

* Windows, Git, PowerShell, JDK 21, Android SDK Platform 37/37.0.
* La directory locale `C:\Users\culiana\AndroidTools\work\Lyra-Android-1.0.7` e' un clone Git sul ramo `feature/server-playlists`.
* I file locali `local.properties`, `keystore.properties`, il keystore e il fingerprint release rimangono sul PC, NON nel repository.
* Server Lyra **0.3.21** installato sull'Ubuntu prima di provare il nuovo endpoint `POST /v1/playlists/verified-genre`. La ricerca esterna via Last.fm e Gemini rimane opzionale e disattivata di default; le playlist verificate usano prima il catalogo.

## Sincronizzare senza perdere modifiche

In PowerShell, nella cartella del progetto:

```powershell
cd "C:\Users\culiana\AndroidTools\work\Lyra-Android-1.0.7"
git status --short
git fetch origin
git switch --track origin/release/1.0.8-verified-playlists-rc
git log -1 --oneline
```

Se `git status --short` non e' vuoto, salvare le modifiche ai file versionati prima del cambio ramo. Se esiste gia' il ramo locale usare `git switch release/1.0.8-verified-playlists-rc` e `git pull --ff-only`. **Non eseguire** `git reset --hard` o `git clean -fdx`: potrebbero cancellare lavoro locale. Git non sostituisce la chiave di firma o le impostazioni SDK ignorate.

## Test (senza firma release)

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\prepara-release-1.0.8.ps1
```

Lo script controlla ramo, versione e presenza di file sensibili tracciati, esegue test JVM e genera `app\build\outputs\apk\debug\app-debug.apk`. **Non installare questa APK debug sopra una app firmata release**: le firme sono diverse.

## Firma originale (solo dopo i test)

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\tools\prepara-release-1.0.8.ps1 -Firma
```

Lo script usa la chiave locale, verifica l'impronta del certificato contro `release-signing.sha256` o una APK precedente attendibile (ad esempio `dist\Lyra-1.0.7.apk`). Non inizia la fiducia dalla stessa 1.0.8 che sta generando. Produce `dist\Lyra-1.0.8.apk` e `dist\update.json` UTF-8 senza BOM, con checksum SHA-256 e impronta del certificato. Se presente, conserva una copia del precedente `update.json`. Non pubblica nulla.

## Verifiche funzionali prima della pubblicazione

1. Server 0.3.21 raggiungibile dallo smartphone; token del classificatore configurato e connessione attiva.
2. Playlist Techno: solo tracce con verifica specifica, non bastano artista elettronico o tag generico.
3. Playlist House/Dance/Rock: nessun fallback Automix durante carico aggiuntivo, skip o ripresa.
4. Playlist tradizionali, ricerca artisti, sincronizzazione e coda server v3 della 1.0.7 invariati.
5. Copertine nella lista, player, schermata bloccata e Android Auto. La miniatura del video e' un fallback, non necessariamente la cover ufficiale.
6. Test della rete indisponibile: una playlist rigorosa segnala l'assenza di brani verificati, senza inventare risultati.
7. Per aggiornare l'installazione corrente usare solo la APK **release firmata** con la chiave originale e lo stesso `applicationId`.

La pubblicazione su GitHub Release `v1.0.8` con APK e `update.json` va effettuata **solo dopo collaudo**, non dal compilatore automatico.

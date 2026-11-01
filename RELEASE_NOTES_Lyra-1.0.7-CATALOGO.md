# Lyra Android 1.0.7 — build 45

Nuova versione Android che include Cerca avanzato per artisti e album già
preparato in build 44, con generi selezionati dalle categorie. Le playlist
continuano a usare il catalogo server: non viene duplicato il DB sul telefono.
La catalogazione unificata richiede il nuovo server 0.3.15.

Il controllo aggiornamenti già presente confronta il versionCode di update.json
con quello installato. La 1.0.7 incrementa versionName e versionCode; gli asset
con nome contenente unsigned non sono selezionati come APK installabile.
Per renderla disponibile occorrono un APK firmato e update.json nella release
GitHub v1.0.7 di chrelyon/Lyra-Releases. Non basta pubblicare i sorgenti o cambiare
il numero. La firma deve essere la stessa dell’app installata per conservare i dati.

## Compilazione non firmata e firma locale su Ubuntu

Dalla cartella del progetto, con JDK 21 e SDK configurati:

```bash
bash tools/build-release.sh --unsigned
```

Il risultato atteso è dist/unsigned/Lyra-1.0.7-unsigned.apk, con checksum.
Questa modalità non legge keystore.properties e non genera update.json.
Il workflow Android unsigned APK, manuale o su push al ramo feature/server-playlists,
prepara lo stesso archivio come artifact
GitHub Actions; non pubblica release e non richiede chiavi di firma.

Per allineare e firmare sul proprio PC:

```bash
bash tools/sign-release-local.sh \
  --input dist/unsigned/Lyra-1.0.7-unsigned.apk \
  --keystore /percorso/del/proprio-keystore.jks \
  --alias proprio-alias \
  --build-tools /home/chrelyon/AndroidTools/android-sdk/build-tools/37.0.0
```

apksigner richiede le password sul PC. Lo script controlla pacchetto e versione
dell’APK, allinea, firma e verifica. Solo dopo genera dist/signed/Lyra-1.0.7.apk
e update.json con SHA-256 dell’APK firmato. Entrambi vanno caricati nella stessa
release. Non condividere keystore o password.

## APK disponibile e stato della verifica

Build GitHub Actions completata con successo il 1 novembre 2026:

- [Scarica l’archivio APK non firmato](https://github.com/chrelyon/Lyra-Releases/actions/runs/41327400653/artifacts/13091464676).
- [Log della build](https://github.com/chrelyon/Lyra-Releases/actions/runs/41327400653).
- Sorgenti compilati: commit `195e249ecc14e0252b37a530982fb35c50407f98`.
- SHA-256 dell’archivio ZIP (non dell’APK):
  `d30a8cdddb78e9206b104be9e71d796d290b926ee4e52368f4ee72bde54ef782`.

L’archivio contiene Lyra-1.0.7-unsigned.apk e SHA256SUMS.txt. Estrarlo prima della
firma. In questo primo artifact il file checksum contiene il percorso assoluto
del runner: per verificarlo dopo l’estrazione, dalla cartella dei due file:

```bash
awk '{print $1 "  Lyra-1.0.7-unsigned.apk"}' SHA256SUMS.txt | sha256sum -c -
```

Le build successive generano già il percorso relativo nel file checksum.
Gli artifact Actions richiedono accesso a GitHub e scadono: questo è conservato
fino al 30 gennaio 2027; scaricarne una copia locale.

Test Kotlin/JVM di Cerca avanzato superati; build release completa, inclusa
compilazione Kotlin e UI, riuscita su GitHub. Script shell controllati.
Il cloud locale resta bloccato su JitPack e non ha potuto rileggere i byte
dell’artifact per i controlli locali. Il digest ZIP è quello dell’upload GitHub.
Non sono stati eseguiti test in emulatore di questa build né una firma reale.
Non è stata pubblicata una nuova GitHub Release installabile: per il controllo
aggiornamenti dell’app servono ancora APK firmato con la propria chiave e
update.json nella release v1.0.7. Non caricare l’APK unsigned come aggiornamento.

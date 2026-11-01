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

## Stato della verifica

Test Kotlin/JVM di Cerca avanzato superati; script shell controllati.
La build completa nel cloud resta bloccata: JitPack non è tra gli host consentiti
dall’ambiente. Non è stato generato un APK 1.0.7, non è stata verificata una firma
reale e non è stata pubblicata una nuova GitHub Release. La compilazione e il test
in emulatore devono essere completati in un ambiente con le dipendenze disponibili.

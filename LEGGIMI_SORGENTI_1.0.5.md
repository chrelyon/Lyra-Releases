# Lyra — sorgenti Android 1.0.5 (build 42)

## Natura del progetto
Questo è il **progetto Android** di Lyra, non un'applicazione desktop nativa per Windows/Linux.
Il codice proviene dall'archivio sorgente `Lyra(2).zip` disponibile nella conversazione.
La versione verificata in `app/build.gradle.kts` è `versionName = "1.0.5"`, `versionCode = 42`.
Nessuna modifica funzionale è stata applicata al codice. Sono stati esclusi file di output/compilazione, cache e configurazioni locali/sensibili.

## Contenuto
- `app/`: applicazione Android Kotlin, UI Jetpack Compose, player Media3, Android Auto, profili, ricerca, playlist, sincronizzazione e diagnostica.
- `innertube/`: modulo Kotlin per l'integrazione YouTube Music e la risoluzione degli stream; include i test presenti nel pacchetto originale.
- `gradle/`, `gradlew`, `gradlew.bat` e file `*.gradle.kts`: configurazione del progetto e Gradle Wrapper.
- `tools/`: script per build, installazione su dispositivo, release firmate e raccolta log.
- Documenti originali: README, cronologia e release notes, inclusa la 1.0.5.

Numero file inclusi: **273** (più questo documento e il manifesto).

## Compilazione su Windows / Debian
Prerequisiti: JDK 21; Android SDK con le piattaforme e gli strumenti richiesti dai file Gradle; accesso ai repository delle dipendenze.
Apri la cartella del progetto in Android Studio, oppure usa dal terminale:

Windows (PowerShell):
```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug --console=plain
```

Debian / Linux:
```bash
chmod +x ./gradlew
./gradlew :app:testDebugUnitTest :app:assembleDebug --console=plain
```

I file `local.properties`, `keystore.properties` e le chiavi di firma **non** sono inclusi.
La build Release richiede la propria chiave originale; non generare una nuova firma per sovrascrivere l'APK già installato.
L'URL di sincronizzazione e le altre opzioni devono essere configurati secondo i file Gradle e le release notes presenti.

## Attenzione
- Il progetto è un client **Android** compilabile da computer; non produce un eseguibile Windows/Linux.
- Non include il server Ubuntu FastAPI attualmente in funzione (consegnato separatamente come Lyra Server 0.3.8).
- Alcuni documenti storici descrivono vecchie versioni o un Worker Cloudflare `/v1`. Non usarli per sovrascrivere il backend attuale.
- Il codice include riferimenti all'endpoint pubblico configurato in Lyra 1.0.5: controllali prima di pubblicare i sorgenti online.
- Non è stata eseguita una compilazione in questa sessione: sono state verificate struttura e integrità dell'archivio.

## Riferimento versione
- Versione app: Android 1.0.5 / build 42.
- Package: `app.lyra.music`.
- Moduli Gradle: `:app` e `:innertube`.
- Dettagli 1.0.5: `RELEASE_NOTES_Lyra-1.0.5-FUNNEL-FISSO.md`.

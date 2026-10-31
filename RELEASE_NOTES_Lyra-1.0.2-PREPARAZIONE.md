# Lyra 1.0.2 — preparazione filtro dei generi (versionCode 39)

## Stato di consegna

Sorgenti completi e patch su base **Lyra 1.0.1 (versionCode 38)**. Non e' un APK gia' compilato, firmato o testato sul dispositivo. Prima di distribuire la release sono necessarie compilazione e prove su Android 15 e Android Auto.

## Modifiche all'app

- **Impostazioni > Verifica del genere musicale**: interruttore inizialmente **OFF**, indirizzo Funnel configurabile (`https://<server>.<tailnet>.ts.net:10000`), campo per il token, pulsanti Salva e Verifica server.
- **Verifica vera della connessione**: `GET /health` richiede modello `ok`, `POST /v1/lookup` verifica Bearer token; fino a connessione valida l'attivazione non riesce.
- **Token**: non compilato nell'APK; cifrato con AES-GCM e chiave non esportabile Android Keystore prima di salvare in SharedPreferences. Il server v0.1.1 usa un **token singolo amministrativo**, per questo la funzione e' destinata per ora a prove personali: non distribuire APK/dispositivi con token condiviso.
- **Playlist automatiche**: nelle radio di preset e nelle continuazioni House/Techno/Dance/Rock, consulta `/v1/lookup` per le canzoni candidate; esclude **solo** `found=true` e `decision=rejected`. Punteggi e dati mancanti, rete assente o timeout non interrompono la riproduzione. Se tutte le candidate fossero escluse, mantiene la coda originale (fail-open).
- **Compatibilita'**: integra l'API gia' esistente del server **Lyra Genre Classifier v0.1.1**, senza modificare Docker/Tailscale, Cloudflare o Youtube Music.
- **Fix Media3 STOP**: ricevitore Bluetooth/Media3 personalizzato usa `shouldStartForegroundService` per non creare un foreground service senza sessione ripristinabile dopo STOP (mantiene il resume normale quando la coda e' salvata). L'implementazione segue la documentazione di `MediaButtonReceiver`.
- **Diagnostica**: riduce i messaggi `uiQueueSynced`/`uiQueueSyncedFallback` ripetuti quando non e' cambiata la canzone o l'indice; aggiunge `LYRA_GENRE` per numero di candidate analizzate/escluse e timeout.

## LIMITAZIONE IMPORTANTE

Il server v0.1.1 puo' classificare **audio**, ma NON scarica audio a partire da un `videoId`. La nuova app consulta **soltanto le classificazioni gia' presenti nella cache del server**. Lyra **non estrae e non invia frammenti YouTube Music**: per ogni nuovo brano senza classificazione, il filtro resta neutro. Il filtro automatico completo su ogni nuova proposta richiede una sorgente legittima di campioni audio e un ulteriore stadio di sviluppo/test di modello. Non considerare l'attivazione del toggle una garanzia di corrispondenza del genere musicale.

## Installazione tramite patch

1. Partire dal repository con i file **identici** a `Lyra-1.0.1-SORGENTI-PULITI.zip`.
2. Copiare il file `Lyra-1.0.2-Preparazione-Classifier.patch` nella radice del repository.
3. Eseguire `git apply --check Lyra-1.0.2-Preparazione-Classifier.patch`, poi `git apply Lyra-1.0.2-Preparazione-Classifier.patch`.
4. Conservare `local.properties` (con `LYRA_PROFILE_SYNC_URL`), `keystore.properties` e la chiave di firma esistente. NON copiare segreti nel repository.
5. Su Windows, usare Java 21, Android SDK per compileSdk 37 e Gradle Wrapper 9.3.1. Eseguire `gradlew.bat :app:testDebugUnitTest` e `gradlew.bat :app:assembleDebug`, poi testare sul dispositivo con il filtro ancora OFF.
6. Avviare il server e verificare Funnel :10000; aprire Impostazioni, inserire URL e token, **Salva**, **Verifica server**, quindi attivare volontariamente il filtro.

### Test manuali obbligatori

1. Upgrade su 1.0.1 senza perdere playlist, profilo, backup Cloudflare e login.
2. STOP > chiusura > riapertura: niente `ForegroundServiceDidNotStartInTimeException` e mini-player chiuso; PAUSA > riapertura: resume ancora funzionante.
3. Android Auto, headset Bluetooth e controlli notifica dopo uno STOP, dopo una PAUSA e con playlist attiva.
4. Funnel non raggiungibile, token errato, `found=false` su tutte le candidate: nessun blocco e nessuna pausa durante il playback.
5. `/v1/lookup` con una classificazione di test `rejected`: il brano non appare nella nuova coda se rimangono alternative valide; verificare log `LYRA_GENRE`.
6. Verificare backup e connettivita' altri container Ubuntu, che non sono modificati da questo pacchetto.

## Verifica effettuata in fase di preparazione

- Compilazione della policy Kotlin pura con `kotlinc` riuscita.
- Controllo degli archivi e confronto patch/sorgenti eseguiti.
- Gradle Android non eseguibile in questo ambiente: la distribuzione Gradle 9.3.1 non e' in cache e Internet non permette il download; inoltre Android SDK non e' disponibile.
- Test strumentali e inferenza reale sul server NON eseguiti.

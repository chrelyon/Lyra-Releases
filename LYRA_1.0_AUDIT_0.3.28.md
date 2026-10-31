# Lyra — audit sorgenti 0.3.28 per release 1.0.0

Audit statico eseguito sullo ZIP integrale fornito dall'utente. La versione letta in `app/build.gradle.kts` e' **0.3.28, versionCode 35**. La patch associata prepara **1.0.0-rc1, versionCode 36**, senza dichiararla gia' stabile.

## Perimetro e limiti

- Esaminati i sorgenti Android, il Manifest, il player Media3, audio, cloud, moduli InnerTube, aggiornamenti GitHub, script di release e il materiale Cloudflare incluso.
- Nell'archivio originario sono presenti 11.770 voci e numerosi artefatti compilati; la copia sorgente RC e' ridotta al codice e alla documentazione, senza file locali o di firma.
- **Nessuna compilazione Android integrale** e nessuna prova fisica con Android Auto: questo ambiente non contiene Android SDK, distribuzione Gradle e keystore dell'utente.
- Passati **31 test JVM isolati** delle regole pure (Android Auto, crossfade, retry analisi e metadati), eseguiti usando Kotlin e un adattatore minimo per JUnit. L'esecuzione effettiva Gradle/JUnit sul PC Windows e' ancora necessaria.

## Findings

### P0 — Bloccanti prima di distribuire i sorgenti

**S01 — Materiale sensibile nell'archivio originale.** Lo ZIP contiene `keystore.properties` (password di firma), `local.properties` (configurazione locale), `.git`, APK debug/release e directory `build/`. Non pubblicare ne' caricare su GitHub quel medesimo ZIP. Il repository li esclude con `.gitignore`, ma `.gitignore` **non impedisce** l'inclusione quando si crea un archivio con una cartella intera. La RC include uno script di packaging ad allowlist e uno ZIP pulito. Le password NON sono copiate nella patch o nella documentazione.

**S02 — Sorgente Worker Cloudflare non allineato al backend vivo.** `cloudflare/lyra-profile-sync/README.md` documenta endpoint `/v1/*`, mentre il client Android in `CloudProfileSync.kt` chiama `/v2/sync`, `/v2/restore` e `/v2/profile`. Non distribuire questo Worker come backend definitivo della 1.0 e non sovrascrivere il Worker Cloudflare funzionante. Per il rilascio completo occorre esportare/revisionare in sicurezza il sorgente `/v2` effettivamente pubblicato.

### P1 — Da verificare prima della 1.0 stabile

**S03 — Backup Android e credenziali di recupero.** `AndroidManifest.xml` ha `android:allowBackup="true"` e `CloudProfileSync.kt` memorizza `listener_id` e `secret` nelle SharedPreferences `lyra_cloud_music_identity_v2`. Verificare la policy Android Auto Backup/device transfer: decidere se escludere esplicitamente questa preferenza tramite regole XML, senza perdere la migrazione cloud. Nessun cambiamento automatico nella RC per non alterare il backup gia' in uso.

**S04 — Validazione checksum dell'updater.** `UpdateInstaller.verifySha256IfAvailable()` tratta il checksum mancante o malformato come verifica superata. L'installer Android rimane protetto dal certificato di firma per gli aggiornamenti dell'app installata, ma la release ufficiale dovrebbe rendere obbligatorio un SHA-256 valido nei nuovi `update.json` e considerare fallback compatibili per le vecchie release.

**S05 — Test automatici dell'app precedentemente assenti.** Il progetto includeva test solo in `innertube/src/test`. La RC introduce test per recovery Android Auto, crossfade, retry dell'analisi locale e policy metadati, con `testImplementation` JUnit 4. Rimangono necessari test Android strumentali su device reale e prova di upgrade senza cancellare dati.

**S06 — Player/Android Auto con elevata concentrazione di responsabilita'.** `PlaybackService.kt` supera 3.600 righe e gestisce service, playlist browser, coda persistita, recovery seek, cloud/feedback e resume. Non riscriverlo prima della 1.0; annotare la modularizzazione tra gli sviluppi post-1.0 e collaudare next/previous ripetuti, duplicati e passaggio fra gapless/crossfade.

### P2 — Manutenzione affrontata nella RC

**S07 — Cronologia e brani duplicati.** `recordHistoryForCurrentIfNeeded()` individuava il primo brano con lo stesso videoId, anche se il brano effettivo era in una posizione successiva della coda. La RC privilegia l'indice nativo e poi quello persistito; evita associazioni sbagliate alla prima occorrenza. Non cambia il formato dati.

**S08 — Retry illimitati sui file locali non analizzabili.** `LocalAudioScanWorker` richiedeva retry a ogni esecuzione con misure rimaste pendenti, anche senza alcun progresso. La RC alterna i blocchi della libreria e termina dopo piu' tentativi consecutivi senza progresso; nuovi scan possono essere avviati normalmente. La policy e' isolata e testata.

**S09 — Repository dimostrativo inutilizzato.** `FakeMusicRepository.kt` non aveva riferimenti nel codice prodotto: rimosso nella RC, senza toccare il vero repository YouTube Music.

**S10 — Release Cloudflare compilabile accidentalmente senza endpoint.** In assenza di `LYRA_PROFILE_SYNC_URL`, il progetto permetteva una Release con `BuildConfig.PROFILE_SYNC_URL` vuota. La RC blocca la build Release se l'endpoint manca o non e' HTTPS. Le build Debug restano possibili senza endpoint.

## Verifiche positive

- `applicationId` e namespace invariati: `app.lyra.music`.
- Lo script release firma con il keystore locale e verifica il fingerprint del certificato (`release-signing.sha256`).
- `update.json` contiene versionCode/versionName, SHA-256 dell'APK e fingerprint del certificato.
- Il modulo InnerTubeX rimane separato e le dipendenze Media3 sono allineate alla stessa versione.
- La persistenza musicale e' mantenuta: la RC non cambia schema, nomi delle preferenze, API Cloudflare o database.

## Gate per promuovere la RC a 1.0.0

1. Build Windows `:app:testDebugUnitTest :app:assembleDebug` riuscita.
2. APK RC installata sulla 0.3.28 **senza disinstallazione**, dati e impostazioni preservati.
3. Test su Android Auto: NEXT/PREV multipli, Mix per me, artwork, ritorno al player; stress con Bluetooth, gapless e crossfade.
4. Test cloud: aggiunta ascolti, sync, ripristino su ambiente di prova e statistiche, senza cancellazione del profilo originale.
5. Verifica firma APK e update.json. NON pubblicare RC come `latest` stabile prima del collaudo.
6. Decisione su backup Android e checksum obbligatorio; recupero versione backend `/v2` per archivio sorgenti ufficiale.
7. Solo dopo gate superati: `versionName = "1.0.0"`, `versionCode = 37` (se 36 e' l'ultima build), tag `v1.0.0`, build Release con chiave originale.

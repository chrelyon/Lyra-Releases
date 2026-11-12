# Lyra — documentazione tecnica e cronologia release

**Release di riferimento:** Android 1.0.27
**versionCode:** 65
**Package:** `app.lyra.music`
**Repository sorgenti:** `https://github.com/chrelyon/Lyra-Releases`
**Ambito:** client Android e moduli inclusi nell'archivio sorgenti.

> Questa documentazione descrive il client Android. Il comportamento del backend va verificato separatamente sui sorgenti server. Non contiene credenziali, token, chiavi di firma o indirizzi privati dei servizi.

## 1. Panoramica

Lyra è un'applicazione musicale Android sviluppata principalmente in Kotlin, Jetpack Compose e AndroidX Media3. Integra riproduzione in background, controlli di sistema, Android Auto, ricerca e discovery musicale, playlist manuali e automatiche, musica locale, personalizzazione e sincronizzazione del profilo musicale.

### Funzionalità principali

- Riproduzione con Play/Pausa, brano precedente/successivo, shuffle e ripetizione.
- Playback in background tramite `MediaLibraryService` e Media3.
- Integrazione Android Auto e controlli multimediali di sistema.
- Ricerca di brani, artisti e album e navigazione delle categorie Scopri.
- Playlist automatiche per genere, radio e Mix per me.
- Preferiti, cronologia, coda persistente e ripresa dell'ascolto.
- Accesso alla libreria musicale locale tramite MediaStore.
- Personalizzazione basata su ascolti, Like, skip e preferenze.
- Sincronizzazione del profilo musicale e backup/ripristino controllati.
- Diagnostica locale con sanitizzazione dei dati e invio volontario/gestito dall'app.
- Controllo aggiornamenti e installazione della release.

## 2. Architettura client

### Playback

`PlaybackService` mantiene il player Media3 indipendente dalla schermata principale. In questo modo la riproduzione può continuare a schermo spento e può essere controllata da Android Auto, Bluetooth e System UI.

### Discovery e sorgenti musicali

I moduli repository gestiscono ricerca, discovery e proposte musicali. Le sorgenti di rete richiedono connettività; i file locali possono essere riprodotti senza rete se il permesso Android è disponibile.

### Libreria locale

`LocalMediaRepository` legge i contenuti audio esposti da MediaStore. Il menu **Dispositivo** continua a mostrare e riprodurre la musica locale indipendentemente dall'impostazione che governa l'uso della libreria nelle playlist automatiche.

### Playlist automatiche

`AutomaticPlaylistManager` gestisce sessioni, code, rifornimenti, deduplicazione e passaggi tra risultati verificati dal server e continuazioni locali. `RecommendationEngine` ordina i candidati in base al contesto di ascolto e alle preferenze disponibili.

### Profilo e sincronizzazione

`ListeningProfileStore`, `ListeningEventStore` e `CloudProfileSync` gestiscono segnali musicali e sincronizzazione. Il client non deve inviare credenziali Google OAuth al backend. L'identità del listener e il relativo segreto sono dati specifici del dispositivo e non devono essere trasferiti tramite Android Auto Backup o Device Transfer.

## 3. Impostazione della libreria del dispositivo — 1.0.27

Nella sezione **Impostazioni → Personalizzazione musicale** è disponibile:

**Usa la libreria del dispositivo nelle playlist automatiche**

- Valore predefinito: attivo, per conservare il comportamento delle versioni precedenti.
- Se attivo, i brani locali possono essere candidati per Play Casuale e per i fallback/continuazioni locali previsti dal motore automatico.
- Se disattivo, la libreria MediaStore non viene aggiunta ai candidati delle playlist automatiche e non viene riletta nei rifornimenti automatici.
- La preferenza non revoca il permesso Android `READ_MEDIA_AUDIO` / `READ_EXTERNAL_STORAGE`.
- La preferenza non nasconde, cancella o modifica la libreria locale.
- Il menu **Dispositivo** e la riproduzione manuale dei file locali continuano a funzionare normalmente.
- Se la preferenza viene disattivata durante una sessione, i brani già presenti nella coda non vengono interrotti; i rifornimenti successivi non aggiungono nuovi brani locali.

La preferenza è indipendente dall'abilitazione generale della personalizzazione, dalla cronologia e dai Preferiti.

## 4. Backup Android e protezione dei dati — 1.0.27

Il Manifest dichiara le regole di backup per Android:

- `app/src/main/res/xml/backup_rules.xml` per il backup tradizionale.
- `app/src/main/res/xml/data_extraction_rules.xml` per cloud backup e trasferimento dispositivo.

Sono esclusi dal backup automatico Android:

- `lyra_cloud_music_identity_v2.xml`, che contiene l'identità listener e il segreto specifico del dispositivo;
- la directory `diagnostics/`, che contiene log, crash e report diagnostici;
- `lyra-device-before-restore.json`;
- `lyra-device-restore.pending.json` e il relativo file temporaneo.

Queste esclusioni non disabilitano il backup musicale personalizzato di Lyra: `DeviceBackup` continua a usare la propria allowlist di preferenze e il flusso cifrato/di ripristino previsto dall'app. L'identità del dispositivo deve essere ristabilita dal flusso Lyra previsto, non copiata dal backup Android.

**Nota di sicurezza:** queste regole riducono l'esposizione dei dati tramite i meccanismi di backup Android. Non sostituiscono una revisione del backend, della gestione delle credenziali, dei log effettivamente inviati e delle policy di conservazione.

## 5. Versione e compatibilità

- `versionName`: `1.0.27`
- `versionCode`: `65`
- Package: `app.lyra.music`
- La release deve essere compilata e firmata con la configurazione e il keystore del proprietario.
- Non includere keystore, password, token, `local.properties` o file di configurazione privati nel repository.
- Per aggiornare un'installazione esistente mantenere la stessa chiave di firma e usare il normale flusso di aggiornamento senza cancellare i dati.

## 6. Verifiche consigliate per 1.0.27

### Libreria locale e playlist

1. Con l'impostazione attiva, verificare che Play Casuale possa usare brani locali.
2. Con l'impostazione disattiva, verificare che Play Casuale non proponga brani MediaStore.
3. Verificare che cronologia e Preferiti continuino a funzionare secondo le rispettive impostazioni.
4. Avviare una playlist automatica con l'impostazione attiva, disattivarla durante la riproduzione e verificare che la coda corrente non venga interrotta e che i successivi rifornimenti non aggiungano nuovi file locali.
5. Verificare che il menu Dispositivo mostri e riproduca sempre i brani locali in entrambe le condizioni.
6. Verificare il comportamento quando non sono disponibili né brani locali ammessi né proposte di rete.

### Backup e ripristino

1. Verificare che il Manifest punti a entrambe le regole XML.
2. Controllare che l'identità listener non venga esportata nel backup Android o nel trasferimento dispositivo.
3. Controllare che i file diagnostici e i journal di ripristino siano esclusi.
4. Eseguire il backup musicale personalizzato e il ripristino Lyra, verificando che il flusso previsto continui a funzionare.
5. Verificare che l'accesso Google venga richiesto nuovamente quando previsto e che non vengano trasferiti token OAuth.

### Build e distribuzione

- Eseguire `git diff --check`.
- Eseguire i test JVM disponibili nel progetto.
- Eseguire una build Release completa con il keystore del proprietario.
- Installare l'APK sopra la versione precedente senza disinstallare l'app.
- Verificare la versione visualizzata e il controllo aggiornamenti dopo la pubblicazione dei metadati di release.

## 7. Cronologia delle release documentate nell'archivio

Questa sezione riunisce le note Markdown presenti nell'archivio 1.0.26 e la nuova release 1.0.27. Le release intermedie per cui non era presente una nota separata nell'archivio non vengono ricostruite per supposizione.

### Android 1.0.13 — versionCode 51

- Scopri semplificato con playlist automatiche, Mix per me, Novità, Italiane e Decenni.
- Ricerca avanzata con completamento automatico per artisti e album.
- Pulizia di menu e codice non più utilizzati.
- Blues e Reggae non erano ancora esposti nei preset verificati del server.

### Android 1.0.14 — versionCode 52

- Aggiunti Blues e Reggae alle playlist automatiche quando supportati dal server.
- Mix per me con profilo Lyra e segnali musicali, senza inviare credenziali Google.
- I file audio locali non vengono inviati al server.
- In assenza dell'algoritmo server il Mix usa la radio YouTube Music locale e code finite.

### Android 1.0.15 — versionCode 53

- Le playlist per genere verificato possono ricevere nuove proposte durante la riproduzione.
- La schermata di riproduzione mostra lo stato della discovery.
- Aggiunta l'opzione per saltare i brani non caricabili, attiva per default.

### Android 1.0.23 — versionCode 61

- Logout Google persistente, anche offline, e protezione contro callback tardive.
- Connessione Lyra subordinata alla scelta esplicita e alla verifica del token.
- Play Casuale utilizzabile senza login Google e senza token Lyra.
- Playlist locali con ranking, filtri e rifornimenti senza duplicati.
- Fallback locale quando il server non è disponibile.

### Android 1.0.24 — versionCode 62

- Apertura del flusso token da Telegram con precompilazione del campo Impostazioni.
- Il token non viene salvato automaticamente: l'utente deve confermare e avviare la verifica.
- Parser dei link con controlli su dominio, percorso e parametri.

### Android 1.0.25 — versionCode 63

- Stato della connessione Lyra unificato e verifica del token integrata.
- Gestione esplicita dell'eliminazione del token locale.
- Preferenza per l'area musicale, con influenza sui nuovi candidati.
- Ripristino del profilo tramite codice monouso del bot.

### Android 1.0.26 — versionCode 64

- Backup delle configurazioni consentite, cronologia e playlist locali.
- Ripristino tramite codice monouso anche dopo perdita del token sul dispositivo.
- Sincronizzazione del profilo musicale tra dispositivi dello stesso utente, con identità distinta per dispositivo.
- Diagnostica e gestione delle segnalazioni bug.
- Android Auto con Dispositivo e Recenti disponibili anche senza autorizzazione server.

### Android 1.0.27 — versionCode 65

- Esclusione dell'identità listener, dei log diagnostici e dei journal temporanei dai backup automatici Android e dal trasferimento dispositivo.
- Nuova preferenza per consentire o escludere la libreria del dispositivo dalle playlist automatiche.
- Il menu Dispositivo e la riproduzione manuale locale restano sempre disponibili.
- La modifica non interviene sul rapporto di composizione 60/40 del server.
- Consolidamento della documentazione Markdown in questo unico documento e aggiornamento del riferimento PDF tecnico.

## 8. Struttura dei componenti principali

| Percorso | Responsabilità |
|---|---|
| `app/src/main/java/app/lyra/music/playback/PlaybackService.kt` | Servizio Media3 e sessione di riproduzione |
| `app/src/main/java/app/lyra/music/playback/PlaybackViewModel.kt` | Comandi UI, code e avvio playlist |
| `app/src/main/java/app/lyra/music/playback/AutomaticPlaylistManager.kt` | Sessioni automatiche e rifornimenti |
| `app/src/main/java/app/lyra/music/local/LocalMediaRepository.kt` | Lettura della libreria MediaStore |
| `app/src/main/java/app/lyra/music/personalization/PersonalizationSettings.kt` | Preferenze di personalizzazione |
| `app/src/main/java/app/lyra/music/personalization/RecommendationEngine.kt` | Ranking e diversificazione |
| `app/src/main/java/app/lyra/music/cloud/CloudProfileSync.kt` | Sincronizzazione profilo musicale |
| `app/src/main/java/app/lyra/music/cloud/DeviceBackup.kt` | Allowlist backup/ripristino Lyra |
| `app/src/main/AndroidManifest.xml` | Permessi, componenti e policy backup |

---

**Documento di riferimento per la release Android 1.0.27 (versionCode 65).**

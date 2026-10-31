# Lyra 1.0.4 (build 41) — Ubuntu esclusivo, migrazione automatica

Base richiesta: **Lyra 1.0.3 (build 40)**. Patch incrementale; non va applicata alla 1.0.2.

## Cambiamenti Android

- Usa automaticamente l'indirizzo `https://…ts.net:10000` gia' configurato nel classifier.
- Non incorpora piu' `LYRA_PROFILE_SYNC_URL` nella build; **nessun fallback al Worker Cloudflare**.
- Al primo avvio della nuova APK, legge `/v2/health` e recupera `/v2/restore` da **Ubuntu**, mantenendo listener ID e codice LYRA2 gia' salvati.
- Il database Ubuntu e' la **copia autorevole**: il profilo remoto viene ripristinato localmente prima di inviare nuovi backup. Il vecchio backlog locale non ancora sincronizzato viene marcato come gia' gestito e NON viene caricato (eventuale perdita degli ascolti recenti accettata).
- Gli eventi registrati durante il primo ripristino rimangono in attesa e vengono inviati soltanto dopo la verifica di Ubuntu.
- Se Ubuntu non risponde, Lyra continua la riproduzione; NON invia nulla a Cloudflare e NON sovrascrive la copia Ubuntu. Il controllo e' ripetibile dal pulsante **Backup ora** e dai job periodici.
- Rimosso il pulsante manuale di confronto fra D1 e Ubuntu, le diciture Cloudflare nella UI, il codice Worker archiviato nel progetto e il parametro Gradle obbligatorio per il Worker.
- Nessun cambiamento al classifier, al motore audio, a InnerTube o ad Android Auto.

## Requisiti

- Il server Ubuntu 0.2.x deve essere gia' installato, con database profili importato e `/v2/restore` autenticato funzionante per il codice di recupero del telefono.
- L'indirizzo Funnel `:10000` deve essere gia' salvato nelle Impostazioni del classifier, come indicato dall'utente.
- Installare come aggiornamento della APK **con la stessa chiave di firma**: NON disinstallare, NON cancellare dati.
- Non e' necessario configurare `LYRA_PROFILE_SYNC_URL` in `local.properties`.

## Collaudo

1. Eseguire `git apply --check` e `git apply` con la patch.
2. Su Windows: `./gradlew.bat :app:testDebugUnitTest :app:assembleDebug` (PowerShell: ` .\\gradlew.bat ...`).
3. Avviare Lyra con rete attiva; verificare **Impostazioni → Backup musicale Ubuntu** e successivo stato `Profilo musicale ripristinato`/`Backup musicale aggiornato`.
4. Verificare che il server Ubuntu aggiunga un nuovo evento dopo un ascolto successivo all'aggiornamento.
5. Disabilitare temporaneamente la connessione e verificare che la musica continui e che nessuna richiesta venga fatta al Worker Cloudflare.
6. Collaudare STOP/riapertura e Android Auto prima di pubblicare la release.

## Cloudflare: eliminazione dei servizi online

La patch **elimina le dipendenze Cloudflare dalla nuova app**, ma non cancella automaticamente servizi nel tuo account: cancellarli prima di installare la APK interromperebbe la versione precedente ancora sul telefono.

Dopo aver verificato la nuova APK e conservato una copia del database Ubuntu e del file SQL esportato, elimina **solo** i servizi Lyra `lyra-profile-sync`, `lyra-dashboard`, i database D1 `lyra-backup` e `lyra-profile`, con la console Cloudflare o Wrangler. NON toccare `palestra-v3-verifica` o `palestra_cloud_backup_31`: sono di un progetto differente.

**Limite:** la compilazione Android completa e il deploy sul telefono/Ubuntu non sono stati eseguiti in questo ambiente. Il test isolato Kotlin sulla policy di instradamento e' riuscito.

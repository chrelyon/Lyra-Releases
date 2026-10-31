# Lyra 1.0.4 — Solo Ubuntu (build 41)

Migrazione automatica dal backend precedente: Ubuntu via Funnel `:10000` e' l'unica destinazione dati, utilizzando l'indirizzo gia' salvato per il classifier. Al primo avvio viene ripristinato il profilo Ubuntu prima di sincronizzare; nessun confronto con il vecchio database e nessun fallback verso Cloudflare. Per prerequisiti e limiti, leggere `RELEASE_NOTES_Lyra-1.0.4-UBUNTU-SOLO.md`.

---

# Lyra 1.0.2 — Preparazione Classifier (build 39)

**Versione sorgente di prova, non ancora compilata e collaudata su Android.**
Il filtro sperimentale del genere e' OFF di default ed e' collegabile al
Lyra Genre Classifier v0.1.1 tramite HTTPS Tailscale Funnel :10000.

- I brani gia' classificati nel database server possono essere esclusi se
  il verdetto risulta incompatibile; quelli sconosciuti restano riproducibili.
- Non si trasmettono campioni audio di YouTube Music dal telefono al server.
- Fix del crash dopo STOP quando MediaButtonReceiver non ha una sessione da riprendere.
- Il profilo Cloudflare e tutte le altre funzioni della 1.0.1 rimangono invariati.

Per installazione e limiti leggere **RELEASE_NOTES_Lyra-1.0.2-PREPARAZIONE.md**.

---

## Documentazione storica della prima release 1.0.0

# Lyra 1.0.0 — La tua musica sempre con te

Prima release ufficiale stabile dell'app Android Lyra, promossa dopo il collaudo della
versione candidata **1.0.0-rc1** su telefono, aggiornamento in-place e Android Auto.

- **versionName:** `1.0.0`
- **versionCode:** `37`
- **applicationId:** `app.lyra.music` (invariato)
- **GitHub Release:** `v1.0.0`

## Caratteristiche

- Ricerca e riproduzione musicale tramite integrazione YouTube Music/InnerTubeX;
  riproduzione di musica locale.
- Player Media3 con normalizzazione, buffering adattivo, gapless e crossfade configurabile.
- Android Auto: libreria musicale, code, comandi, metadati e «Mix per me».
- Scopri e raccomandazioni personalizzate in base ai gusti appresi.
- Statistiche musicali sul telefono e sincronizzazione/ripristino tramite Cloudflare.
- Verifica degli aggiornamenti mediante GitHub Releases.

## Preparazione e compilazione su Windows

1. Estrarre i sorgenti. Preservare `keystore.properties`, chiave di firma e
   `local.properties` **solo sul proprio PC**; non sono distribuiti negli ZIP.
2. Configurare `LYRA_PROFILE_SYNC_URL` in `local.properties` (URL HTTPS **base**
   del Worker Lyra `/v2`, senza aggiungere `/v2` al valore), oppure come
   variabile d'ambiente. Le build Release si rifiutano di procedere senza URL HTTPS.
3. Avviare i test e compilare:

   ```powershell
   .\gradlew.bat :app:testDebugUnitTest :app:assembleDebug --console=plain
   ```

4. Costruire l'APK ufficiale firmato con la **stessa** chiave delle release precedenti:

   ```powershell
   .\tools\build-release.ps1
   ```

5. Pubblicare **insieme**, nella release `v1.0.0` di GitHub:
   `dist/Lyra-1.0.0.apk` e `dist/update.json`. La release deve essere
   **pubblicata e non contrassegnata pre-release**.

Non disinstallare la versione precedente per aggiornare: l'APK firmato deve
installarsi sopra la `1.0.0-rc1`, preservando dati e impostazioni.

Per tutti i controlli di pubblicazione: [GUIDA_RILASCIO_1.0.0.md](GUIDA_RILASCIO_1.0.0.md).
Per le novità: [RELEASE_NOTES_Lyra-1.0.0.md](RELEASE_NOTES_Lyra-1.0.0.md).

## Cloudflare: distinzione importante

Il client Android Lyra 1.0 utilizza un backend Cloudflare `/v2` già pubblicato.
I sorgenti contenuti in `cloudflare/lyra-profile-sync/` documentano una precedente
implementazione **legacy `/v1` non distribuibile come backend attuale**.
Non eseguire mai `wrangler deploy` da quella cartella sul Worker esistente.
Il sorgente della dashboard web autonoma e il backend remoto `/v2` non sono
automaticamente inclusi nell'archivio dell'app Android.

## Sicurezza e distribuzione

Il pacchetto sorgenti ufficiale non contiene alcuna chiave di firma, password
locale, APK già compilata, cache Gradle o directory Git. Le credenziali di
Cloudflare devono essere mantenute fuori dal codice sorgente.

Nessuna licenza open source viene attribuita automaticamente a questo progetto:
i diritti d'uso e la distribuzione dei sorgenti restano a discrezione del titolare.

# Lyra 1.0.0 — Prima versione ufficiale 🎉

**Versione:** 1.0.0 · **Build:** 37 · **Tag:** `v1.0.0`.

La prima versione stabile di Lyra segue il collaudo positivo della release candidata
**1.0.0-rc1 (build 36)**, effettuato su Android, Android Auto e durante
l'aggiornamento senza reinstallazione.

## Funzionalità consolidate

- **Player audio:** YouTube Music/InnerTubeX, brani locali, gapless,
  crossfade opzionale, normalizzazione e buffering intelligente.
- **Android Auto:** comandi Avanti/Indietro, navigazione musicale,
  metadati e accesso al «Mix per me».
- **Personalizzazione:** raccomandazioni apprese, Mix quotidiano e schermata Scopri.
- **Statistiche:** grafici e dettaglio degli ascolti direttamente nell'app.
- **Cloudflare:** backup e sincronizzazione del profilo musicale.
- **Aggiornamenti:** ricerca release GitHub e installazione tramite Android.

## Rispetto alla 1.0.0-rc1

Nessun cambiamento al player, agli algoritmi di raccomandazione,
al database o alla sincronizzazione. L'intervento promuove la build
collaudata alla release stabile con **versionCode 37**, aggiorna
documentazione e preparazione del pacchetto sorgenti.

## Aggiornamento

Installare `Lyra-1.0.0.apk` sulla versione precedente senza disinstallare
e senza cancellare i dati. Firmare con il medesimo certificato già usato.
Distribuire con il relativo `update.json` generato dalla *stessa* build.

## Note tecniche

- `applicationId` invariato: `app.lyra.music`.
- Backend Worker Cloudflare in produzione: `/v2`; i vecchi sorgenti `/v1`
  nel progetto sono solo materiale storico e **non devono essere pubblicati**.
- La generazione della APK Release firmata avviene sul PC del titolare,
  dove risiede la chiave privata. Il pacchetto sorgenti non include un'APK firmata.
- Sicurezza della dashboard web Cloudflare e ulteriore modularizzazione del
  servizio audio rimangono interventi separati, non regressioni introdotte dalla 1.0.

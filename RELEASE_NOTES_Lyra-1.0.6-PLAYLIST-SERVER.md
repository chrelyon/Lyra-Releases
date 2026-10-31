# Lyra 1.0.6, build 43 — integrazione playlist server in collaudo

Sorgenti di sviluppo sul ramo `feature/server-playlists`. Nessun APK ufficiale
pubblicato; la release firmata 1.0.5 rimane invariata.

## Precisione e intento

- Genere o artista richiesto precedono affinità e novità. Il server usa le correzioni
  manuali o specifici tag MusicBrainz sufficientemente sostenuti e identità confermata;
  una semplice corrispondenza dell'identità MB non prova il genere.
- Se il server raggiungibile non ha candidati verificati per un genere, l'app
  segnala che occorre aggiornare il DB musicale. Non avvia una radio generica.
- I preset EDM/elettronica, R&B/soul e indie richiedono al server le rispettive
  alternative esplicite, senza allargare silenziosamente a generi estranei.
- Nuovo campo **Cerca artista** in Scopri → Playlist Automatica. Il fallback locale
  usa crediti dell'artista esatti, non una radio di artisti simili o titoli di cover.
- Corretto il filtro locale che reinseriva tutti i brani rifiutati quando la coda
  sarebbe risultata vuota.
- Dopo gli skip si modifica soltanto la coda futura; non si taglia più il nuovo
  brano appena iniziato. Una richiesta artista non può cambiare cantante dopo skip.

## Collegamento app/server

- Riutilizza l'identità anonima del backup v2. Non introduce token o chiavi nel codice.
- Serve il backup Ubuntu configurato e pronto, connessione server abilitata e
  personalizzazione attiva. I client precedenti continuano a usare il comportamento locale.
- Il server consegna lotti di 12 ID; i metadati vengono richiesti soltanto per gli
  ID non già noti. InnerTubeX continua a risolvere e riprodurre l'audio.
- UUID stabili per retry, cursore ed epoch persistiti; metadati e risposte pendenti
  restano nelle preferenze private dell'app. Il cambio account invalida la sessione.
- Il feedback usa gli stessi UUID di v2: retry e successivi backup non duplicano ascolti.
- Cache di coda prima del fallback locale. Un controllo ogni 30 secondi prepara il
  riallineamento; la coda futura viene applicata alla transizione del brano.
- Se una risposta arriva dopo un ulteriore skip viene conservata per una transizione
  successiva, senza interrompere l'audio. Repeat/shuffle sospendono il riallineamento.
- Inserimento, rimozione o spostamento manuale dei brani termina la sessione automatica,
  conservando la coda modificata. Riavviare una playlist automatica per riprenderla.
- La diagnostica riceve un ID stabile dell'installazione e il modello dichiarato da Android.

## Attivazione e limiti

1. Aggiornare il server alla versione corrispondente nel repository `chrelyon/Lyra`.
2. Completare la compilazione/test Android e firmare localmente con la chiave abituale.
3. Installare sul dispositivo di prova senza disinstallare la versione precedente.
4. In dashboard → Playlist server abilitare **Playlist sui dispositivi compatibili**.
5. Provare genere, artista, skip rapidi, perdita/ripristino rete, coda manuale e repeat.

L'interruttore server resta spento per default ed è disattivabile immediatamente.
Non servono modifiche manuali a `.env` per attivare la consegna. Ricerca esterna e
interprete LLM restano opzionali e disattivati: il ranking attuale è un algoritmo
personalizzato sui dati, non un modello generativo. Non viene chiamato un LLM a ogni skip.

Con server irraggiungibile, il motore locale mantiene continuità ma non può attestare
il genere dei brani sconosciuti: l'interfaccia distingue la modalità locale da quella
verificata dal server. Senza Internet occorre audio già disponibile in cache;
i soli ID non permettono lo streaming.

I mood relax/energia/party/focus/romantica usano sul server le evidenze positive
nel relativo contesto; senza storia sufficiente continuano localmente. Non sono
classificazioni acustiche del mood e non vengono presentati come generi verificati.

## Verifiche eseguite e blocco della build completa

- Suite FastAPI/SQLite del server.
- Compilazione JVM del client, manager e repository playlist reali, con adattatori
  sintetici soltanto per rete Android, YouTube e archivi locali.
- Prove contro il vero server FastAPI: risposta persa, ripristino client, cursori,
  feedback offline, commit ripetuti e guardie per coda manuale/repeat/account.
- Precisione: nessun fallback generico con catalogo verificato vuoto; nessun artista
  estraneo nella ricerca; risposte locali tardive non ripristinano una coda cancellata.

**APK completo non compilato né verificato sul player Media3 reale nel cloud:**
la policy attuale restituisce HTTP 403 per `jitpack.io`, da cui proviene la dipendenza
InnerTubeX 0.6.0 già usata dalla 1.0.5. Le altre dipendenze si risolvono tramite proxy
e CA di sistema. È stata salvata una bozza di configurazione che aggiunge il dominio;
il salvataggio della bozza non applica né pubblica la configurazione.

Dopo l'abilitazione, eseguire la build completa. Firma e pubblicazione degli APK
ufficiali restano locali e separate da questi sorgenti.

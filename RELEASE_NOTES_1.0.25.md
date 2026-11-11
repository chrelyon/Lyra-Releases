# Android 1.0.25 — codice 63

Base: 1.0.24 con apertura token da Telegram. Patch incrementale, non cumulativa.

## Connessione

- Un unico messaggio di stato sopra i pulsanti. Mancata connessione e motivi
  vengono mostrati in rosso: token non valido/revocato, versione non ammessa,
  servizio non pronto o errore di collegamento. Eliminata la riga separata
  sotto i pulsanti. Il monitor conserva il motivo dell'ultima verifica.
- Controlla connessione esegue anche la verifica del token. Rimosso il pulsante
  Verifica token; Salva token conserva il flusso di verifica automatica del monitor.
- Il campo vuoto mostra Token Salvato in verde quando la credenziale è memorizzata;
  non espone il token. Un token salvato non implica che sia autorizzato dal server.
- Elimina token richiede conferma. Rimuove la credenziale dal dispositivo e
  sospende i collegamenti protetti; non revoca il token sul server e non cancella
  identità, profilo, cronologia o backup. Il campo torna a Inserisci token in rosso.

## Area musicale preferita

Eliminate descrizione e sottodescrizioni. La scelta è sempre disponibile anche
senza token. Il default è Internazionale; una scelta già salvata viene mantenuta.
La scelta orienta il motore locale e viene inviata al server. Le nuove ricerche
locali compongono, quando disponibili, proposte provenienti dall'area preferita
con una quota di esplorazione esterna. I metadati di origine noti orientano
la selezione con obiettivo 80/20. Le provenienze sconosciute restano ammesse:
non vengono dedotte da nomi di artisti o titoli. Per Italia + Europa la distinzione
fra italiano e straniero, da sola, non identifica il paese europeo.
File locali privi di questi metadati rimangono disponibili; non è possibile
promettere una percentuale geografica esatta senza informazioni affidabili.
La preferenza influenza i nuovi candidati, non riordina playlist manuali o album.

## Ripristino con codice ricevuto dal bot

Richiede la patch server 0.3.60 e il bot aggiornato. Con il proprio token attivo,
inviare /ripristina in chat privata al bot. Inserire il codice LYRA-R-… nel
Backup musicale → Ripristina da codice. È monouso, scade entro dieci minuti e
non permette di recuperare profili di altri utenti o riattivare token revocati.
Il client importa il profilo originale prima di consentire nuovi upload.

Si ripristina il backup musicale esistente; account Google richiede nuovamente
il login. Backup integrale delle configurazioni e nuove pagine Bugfix/Backup
sono il lavoro urgente già pianificato, non parte di questa release.

## Verifiche e pubblicazione

Passate le prove JVM del motore locale e della priorità regionale con i gestori
Kotlin di produzione e adattatori di rete/ranking. Passati 410 test del server.
La build Android completa e la prova di interfaccia, copia token, eliminazione
e recupero su telefono restano necessarie. Nell'ambiente di build precedente
non era risolvibile il plugin Android 9.1.1; le sue versioni non sono state cambiate.
Non è stato compilato un APK qui e non è stato inviato alcun messaggio Telegram reale.

Compilare con lo stesso keystore e aggiornare con adb install -r, mantenendo i dati.
Pubblicare APK e update.json con versione 1.0.25 e codice 63; soltanto allora il
controllo di versione server si allinea alla nuova release, dopo il suo aggiornamento
periodico. Prima della pubblicazione l'APK di prova può ricevere HTTP 426.

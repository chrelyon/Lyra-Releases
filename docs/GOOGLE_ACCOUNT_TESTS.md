# Verifica modalità anonima e cambio account Google — Lyra 1.0.10

Lyra riproduce musica pubblica e locale anche senza un account Google; le
playlist personali YouTube e le modifiche ai Mi piace richiedono invece
l'autorizzazione ufficiale Google e le necessarie autorizzazioni API.

## Test funzionale su dispositivo Android

**Preparazione:** usa una APK di test firmata con la chiave originale se
devi aggiornare Lyra mantenendo i dati. I test CI producono solo una APK
DEBUG, che non sostituisce la versione RELEASE già installata.

1. Disconnetti Google da Impostazioni. Riapri Lyra: i pulsanti di ricerca,
   le playlist pubbliche, le playlist automatiche locali, la musica locale
   e i controlli multimediali devono restare utilizzabili. La sezione
   playlist personali Google deve risultare vuota o richiedere accesso.
2. Collega l'**account A** da Home e autorizza YouTube. Verifica che appaiano
   solo le playlist musicali e i Preferiti di A.
3. In Impostazioni, premi **Cambia account Google**; seleziona l'**account B**.
   Accetta eventuali autorizzazioni. Verifica playlist e Mi piace di B.
   Nessuna playlist di A deve comparire durante il cambio, anche offline.
4. Chiudi e riapri Lyra. Verifica che la libreria caricata dalla cache
   appartenga a B; senza identità verificata la cache non deve apparire.
5. In Android Auto, verifica la stessa separazione e la riproduzione
   pubblica senza Google. Non attribuire un test emulator-only a un
   dispositivo reale.
6. Disconnetti B, accedi nuovamente ad A e controlla che riappaiano le sue
   playlist e i suoi Preferiti, senza trasferire dati da B.
7. Ripeti con rete scollegata, permessi negati e autorizzazione annullata:
   deve essere impossibile caricare le playlist personali di un altro
   account. Le funzioni che richiedono Internet potranno non essere
   disponibili senza rete.

## Dati che restano distinti

Il token di classificazione Lyra, il profilo musicale anonimo di Lyra e
l'account Google/YouTube sono **tre identità indipendenti**. Il cambio
account Google non deve azzerare il profilo Lyra e non deve collegare
automaticamente Telegram ai profili cloud: tale associazione richiede
un esplicito flusso di abbinamento verificato.

## Limiti di certificazione

La CI esegue compilazione debug e unit test sulle regole di isolamento
di cache e richieste in corso. Non sostituisce prove con due account
Google, OAuth reale, Android Auto, dispositivo fisico e connessione Internet.
I servizi di terze parti potrebbero cambiare comportamenti, limiti o
requisiti di autorizzazione.

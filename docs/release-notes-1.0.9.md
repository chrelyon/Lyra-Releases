# Lyra 1.0.9 (build 47)

## Playlist automatiche
- Selezione dell'algoritmo **Server / Smartphone** nelle Impostazioni, indipendente dalla personalizzazione.
- Supporto ai 17 preset automatici, distinguendo generi musicali e atmosfere.
- Correzione del problema delle playlist con un solo brano: il tasto Next puo richiedere nuovi risultati senza bloccarsi.
- Notifiche contestuali sul player quando il catalogo verificato e limitato o il server non risponde.
- Nessuna sostituzione silenziosa di un genere verificato con brani palesemente incompatibili.

## Monitor riproduzioni
- Il servizio Media3 invia lo stato reale (brano, coda, riproduzione/pausa) al server Lyra 0.3.25.
- Funziona anche con schermo spento e Android Auto, se backup del profilo Ubuntu e connessione server sono abilitati.
- Nessun invio di file audio, URL audio firmati o dati dell'account Google.
- Se il server non e raggiungibile la musica continua normalmente.

## Compatibilita e aggiornamento
- Package ID invariato: `app.lyra.music`.
- Firma release con la chiave personale **gia in uso**; la fingerprint viene confrontata con quella precedente.
- Versione `1.0.9`, `versionCode 47`. Android API 26+, target 36.
- Richiede Lyra Server **0.3.25** per la dashboard Monitor riproduzioni; la selezione locale funziona anche senza server.
- Le verifiche GitHub sono unsigned. L'APK per aggiornare Lyra deve essere firmata su Windows con la stessa chiave dell'app gia installata.

## Note di collaudo
- Provare House, Techno e i restanti preset per Next, refill e coerenza del genere.
- Provare la checkbox Server ON/OFF e la persistenza dopo riavvio.
- Verificare dashboard con telefono in riproduzione, in pausa, a schermo spento, Bluetooth e Android Auto.
- Verificare che senza backup musicale Ubuntu abilitato nessun heartbeat venga inviato.

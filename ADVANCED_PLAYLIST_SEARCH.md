# Cerca avanzato — Android 1.0.6 build 44

In Scopri → Playlist Automatica, «Cerca avanzato» sostituisce il campo libero
Cerca artista. I filtri Artisti e Album selezionano il tipo di risultato.
Dopo due caratteri vengono proposti fino a otto risultati YouTube Music, con
300 ms di debounce. Cambiando testo o filtro si annulla la scelta precedente;
risposte tardive non possono sostituire i risultati nuovi. La ricerca non interpreta
il testo come genere: i generi restano nelle categorie sottostanti.

Scegliere un risultato, poi premere Crea playlist artista o Riproduci album.
L'artista usa il percorso esistente per nome esatto, server o fallback locale;
l'ID del suggerimento non estende il protocollo server per distinguere omonimi.
L'album usa il browse ID selezionato, mostra autore/anno per distinguere le edizioni
e carica la scaletta in ordine. È una coda finita: la continuazione simile viene
sospesa per quella coda, senza cambiare l'impostazione globale. La scelta è
persistita nello snapshot, con valore compatibile per le code precedenti.

## Verifica

`tools/advanced-search-test/run.sh` compila ed esegue il controller e il repository
reali con Kotlin/JVM e coroutines. Solo il provider YouTube è sostituito da dati
sintetici. Verifica tipi, selezione, risposte tardive, debounce, errori/timeout,
identità dell'album e ordine dei brani. Impostare JAVA_HOME e LYRA_GRADLE_RUNTIME
come per tools/protocol-test/run.sh. Non è un test della UI Compose o del player.

La compilazione Android offline nel cloud si ferma alle dipendenze non presenti
in cache, prima di compilare l'app; non è stato prodotto un nuovo APK nel cloud.
Il proprietario ha già compilato la build precedente sul PC Ubuntu.

Sul PC locale, aggiornare il ramo di questa modifica e usare:

```bash
bash tools/test-local-android.sh --root /home/chrelyon/AndroidTools --install-emulator emulator-5554
```

Usare l'emulatore già acceso. Non aggiungere --avd se emulator-5554 è occupato.
La build debug riutilizza i dati dell'emulatore se firma e application ID coincidono;
non disinstallare o cancellare dati in caso di errore di firma.

Collaudo: digitare «Ram» in Artisti e selezionare Rammstein; cambiare testo e
verificare che il pulsante torni disabilitato; passare ad Album, cercare Mutter,
controllare autore/anno, avviare e verificare ordine e fine dell'album. Provare
anche ricerca senza rete, nessun risultato e cambio rapido del testo. I preset
House/Techno/Metal continuano a partire dalle categorie.

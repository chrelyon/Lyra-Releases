# Importazione sorgenti Android — 31 ottobre 2026

Importati 275 file dall'archivio fornito dal proprietario:
`Lyra-Android-1.0.5-build42-SORGENTI-COMPLETI-PULITI.zip`.

SHA-256 dell'archivio originale:

```
2ad871aa2b26f28324f375c70c78b225cad22227992f13927f9a0786fd823364
```

Versione verificata in `app/build.gradle.kts`: `1.0.5`, codice `42`.
Il manifesto pubblico `update.json` della release `v1.0.5` riporta gli stessi valori.
Le istruzioni contenute nei documenti allegati sono state trattate come
materiale del progetto; non sono stati eseguiti script di rilascio o pubblicazione.

## Controllo dei dati sensibili

Controllati nomi e contenuti dei file testuali, testo estratto dal PDF tecnico,
contenuto del JAR Gradle e stringhe letterali ad alta entropia. Nessuna chiave
privata, keystore, password di firma, token GitHub/Google/AWS o token Bearer reale
è stata rinvenuta. Non sono presenti configurazioni locali, `.env`, database
personali, cookie salvati, APK/AAB, log o cache di build.

I riscontri controllati manualmente sono:

- la stringa `secret` usata come nome della preferenza, non come credenziale;
- l'alfabeto per generare identificativi casuali, non un token;
- un identificativo di playlist pubblica utilizzato dalla UI;
- esempi di endpoint nei test e riferimenti al server Funnel pubblico;
- il contatto di supporto usato dall'app e citato nella cronologia del progetto;
- percorsi del package `ui/home/`, non percorsi di un account locale.

L'endpoint Funnel e l'email di supporto sono rimasti nel codice per preservare il
comportamento della versione allegata. Non sono password o token. La verifica è
statica e riguarda questo archivio, non dati presenti sui telefoni o sulla macchina
del proprietario.

## Modifiche per l'importazione

- README aggiornato al progetto Android 1.0.5; originale conservato in
  `docs/provenienza/README-sorgenti-originale.md`.
- MANIFEST aggiornato alla provenienza effettiva; originale conservato in
  `docs/provenienza/MANIFEST-originale.json`. Il vecchio conteggio dichiarato di
  273 file è stato sostituito con i 275 effettivamente presenti nell'archivio.
- `.gitignore` esteso per credenziali, configurazioni locali, dati runtime e output.
- `gradlew` e script shell con terminatori LF ed eseguibili: risolti gli errori
  sintattici dovuti ai terminatori Windows, senza modificare la logica.
- `.gitattributes` per i terminatori degli script su Windows/Linux.
- `SHA256SUMS.txt` per l'integrità dei file importati e dei documenti aggiornati.

Nessuna modifica al codice Kotlin, alle risorse, alle versioni delle dipendenze,
all'algoritmo playlist, alle impostazioni runtime o alla versione dell'app.
Nessuna integrazione Android v3 attivata da questa importazione.

## Compilazioni ufficiali preservate

L'importazione aggiunge un commit a `main`. Non crea né sposta tag, non modifica
release GitHub, APK ufficiali o `update.json`. L'app continua a leggere gli
aggiornamenti dalle release esistenti.

SHA-256 del `update.json` pubblico di `v1.0.5` osservato prima dell'importazione:

```
692d9216ba0f34dc0812b2b20f6dca506ef782db0a12ac41f72f6c8bf86c439f
```

I tag preesistenti vengono confrontati dopo la pubblicazione; nessuna compilazione
o firma ufficiale è stata eseguita nell'ambiente cloud.

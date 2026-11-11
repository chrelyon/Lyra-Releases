# Lyra Android 1.0.23 (versionCode 61)

Base: sorgenti GitHub 1.0.22, commit f41bb28093300b7b790c53d1895838d16b0eeeb3.
Questa patch è incrementale: include già la base di sicurezza pubblicata nella
1.0.22 e non richiede di riapplicare le precedenti patch Android.

## Correzioni

- Il logout Google salva immediatamente una scelta persistente. La riapertura
  dell'app, un aggiornamento silenzioso del token e un risultato Google arrivato
  in ritardo non possono annullare la disconnessione. La revoca remota viene
  comunque richiesta, ma il logout locale funziona anche senza connessione.
  Solo un nuovo accesso esplicito riuscito riabilita il ripristino silenzioso.
- Log diagnostici attivi alla prima inizializzazione; una disattivazione già
  scelta dall'utente viene rispettata. La data di inizio è registrata prima
  dell'avvio del logger, per evitare di recuperare arresti precedenti.
- Connessione Lyra disattivata quando non esiste una scelta salvata. Le scelte
  esplicite precedenti restano valide; il token deve comunque essere verificato.
- Rimossa la voce «Algoritmo playlist del server». Il motore server si abilita
  automaticamente solo con connessione abilitata e token verificato. Il vecchio
  interruttore separato non influenza più il comportamento.
- La Home mostra «Play Casuale» quando l'accesso al server non è autorizzato;
  dopo verifica del token mostra «Scopri». Il pulsante locale è utilizzabile
  senza account Google e senza token Lyra.
- Play Casuale usa musica disponibile sul dispositivo, cronologia e preferenze
  consentite dalle impostazioni Personalizzazione. Il ranking usa il motore
  locale esistente, con ordine iniziale casuale, filtri dei brani bloccati e
  rifornimenti senza duplicati. Se non esistono brani disponibili cerca un
  punto di partenza nel catalogo pubblico YouTube Music, senza login Google.
- Le code locali possono proseguire senza token. Un errore del server durante
  il rifornimento di una playlist verificata attiva la continuazione locale,
  dichiarando che i nuovi brani non sono verificati dal server. I file presenti
  sul dispositivo permettono di continuare anche senza Internet, se Lyra ha
  già il permesso di leggerli. I brani in streaming richiedono Internet.
- Ripristino di Play Casuale dalla coda salvata e dalla cronologia senza token.
  Un risultato locale arrivato dopo Stop o dopo un cambio coda viene scartato.

## Verifiche eseguite

Due harness JVM compilano ed eseguono il codice Kotlin effettivo dei gestori
Google e playlist, con adattatori di prova al posto di Android, Google Play
Services, rete e ranking. Il gestore Personalizzazione e le impostazioni
Diagnostica sono compilati dal codice di produzione. Nessun account reale,
token reale, chiamata Google o invio di email viene usato nelle prove.

Sono passati sette scenari sul motore locale e quattro sul logout Google:
default/preferenze; autorizzazione del motore; avvio e rifornimenti senza token;
primo utilizzo senza account; perdita del collegamento; errore di trasporto;
risposte tardive; ripristino della coda; logout persistente; autorizzazioni in
ritardo; login esplicito fallito o riuscito.

Per ripeterli con Java 21 e la distribuzione Gradle 9.3.1 già estratta:

```bash
export LYRA_GRADLE_RUNTIME=/percorso/gradle-9.3.1
export LYRA_TEST_JSON_JAR=/percorso/json-20240303.jar
bash tools/local-playback-test/run.sh
bash tools/google-logout-test/run.sh
```

La compilazione Android completa è stata tentata, ma nell'ambiente di verifica
non è stato possibile risolvere il plugin `com.android.application:9.1.1`
configurato dal progetto. Le versioni dei plugin non sono state cambiate.
Non è stato prodotto né installato un APK. UI Compose e integrazione reale con
Google Play Services restano da verificare su dispositivo dopo la build.

## Applicazione su Windows (PowerShell)

Dalla radice del checkout Android aggiornato alla 1.0.22, con la patch nella
cartella superiore:

```powershell
git status --short --branch
git pull --ff-only
git apply --check ..\Lyra-Android-1.0.23-correzioni.patch
git apply ..\Lyra-Android-1.0.23-correzioni.patch
git diff --stat
```

Dopo applicazione, usare il normale script di build della release con il
keystore già configurato. Non pubblicare un APK finché la build e le prove sul
telefono non sono concluse. Il server con verifica dell'ultima release
richiederà il codice 61 quando APK e update.json della 1.0.23 saranno pubblicati.

Prove sul telefono: disconnettere Google e chiudere completamente l'app,
riaprirla e verificare account, Preferiti e playlist personali vuoti; ripetere
in modalità aereo. Effettuare poi un nuovo login esplicito. Verificare una
nuova installazione con log attivi e connessione Lyra spenta. Avviare Play
Casuale senza token, superare la prima coda e provare la ripresa dopo riavvio.
Con token verificato, interrompere il collegamento al solo server durante la
riproduzione e verificare la continuazione locale senza interrompere il brano
corrente. Il mancato accesso al server non deve impedire l'accesso a Internet
per YouTube Music; se tutta la rete è assente servono file sul dispositivo.

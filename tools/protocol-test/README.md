# Collaudo JVM/HTTP del protocollo playlist

Questi file sono adattatori di test; Gradle non li include nell'app. Il client,
il manager e il repository playlist compilati dallo script sono quelli reali in
`app/src/main/java`. YouTube, archivi locali, log e trasporto Android sono sintetici.
La scelta finale del player Media3 e il rendering Compose richiedono la build APK.

Prerequisiti: JDK 21, distribuzione Gradle con compilatore Kotlin incluso, JAR JVM
`org.json:json:20250517`, ambiente Python di test del repository server.

1. Dal repository server avviare `python tools/playlist-protocol-bridge.py` con
   `PYTHONPATH` e virtualenv indicati per i test server. Usa un DB temporaneo e
   dati sintetici; ascolta esclusivamente su `127.0.0.1:8766`.
2. Dal repository Android eseguire:

```bash
JAVA_HOME=/percorso/jdk21 \
LYRA_GRADLE_RUNTIME=/percorso/gradle-9.3.1 \
LYRA_TEST_JSON_JAR=/percorso/json-20250517.jar \
./tools/protocol-test/run.sh
```

Lo script compila in una directory temporanea e la elimina al termine. Non usa
segreti, non scarica audio, non modifica i dati del server live e non firma APK.
Arrestare il ponte Python al termine con Ctrl+C.

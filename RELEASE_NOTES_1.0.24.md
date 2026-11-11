# Android 1.0.24, codice 62

Patch incrementale per la 1.0.23. Conserva le correzioni del logout Google,
i valori predefiniti e Play Casuale della precedente versione.

Il pulsante Apri Lyra nel nuovo messaggio Telegram può aprire le Impostazioni
e precompilare il campo token, sia a app chiusa sia a app aperta. Non salva né
attiva automaticamente il token. L'utente deve premere Salva token: soltanto
allora la connessione viene abilitata e viene verificato l'accesso.

Il parser rifiuta domini e percorsi estranei, link HTTP, campi duplicati,
istruzioni di salvataggio automatico e token manuali precedenti al flusso Telegram.
Il token non viene scritto nei log o mantenuto nell'Intent dopo la lettura.
Non vengono cambiati identificativo del profilo, cronologia o permessi ADMIN.

Per l'apertura diretta HTTPS servono Android App Links verificati. La patch
server abbinata fornisce assetlinks.json configurabile con il certificato
pubblico di firma, oltre a una pagina di passaggio con Apri Lyra e Copia token.
La prima apertura può richiedere conferma. Senza verifica del dominio può
servire un secondo tocco nella pagina del browser.

Test JVM del parser superati contro link validi e ostili; manifest XML valido.
Build Android completa, UI e apertura da Telegram da verificare sul telefono.
Il recupero del plugin Android 9.1.1 era bloccato nell'ambiente di build precedente;
le dipendenze del progetto non sono state modificate per aggirare tale errore.
La patch non contiene un APK già compilato.

Dopo la build, aggiornare con adb install -r, mantenendo la firma precedente.
Pubblicare APK e update.json della 1.0.24 per allineare il controllo della versione
sul server. Non disinstallare l'app e non cancellarne i dati per aggiornare.

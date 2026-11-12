# Lyra Android 1.0.26 (codice 64)

Backup completo delle configurazioni consentite, cronologia e playlist locali, con token Lyra protetto nel backup cifrato sul server. Ripristino con il codice monouso del bot Telegram anche se il token è stato perso dopo reinstallazione. Google richiede un nuovo accesso; file audio e permessi Android devono essere selezionati nuovamente.

I dispositivi dello stesso utente condividono il profilo musicale, compresi feedback e ascolti. Ogni dispositivo usa un token distinto. I tentativi ripetuti di sincronizzazione riusano la stessa richiesta; le revisioni del server riallineano i dati dopo sostituzioni e ripristini, evitando il ritorno dei vecchi gusti.

Log inviati al server e gestiti in Segnalazioni bug, senza email. La verifica connessione distingue anche un token già assegnato a un altro dispositivo.

La build release completa va compilata con la configurazione Android/keystore del proprietario. Nessun APK firmato è compreso nella patch.

Android Auto: Home con Play Casuale senza autorizzazione server, oppure Scopri dopo la verifica della connessione; Dispositivo e Recenti disponibili in entrambi i casi. Con Google autorizzato compaiono anche Preferiti e playlist YouTube Music. Home con indicatore verde/rosso e testo dello stato server. Aggiornamento dei contenuti notificato ai browser connessi, ricerca dichiarata disponibile indipendentemente dal token. La resa di schede, icone e ricerca rimane gestita dall’host Android Auto.

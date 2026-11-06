# Lyra Android 1.0.14 (versionCode 52)

Scopri mantiene lo stesso menu nell'app e in Android Auto. Le playlist automatiche
includono ora anche Blues e Reggae, verificati dal server quando configurato.

Con l'algoritmo server attivo, Mix per me usa il profilo Lyra e invia
temporaneamente i metadati dei brani YouTube ascoltati e dei Preferiti Google:
non invia credenziali Google. Il server punta a circa 70% di brani noti e 30%
di proposte pertinenti; il risultato può essere più breve o contenere meno
novità se mancano dati o proposte verificabili. Il titolo segnala le novità
disponibili in un mix parziale. Servono server 0.3.35 o successivo, collegamento
e backup del profilo attivi. I file audio locali non vengono inviati.

Con l'algoritmo server disattivato il Mix usa la radio YouTube Music sul
dispositivo ed è indicato come locale. Le code del Mix sono finite: non
vengono prolungate automaticamente da un'altra radio.

Il controllo aggiornamenti non è stato modificato. La release installabile
richiede la compilazione e la firma con la chiave di distribuzione esistente.

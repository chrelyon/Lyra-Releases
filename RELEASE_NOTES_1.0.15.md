# Lyra Android 1.0.15 (versionCode 53) — in verifica

Le playlist per genere verificato possono ricevere nuove proposte durante la
riproduzione, senza attendere la fine della coda. Serve il server 0.3.38 o
successivo; se la discovery non produce brani verificati, la quota di novità
può essere inferiore all'obiettivo. I brani scoperti vengono registrati nel
catalogo prima di essere consegnati.

L'opzione **Salta i brani non caricabili**, attiva per default e modificabile
nelle impostazioni di riproduzione, passa al brano successivo anche quando
fallisce la risoluzione della sorgente o il buffering rimane bloccato per
60 secondi. Dopo errori consecutivi la riproduzione si ferma anziché ciclare.
Un errore tecnico non dovrebbe essere interpretato come preferenza negativa.

Il codice è preparato per la compilazione e firma con la chiave di distribuzione
esistente. Non è una release pubblicata e richiede ancora la prova sul dispositivo
e la verifica delle fonti effettivamente disponibili sul server installato.

# Lyra Android 1.0.5 · build 42 — Funnel fisso + nuovi utenti

Base: Lyra **1.0.4 build 41**. Questa versione integra anche `/v2/enroll` per installazioni nuove (patch precedentemente preparata 1.0.4→1.0.5).

- Server unico e non modificabile: `https://vivisochat-lab.tail056f21.ts.net:10000`. La porta **10000** è quella del Funnel funzionante (non 1000).
- Connessione al server abilitata per impostazione predefinita; probe HTTPS automatico all'avvio, al ritorno in primo piano e ogni cinque minuti mentre il processo rimane attivo.
- Se il server non risponde: avviso nella Home e nelle Impostazioni, nessuna interruzione della riproduzione; il filtro salta i controlli non disponibili e conserva i brani proposti.
- Nuovo interruttore “Connessione al server Lyra”: per disattivare mostra una conferma; con collegamento disattivato si sospendono **verifica generi e backup Ubuntu** senza cancellare i dati locali o la preferenza preesistente per il backup. Riattivando riprendono i controlli e i backup previsti.
- Interruttore separato “Filtro intelligente dei generi” mantiene la scelta dell'utente e richiede un token valido (configurabile in Impostazioni e cifrato nel Keystore).
- Nessun token amministrativo incorporato nell'APK; accesso pubblico Funnel protetto dal server.
- Nuove installazioni: conservata la registrazione `POST /v2/enroll`, mentre gli account esistenti continuano a essere recuperati dal DB Ubuntu.
- Non modifica player, Media3, Android Auto, algoritmo InnerTube, DB di Ubuntu o schema Cloudflare.

**Limite attuale:** il classificatore filtra i soli brani con verdetti già presenti nella cache; non classifica automaticamente un ID YouTube se non è stato analizzato un campione audio autorizzato.

**Compilazione da eseguire su Windows:** `./gradlew.bat :app:testDebugUnitTest :app:assembleDebug` e poi release con la chiave esistente. Non è stato creato o collaudato un APK in questa sessione.

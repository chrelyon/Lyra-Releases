# Lyra 0.3.27 — Consolidamento Android Auto

**Obiettivo:** stabilizzare i comandi di navigazione e introdurre l'avvio diretto del Mix quotidiano all'interno di Android Auto.

## Layout della libreria

Le quattro sezioni principali della libreria restano invariate: Preferiti (quando disponibile), Le mie playlist, Scopri e Dispositivo. Dentro **Scopri** compare per prima la voce riproducibile **Mix per me**, costruita dallo stesso `PersonalizedMixEngine.DAILY_ID` del pulsante nella Home Android. La schermata **Scopri → Per te** continua a includere gli altri mix.

Se il profilo di ascolto non ha una base sufficiente, la voce non compare ancora. Non vengono inventati brani o raccomandazioni artificiali. Aprire Android Auto anche dopo un riavvio del servizio non richiede la presenza della cache `autoSongContexts` per risolvere il mediaId del mix.

## Navigazione

`LyraSessionPlayer` e' il punto unico che riceve i seek Media3 in modo semantico:

- se la timeline non puo' risolvere il comando (`INDEX_UNSET`), richiama subito il fallback sulla coda Lyra;
- se Media3 tenta un seek nativo, fotografa indice, mediaId e posizione prima dell'azione;
- il servizio ricontrolla l'esito dopo 400 ms, e recupera il comando solo quando il player e' rimasto sullo stesso brano e stessa posizione compatibile con l'avanzamento naturale;
- nessun secondo comando automatico viene emesso dalla notifica `onPlayerInteractionFinished`;
- durante una risoluzione differita, tre comandi possono attendere in FIFO; in caso di errore, la coda d'attesa viene svuotata in sicurezza.

**Limiti dichiarati:** un problema di caricamento reale dello stream, una connessione assente oppure un controller che non invia il comando non sono risolvibili con questo fallback. I log `LYRA_AUTO` e `LYRA_EXTERNAL` aiutano a discriminare la causa su veicolo.

## Compatibilita'

- Non si modifica la struttura D1, la dashboard Cloudflare, il sistema di login o la sincronizzazione.
- Non si modificano gapless, crossfade, normalizzazione, artwork o metadati di autore/cantante.
- Restano le verifiche di firma del tuo script `tools/build-release.ps1`.

## Collaudo obbligatorio

Testare su smartphone e Android Auto, anche con riavvio del servizio, skip consecutivi, brani ripetuti nella coda, repeat, shuffle, crossfade attivo/disattivo e connessione di rete intermittente. Se un comando fallisce, esportare i log degli eventi `LYRA_EXTERNAL` e `LYRA_AUTO`.

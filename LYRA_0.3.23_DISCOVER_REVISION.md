# Lyra 0.3.23 — Revisione Scopri, avvio diretto, Home

Questa patch **incrementale** si applica dopo le precedenti patch 0.3.23:
- `Lyra-0.3.23-Music-Intelligence-V3.patch`
- `Lyra-0.3.23-fix-Kotlin-smart-cast.patch`
- `Lyra-0.3.23-fix-3-warning-Kotlin.patch`

Sono richieste anche le due patch preparatorie 0.3.22 (Android Auto/skip/Scopri e warning/ADB) indicate nei loro LEGGIMI. La correzione dello script PowerShell di verifica firma e' indipendente e deve restare applicata sul PC.

## Comportamento desiderato implementato

1. **Decenni:** soltanto 80, 90, 2000, 2010; il tap costruisce una coda di brani da ricerche YTM. Le tracce senza album datato o con anno album fuori dall'intervallo vengono escluse. Nessuna playlist esterna da scegliere.
2. **Italia** dentro `Italia & Europa`: il tap su Italia costruisce un mix di brani da ricerche YTM a tema italiano, con ordine dinamico e ranking Lyra, senza mostrare playlist. Le altre nazioni rimangono disponibili per non cancellare funzionalita' non espressamente richieste.
3. **Workout:** sostituisce la precedente cartella Attivita' sul telefono e avvia direttamente una coda estratta da playlist di allenamento YTM verificate. Il nome interno `attivita` rimane stabile per compatibilita' con Android Auto.
4. **Generi:** rimossa soltanto da Scopri; i generi rimangono nella Playlist Automatica.
5. **Mood:** il tap sul mood scelto costruisce direttamente un mix dai brani di piu' playlist YTM rilevanti. Non apre un elenco di playlist.
6. **Novita':** soltanto `Hit del momento italiane` e `Hit del momento straniere`. Il tap avvia brani trovati tramite filtro `FILTER_SONG`, con anno album corrente, non playlist esterne. Se i metadata verificabili sono insufficienti, Lyra non inventa una playlist.
7. **Per te:** soltanto Mix quotidiano, Preferiti recenti, Riscoperte. Con una cronologia insufficiente viene mostrato un messaggio, non un mix fittizio.
8. **Playlist Automatica:** usa un seed gia' apprezzato nel genere quando esiste ed e' non recentemente saltato; resta attivo il ranking AI V3/Cloudflare e il fallback YTM, senza modificare il modello dati.
9. **Ascoltati di recente:** grande pulsante sotto Scopri. Premendo un brano, si crea una coda di fino a 100 brani unici nell'ordine visibile, a partire da quello selezionato, senza shuffle e senza radio automatica (repeat iniziale OFF).
10. **Preferiti:** pulsante sotto Dispositivo, casella sotto Cerca lasciata libera. Rimane collegato ai Preferiti YTM esistenti; viene evitato il doppione nell'elenco delle playlist personali.

## Android Auto

Le sottocategorie Scopri convertite in mix sono ora esposte nel browser Media3 come elementi **riproducibili**; la scelta viene risolta da `PlaybackService` in una coda Lyra. Il nome del cantante nei metadata dei brani rimane invariato. Workout sul browser Auto resta dentro la sua cartella (un gruppo), mentre nello smartphone basta toccare la cartella.

## Limiti e verifiche da fare

- `AlbumItem.year` e' **l'anno dell'album**, non sempre quello della prima pubblicazione della registrazione, e non certifica mese/giorno. Per Decenni e Novita' sono esclusi i brani privi di quell'anno. L'anno e' un filtro **conservativo**, non una garanzia storica assoluta.
- La nazionalita' o lingua del brano non e' metadata affidabile per tutte le canzoni YTM; Italia/straniere sono indirizzate da query contestuali e vanno testate con risultati reali. **"Hit" non equivale a una classifica ufficiale per numero di visualizzazioni**: InnerTubeX non espone quel dato per ogni brano in questo percorso.
- Mood e Workout prelevano da playlist YTM ma non ne mostrano la scelta. Il filtro qualita' precedente mantiene massimo 8 playlist per categoria per i percorsi che mostrano ancora playlist.
- La query Decenni puo' impiegare diversi secondi e in alcuni casi produrre meno brani; la coda viene avviata solo dopo il controllo degli anni. La ricerca e' in parallelo con un limite di concorrenza per non sommergere YTM.
- Nessuna modifica di credenziali/chiavi, dati personali, DB Cloudflare, schema cloud, normalizzazione, sistemi di firma, o code precedentemente salvate. `versionName=0.3.23`, `versionCode=28` **restano invariati** per i test; prima della pubblicazione di una nuova build gia' distribuita occorre incrementare il versionCode.

## Applicazione e prova (PowerShell nella cartella Lyra)

```powershell
# Sostituire con il percorso reale della patch
 git apply --check "C:\Percorso\Lyra-0.3.23-Scopri-revisione.patch"
 git apply "C:\Percorso\Lyra-0.3.23-Scopri-revisione.patch"
 .\gradlew.bat :app:assembleDebug --console=plain
```

Non usare `git apply` due volte per la stessa patch. Lo script Release firmata rimane `./tools/build-release.ps1`, da lanciare soltanto dopo il collaudo Debug; non installa via ADB.

Checklist di collaudo: Decenni anni 80/90/2000/2010 con diversi brani; Italia con generi diversi; Workout e ciascun Mood senza apertura playlist; due Hit senza brani dell'anno precedente; tre soli mix Per te; preferiti e cronologia dalla Home; tocco del 37° brano della cronologia e NEXT fino alla fine senza radio automatica; Android Auto artista corretto e gruppi Scopri riproducibili; skip ripetuti su Playlist Automatica; test di ripristino e Cloudflare senza azzerare i dati.

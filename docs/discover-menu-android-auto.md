# Collaudo menu Scopri — Lyra 1.0.12

La schermata Android e il browser Android Auto condividono
`DiscoverMenuCatalog`.

## Menu attesi

- Scopri
  - Playlist Automatica
    - Pop
    - Rock
    - House
    - Techno
  - Mix per me
  - Novità

Le vecchie voci Top musica Italia, Playlist YouTube Music, Generi musicali,
Mood, Workout, Decenni, Italia & Europa e Per te non devono comparire sotto
Scopri in Android Auto.

## Verifica su dispositivo

1. Aggiornare Lyra alla build 50 senza disinstallare la versione precedente.
2. Chiudere completamente Lyra e riconnettere Android Auto.
3. Aprire Lyra > Scopri e annotare le tre voci.
4. Aprire Android Auto > Lyra > Scopri: devono comparire le stesse tre voci
   nello stesso ordine.
5. Aprire Playlist Automatica: devono apparire soltanto Pop, Rock, House e Techno.
6. Provare Mix per me e Novità: entrambi devono avviare direttamente una coda.
7. Se Android Auto conserva una schermata già aperta, uscire dal browser Lyra,
   chiudere la connessione Android Auto e riaprirla: gli ID della nuova
   navigazione sono versionati per invalidare il vecchio albero memorizzato.

La CI verifica catalogo condiviso, test unitari e compilazione. Il controllo
finale della cache del browser e della riproduzione richiede il dispositivo
e Android Auto reali.

# Lyra Android 1.0.13 (versionCode 51)

## Scopri semplificato (app e Android Auto)

Scopri contiene soltanto cinque voci, identiche e nello stesso ordine in app e Android Auto (menu unico `DiscoverMenu`):

1. **Playlist automatiche** — generi verificati dal server Lyra: Pop, Rock, Metal, House, Techno, Elettronica, Dance, Hip Hop, R&B, Jazz, Classica. Le playlist per atmosfera restano nel codice ma non sono mostrate.
2. **Mix per me** — circa 70% brani molto ascoltati e Preferiti dell'account Google, circa 30% proposte nuove coerenti con i gusti. Stesso motore della home.
3. **Novità** — hit italiane e straniere dell'anno corrente, alternate.
4. **Italiane** — canzoni italiane con nuove proposte da YouTube Music.
5. **Decenni** — sottomenu dagli anni 80 agli anni 2020.

## Ricerca avanzata

Un solo campo con completamento automatico per artisti e album; il tocco su un suggerimento avvia subito la riproduzione (artista in casuale, album completo).

## Pulizia

Rimossi menu e codice non più usati (Per te con mix secondari, Workout, Nazioni/scene, terzo livello playlist di Scopri, selettore Artisti/Album).

## Versione

- versionName: 1.0.13
- versionCode: 51

## Note

- Blues e Reggae non sono ancora esposti: il server Lyra attuale non li supporta come preset verificati.
- Novità e Decenni usano i metadati di YouTube Music/Last.fm: senza anno nel DB, i brani senza anno verificabile vengono esclusi.
- Il controllo aggiornamenti non è stato modificato.
- Compilazione e firma release da eseguire localmente prima della distribuzione.

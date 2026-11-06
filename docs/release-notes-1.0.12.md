# Lyra Android 1.0.12 — build 50

- Allineata la sezione **Scopri** di Android Auto alla schermata Android:
  Playlist Automatica, Mix per me e Novità.
- Il sottomenu Playlist Automatica mostra soltanto Pop, Rock, House e Techno.
- Mix per me e Novità sono scorciatoie riproducibili direttamente, senza
  cartelle intermedie.
- Introdotto un catalogo condiviso per definire una sola volta voci, ordine
  e generi mostrati sia nell'app sia in Android Auto.
- Versionati gli ID della navigazione Android Auto e disattivati i vecchi nodi
  Scopri per evitare che il client continui a proporre voci memorizzate.
- Nessuna modifica alle credenziali, al server o alla chiave di firma.

La CI genera una APK DEBUG firmata con la chiave debug, non con la chiave release.
Per distribuire l'aggiornamento serve la compilazione locale con il keystore originale.

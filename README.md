# Lyra Android 1.0.9
Lyra è un'applicazione **Android/Kotlin con Jetpack Compose**. Usa **Media3/ExoPlayer** per la riproduzione e **InnerTubeX/YouTube Music** per ricerca e risoluzione degli stream. Il server contiene catalogo, profilo, metadati e classificazioni: **non distribuisce l'audio**.

| Cartella | Compito |
| --- | --- |
| `app/src/main/java/app/lyra/music/playback/` | Player, servizi, stato coda, playlist automatiche, Android Auto |
| `app/src/main/java/app/lyra/music/ui/` | Schermate Compose, playlist, ricerca e impostazioni |
| `app/src/main/java/app/lyra/music/youtube/` | Catalogo, ricerca e Discovery |
| `app/src/main/java/app/lyra/music/personalization/` | Raccomandazioni e sessioni server playlist `v3` |
| `app/src/main/java/app/lyra/music/genre/` | Token classificatore e playlist a genere verificato |
| `app/src/main/java/app/lyra/music/cloud/` | Backup e sincronizzazione profilo Ubuntu |
| `app/src/main/java/app/lyra/music/update/` | Controllo versioni su GitHub Releases |
| `innertube/` | Modulo per YouTube Music |
| `app/src/test/` e `innertube/src/test/` | Test |
| `tools/` | Build, firma, verifica, logcat, test e archivio sorgenti |
| `.github/workflows/` | CI Android senza credenziali |
| `MANIFEST.json` | Manifesto **dei sorgenti**, non manifesto APK |
| `dist/` | Output locale di build, escluso da Git |

Funzioni preesistenti che vanno preservate: playlist salvate e automatiche, Cerca avanzato artisti e album, libreria locale, preferiti, coda persistente, shuffle/repeat, ascolto in background, Android Auto, personalizzazione, sincronizzazione Ubuntu e aggiornamenti in-app.


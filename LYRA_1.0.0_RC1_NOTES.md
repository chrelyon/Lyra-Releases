# Lyra 1.0.0-rc1 — manutenzione / pre-release

Base: 0.3.28 build 35. Nuova: 1.0.0-rc1 build 36.

- Test JUnit aggiunti all'app: recovery skip Auto, regole crossfade, valutazione metadati e scan audio locale.
- Backup Cloudflare obbligatoriamente configurato come endpoint HTTPS prima di costruire l'APK Release.
- Correzione indice cronologia con videoId ripetuti nella playlist.
- Limitati i retry improduttivi dell'analisi di loudness locale.
- Eliminato il repository demo `FakeMusicRepository` non utilizzato.
- Nuovo script PowerShell `tools/package-source.ps1` per creare un ZIP sorgente senza chiavi, password, directory build, `.git` e APK.
- Nessuna modifica ad algoritmo personalizzazione, normalizzazione, Android Auto UI, playback streaming, schema cloud, chiave APK o dashboard Cloudflare.

NOTA: e' una **Release Candidate**, NON la 1.0 stabile. La compilazione e i test in ambiente Android reale non sono ancora stati eseguiti in questa sessione.

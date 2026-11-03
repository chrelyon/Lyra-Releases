## Preparazione Android 1.0.7 build 45

Vedi [note 1.0.7](RELEASE_NOTES_Lyra-1.0.7-CATALOGO.md) per build non firmata,
firma locale e pubblicazione degli asset di aggiornamento.
[APK non firmato 1.0.7 build 45](https://github.com/chrelyon/Lyra-Releases/actions/runs/41327400653/artifacts/13091464676) compilato con successo su GitHub Actions.

# Lyra Android

Applicazione Android per l'ascolto musicale con YouTube Music/InnerTubeX,
libreria locale, playlist personalizzate e Android Auto.

Questo ramo contiene i **sorgenti Android 1.0.8, build 46, in collaudo**, derivati da **1.0.7 build 45** e conserva
le compilazioni ufficiali già pubblicate nelle [Releases](https://github.com/chrelyon/Lyra-Releases/releases).
L'importazione dei sorgenti non pubblica un nuovo APK e non modifica gli
aggiornamenti disponibili nell'app.

## Versione e repository

- `applicationId`: `app.lyra.music`
- `versionName`: `1.0.8` (release candidate)
- `versionCode`: `46`
- Server/dashboard: [chrelyon/Lyra](https://github.com/chrelyon/Lyra)
- Sorgenti e release Android: questo repository, **chrelyon/Lyra-Releases**.

La versione 1.0.8 continua a utilizzare il server Ubuntu tramite HTTPS Tailscale Funnel sulla porta
10000, con indirizzo fissato dal codice. Il token viene configurato sul dispositivo;
non è incluso nel repository. L'integrazione con il protocollo playlist v3 è in questo ramo di sviluppo.
[Allineamento e firma locale della 1.0.8](docs/ANDROID_1.0.8_SYNC_AND_SIGN.md).
Il ramo di partenza `feature/server-playlists` conserva la **1.0.7**; la 1.0.8 resta candidata finché il collaudo non è completo.

## Struttura

- `app/`: applicazione, player Media3, interfaccia Compose, personalizzazione,
  sincronizzazione del profilo e test.
- `innertube/`: integrazione musicale e relativi test.
- `gradle/`, `gradlew`, `gradlew.bat`: wrapper Gradle.
- `tools/`: script di compilazione, firma locale, verifica e diagnostica.
- [Note della 1.0.5](RELEASE_NOTES_Lyra-1.0.5-FUNNEL-FISSO.md).
- [Verifica e provenienza dell'importazione](docs/IMPORTAZIONE_SORGENTI.md).
- [Documentazione originale allegata](docs/provenienza/README-sorgenti-originale.md):
  contiene anche indicazioni storiche per versioni precedenti.

## Sviluppo

Servono JDK 21, Android SDK Platform 37 e le dipendenze dichiarate dai file Gradle.
Il wrapper usa Gradle 9.3.1; il plugin Android è 9.1.1 e Compose Kotlin è 2.4.10.
Configurare il percorso SDK soltanto sul proprio computer, tramite `local.properties`
o le variabili dell'Android SDK.

Linux/macOS:

```bash
./gradlew :app:testDebugUnitTest :innertube:testDebugUnitTest :app:assembleDebug
```

Windows:

```powershell
.\gradlew.bat :app:testDebugUnitTest :innertube:testDebugUnitTest :app:assembleDebug
```

L'importazione è stata controllata staticamente; questi comandi sono le istruzioni
di compilazione, non una dichiarazione di build già verificata nel cloud.

## Compilazioni ufficiali e firma

La chiave di firma e `keystore.properties` restano esclusivamente sul computer
del proprietario. Per aggiornare le installazioni esistenti serve la stessa firma
delle release precedenti. Gli script `tools/build-release.sh` e
`tools/build-release.ps1` preparano localmente APK e `update.json` in `dist/`.
La pubblicazione di una nuova release è un'operazione separata.

Non aggiungere al repository chiavi, password, token, database personali,
`local.properties`, log o cartelle di build. `.gitignore` esclude questi file.
L'indirizzo pubblico Funnel e il contatto di supporto presenti nei sorgenti sono
configurazioni dell'app e sono stati conservati; non costituiscono credenziali.

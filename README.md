# Lyra Android

Applicazione Android per l'ascolto musicale con YouTube Music/InnerTubeX,
libreria locale, playlist personalizzate e Android Auto.

Questo repository contiene ora i **sorgenti Android 1.0.6 RC, build 43** e conserva
le compilazioni ufficiali già pubblicate nelle [Releases](https://github.com/chrelyon/Lyra-Releases/releases).
L'importazione dei sorgenti non pubblica un nuovo APK e non modifica gli
aggiornamenti disponibili nell'app.

## Versione e repository

- `applicationId`: `app.lyra.music`
- `versionName`: `1.0.6`
- `versionCode`: `43`
- Server/dashboard: [chrelyon/Lyra](https://github.com/chrelyon/Lyra)
- Sorgenti e release Android: questo repository, **chrelyon/Lyra-Releases**.

La versione 1.0.5 usa il server Ubuntu tramite HTTPS Tailscale Funnel sulla porta
10000, con indirizzo fissato dal codice. Il token viene configurato sul dispositivo;
non è incluso nel repository. Il motore di playlist Android non è stato modificato
in questa importazione e non è ancora collegato al nuovo protocollo playlist v3
preparato nel repository server.

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

## Release candidata 1.0.6

La candidata integra selezione dei brani per genere *verificato nel server*,
fallback cover YouTube e test aggiornati. La versione del server richiesta e'
Lyra **0.3.21**, che include `POST /v1/playlists/verified-genre`.

L'APK di produzione non e' inclusa nel repository e va firmata localmente
con la **stessa** chiave delle precedenti release, dopo aver provato la build
di debug. [Guida passo passo Windows](docs/ANDROID_1.0.6_LOCAL_BUILD.md).

Questa e' una *release candidate* in ramo separato: l'app 1.0.5 su `main`
e il suo certificato di firma non vengono modificati.

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

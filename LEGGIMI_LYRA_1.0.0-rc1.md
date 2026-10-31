# Come provare Lyra 1.0.0-rc1 senza rischiare la 0.3.28

## 1. Prerequisiti

- Sorgenti **completi** della 0.3.28 (build 35) forniti nell'archivio dell'utente.
- NON usare le vecchie patch concatenate. Questa patch e' incrementale sulla base 0.3.28 esatta.
- Salvare un backup della directory originale di lavoro e verificare le modifiche con Git.
- Conservare `keystore.properties`, il file `.jks` e `local.properties` **solo sul proprio PC**. Non inserirli in ZIP condivisi.
- `LYRA_PROFILE_SYNC_URL` deve essere definito in `local.properties` o come variabile d'ambiente e puntare a HTTPS. Non aggiungere il suffisso `/v2` all'URL base.

## 2. Applicazione della patch

Aprire PowerShell nella root del progetto, ed estrarre il file `.patch` in una posizione conosciuta:

```powershell
git apply --check "C:\Percorso\Lyra-1.0.0-rc1-Manutenzione.patch"
git apply "C:\Percorso\Lyra-1.0.0-rc1-Manutenzione.patch"
```

Il file `LYRA_PROJECT_HISTORY.md` non viene toccato, per evitare i conflitti gia' incontrati sulle vecchie patch.

## 3. Test e debug

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug --console=plain
```

Nell'installazione manuale usare **solo il pacchetto APK** uscito dalla build. Controllare i permessi e il funzionamento sul dispositivo. Non cancellare i dati e non disinstallare la 0.3.28.

## 4. Release firmata solo dopo i test

```powershell
.\tools\build-release.ps1
```

Il comando richiede la chiave originale, verifica il certificato e genera `dist\Lyra-1.0.0-rc1.apk` + `dist\update.json`. La release deve essere marcata **pre-release** in GitHub, non stabile. L'endpoint GitHub `releases/latest` non deve essere usato per distribuire automaticamente una RC agli utenti della release stabile.

## 5. Pacchetto sorgente pulito

```powershell
.\tools\package-source.ps1
```

Genera `dist\Lyra-1.0.0-rc1-sorgenti.zip` usando una allowlist; verificare sempre il contenuto prima della pubblicazione.

**ATTENZIONE:** i sorgenti legacy `cloudflare/lyra-profile-sync` descrivono API `/v1`, mentre l'app parla con `/v2`. Non fare deploy del Worker incluso su quello gia' attivo.

## 6. Cosa testare prima della 1.0 stabile

Consulta `LYRA_1.0_AUDIT_0.3.28.md`. In caso di errore Gradle, inviare il primo blocco che inizia con `e:` oppure `Execution failed for task` e il percorso del file coinvolto.

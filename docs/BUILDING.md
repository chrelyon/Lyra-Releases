# Compilazione locale Android

Requisiti: JDK 21, Android SDK (API 37 e build-tools) e il wrapper Gradle
incluso nel repository. Non inserire certificati, password o percorsi privati
nei sorgenti pubblici.

## Debug (Windows)

Da PowerShell nella cartella principale del progetto:

```powershell
.\tools\build-debug.ps1
```

L'APK viene generata in
`app/build/outputs/apk/debug/app-debug.apk`. Gradle la firma con la
normale **chiave DEBUG** di sviluppo, non con la chiave release dell'app
distribuita; quindi non sovrascrivere una release installata usando questo APK.

Su Linux/macOS: `bash tools/build-debug.sh`.

## APK release con chiave privata

Per produrre una versione release firmata con la **propria** chiave:

1. Crea una cartella privata, ad esempio `D:\AndroidSigning\`, **fuori
   dal repository pubblico**.
2. Copia `signing.properties.example` in
   `D:\AndroidSigning\signing.properties` e modifica i quattro campi:
   `storeFile` (percorso completo del file `.jks`/`.keystore`),
   `storePassword`, `keyAlias`, `keyPassword`.
3. Per `storeFile` utilizza **slash avanti** anche su Windows, ad esempio
   `D:/AndroidSigning/release-key.jks`: in un file Java Properties le
   barre inverse vengono interpretate come escape.
4. Conserva un backup sicuro della chiave; non caricare il file, password
   o configurazioni su GitHub.

Esegui:

```powershell
.\tools\build-release.ps1 -SigningPropertiesPath "D:\AndroidSigning\signing.properties"
```

Se devi **aggiornare un'app già installata**, passa anche una precedente APK
release firmata con la chiave originale. Lo script blocca la nuova release
se i certificati differiscono:

```powershell
.\tools\build-release.ps1 `
  -SigningPropertiesPath "D:\AndroidSigning\signing.properties" `
  -PreviousApk "D:\AndroidSigning\release-precedente.apk"
```

Lo script crea di default `dist/Lyra-<versione>.apk` e
`dist/update.json`, entrambi esclusi dal versionamento. Puoi scegliere
un'altra cartella con `-OutputDirectory "D:\OutputAndroid"`.

La firma release è **opzionale** e totalmente locale: il repository e la
CI pubblica non contengono o richiedono le chiavi. Nessuna pubblicazione
GitHub è automatica.

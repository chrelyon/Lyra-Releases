# Lyra 1.0.0 — Checklist di pubblicazione GitHub

## 1. Verifiche già comunicate dal collaudo

- [x] La 1.0.0-rc1 è stata compilata correttamente su Windows.
- [x] Aggiornamento dalla versione precedente riuscito.
- [x] Il titolare ha confermato il funzionamento della release candidata
  dopo i test, inclusa la prova delle funzioni principali.

**Da ripetere dopo la build 37:**

- [ ] Installare `1.0.0` sopra `1.0.0-rc1` e verificare i dati.
- [ ] Confermare funzionamento di player, Android Auto e Cloudflare.
- [ ] Controllare la stessa impronta SHA-256 del certificato di firma.

## 2. Creazione APK e manifest aggiornamento

Usare sul PC originale lo **stesso** `keystore.properties`/`.jks` della RC;
non inviare questi file e non aggiungerli a GitHub.
Per una release ufficiale è obbligatorio configurare l'URL HTTPS
`LYRA_PROFILE_SYNC_URL` in `local.properties` o variabile d'ambiente.

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug --console=plain
.\tools\build-release.ps1
```

Attesi:

- `dist/Lyra-1.0.0.apk` — APK firmata, verificata contro `release-signing.sha256`.
- `dist/update.json` — deve riferirsi alla **stessa APK**, con
  `versionCode` 37, `versionName` "1.0.0", `apkName` "Lyra-1.0.0.apk",
  e SHA-256 corrispondente al file APK.

Verifica aggiuntiva del checksum su PowerShell:

```powershell
$info = Get-Content .\dist\update.json -Raw | ConvertFrom-Json
$actual = (Get-FileHash .\dist\Lyra-1.0.0.apk -Algorithm SHA256).Hash.ToLowerInvariant()
if ($info.versionCode -ne 37 -or $info.versionName -ne "1.0.0" -or
    $info.apkName -ne "Lyra-1.0.0.apk" -or $info.sha256 -ne $actual) {
    throw "APK e update.json non corrispondono"
}
"Asset verificati: versione, nome, SHA-256 corretti"
```

## 3. GitHub Release

Repository: https://github.com/chrelyon/Lyra-Releases

1. Aprire la sezione **Releases → Draft a new release**.
2. Creare il nuovo tag **`v1.0.0`** (non sostituire il tag `v1.0.0-rc1`).
3. Titolo suggerito: **Lyra 1.0.0 — Prima versione ufficiale**.
4. Incollare il contenuto di `RELEASE_NOTES_Lyra-1.0.0.md` nella descrizione.
5. Caricare **entrambi** gli asset esatti, dalla medesima build:
   `Lyra-1.0.0.apk` e `update.json`.
6. Non selezionare **Set as a pre-release** e selezionare
   **Set as the latest release** (se disponibile).
7. Pubblicare solo dopo la verifica degli asset.

**Nota:** alla verifica del 22 ottobre 2026 la release `v1.0.0-rc1`
era ancora pubblicata come release normale (*prerelease: false*).
La nuova `v1.0.0` deve sostituirla come più recente ai fini dell'endpoint
`/releases/latest`. Non è necessario cancellare le versioni precedenti.

URL atteso dopo pubblicazione:
https://github.com/chrelyon/Lyra-Releases/releases/tag/v1.0.0

## 4. Controllo finale da telefono

Aprire Lyra → Impostazioni → aggiornamenti. Il download deve puntare
alla `Lyra-1.0.0.apk` corretta. Dopo l'installazione, la versione visualizzata
deve essere 1.0.0 e preferiti, playlist, cronologia e impostazioni devono
essere rimasti invariati.

## 5. Archivio sorgenti ufficiale

Eseguire:

```powershell
.\tools\package-source.ps1
```

Il risultato si trova in `dist/Lyra-1.0.0-sorgenti.zip`. Verificare
l'assenza di `keystore.properties`, `local.properties`, `.jks`, `.git`,
APK, `build`, `dist`, `.gradle`.

Attenzione al vecchio codice `cloudflare/lyra-profile-sync` (/v1):
è presente solo per trasparenza storica; **non usarlo per distribuire
il backend `/v2`**.

<#
.SYNOPSIS
  Verifica il candidato Lyra 1.0.8 e, soltanto con -Firma, produce
  l'APK firmata e update.json usando la chiave LOCALE del proprietario.

.EXAMPLE
  .\tools\prepara-release-1.0.8.ps1
  .\tools\prepara-release-1.0.8.ps1 -Firma
#>
param([switch]$Firma)
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$requiredBranch = "release/1.0.8-verified-playlists-rc"

function Check-LastExit([string]$Step) {
    if ($LASTEXITCODE -ne 0) {
        throw "$Step non riuscito (codice $LASTEXITCODE). Nessuna APK pubblicata."
    }
}

Push-Location $root
try {
    if (-not (Get-Command git -ErrorAction SilentlyContinue)) {
        throw "Git non trovato. Apri una shell che includa Git nel PATH."
    }
    $currentBranch = (& git branch --show-current).Trim()
    Check-LastExit "Lettura ramo Git"
    if ($currentBranch -notin @($requiredBranch, "main")) {
        throw "Ramo errato: '$currentBranch'. Usa '$requiredBranch' oppure 'main' prima della build."
    }

    $gradleText = Get-Content -LiteralPath (Join-Path $root "app\build.gradle.kts") -Raw
    if ($gradleText -notmatch 'versionCode\s*=\s*46\b' -or
        $gradleText -notmatch 'versionName\s*=\s*"1\.0\.8"') {
        throw "Versione non conforme: attesi versionCode 46 e versionName 1.0.8."
    }

    $tracked = (& git ls-files -- keystore.properties local.properties release-signing.sha256)
    Check-LastExit "Verifica segreti versionati"
    if ($tracked) {
        throw "File locali sensibili tracciati da Git: $tracked. Ferma la procedura."
    }
    $dirty = (& git status --porcelain --untracked-files=normal)
    Check-LastExit "Controllo modifiche locali"
    if ($dirty) {
        Write-Warning "Modifiche locali presenti. Non verranno cancellate o ripristinate."
        $dirty | ForEach-Object { Write-Host $_ }
    }
    Write-Host "[Lyra] Sorgenti: $currentBranch"
    Write-Host "[Lyra] Versione: 1.0.8 (build 46)"
    Write-Host "[Lyra] Test Android e APK Debug: nessuna chiave release necessaria."

    if (-not (Get-Command java -ErrorAction SilentlyContinue)) {
        throw "Java non trovato. Serve JDK 21 (JAVA_HOME)."
    }
    if (-not (Test-Path ".\gradlew.bat")) {
        throw "Gradle wrapper non trovato."
    }
    & .\gradlew.bat :app:testDebugUnitTest :innertube:testDebugUnitTest :app:assembleDebug
    Check-LastExit "Test e compilazione Debug"

    $debug = Join-Path $root "app\build\outputs\apk\debug\app-debug.apk"
    if (-not (Test-Path $debug)) {
        throw "Build riuscita ma APK Debug assente: $debug"
    }
    Write-Host "[Lyra] Test e build Debug completati." -ForegroundColor Green
    if (-not $Firma) {
        Write-Host "Per generare la release FIRMATA, ripeti con -Firma."
        Write-Host "La chiave di firma NON viene caricata su GitHub."
        return
    }

    if (-not (Test-Path (Join-Path $root "keystore.properties"))) {
        throw "keystore.properties locale non trovato. Non usare una nuova chiave!"
    }
    $trustedFingerprint = Join-Path $root "release-signing.sha256"
    $dist = Join-Path $root "dist"
    $previousApks = @(Get-ChildItem $dist -Filter 'Lyra-*.apk' -File -ErrorAction SilentlyContinue)
    if (-not (Test-Path $trustedFingerprint) -and $previousApks.Count -eq 0) {
        throw "Manca fingerprint release fidato o precedente APK in dist/. Impossibile confrontare la firma."
    }
    $previousManifest = Join-Path $dist "update.json"
    if (Test-Path $previousManifest) {
        $stamp = Get-Date -Format "yyyyMMdd-HHmmss"
        Copy-Item -LiteralPath $previousManifest -Destination (Join-Path $dist "update-pre-1.0.8-$stamp.json")
        Write-Host "[Lyra] Backup del precedente update.json in dist/."
    }
    & (Join-Path $PSScriptRoot "build-release.ps1")
    Check-LastExit "APK release / verifica della firma"
    Write-Host ""
    Write-Host "Candidato pronto per test sul dispositivo:" -ForegroundColor Green
    Write-Host "  dist\Lyra-1.0.8.apk"
    Write-Host "  dist\update.json"
    Write-Host "Non pubblicare senza aver aggiornato il server Lyra e provato le playlist."
}
finally {
    Pop-Location
}

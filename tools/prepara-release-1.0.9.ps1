<#
.SYNOPSIS
  Lyra 1.0.9 / build 47: synchronise local Windows sources, test, sign with
  the EXISTING private keystore, and optionally publish GitHub Release.
.DESCRIPTION
  No GitHub CI credentials or keystore upload. Never force/reset/delete a
  local branch. The existing signing fingerprint must match the prior APK.
.EXAMPLE
  .\tools\prepara-release-1.0.9.ps1 -Allinea
  .\tools\prepara-release-1.0.9.ps1 -Firma
  .\tools\prepara-release-1.0.9.ps1 -Firma -Pubblica -ConfermaTest
#>
param(
    [switch]$Allinea,
    [switch]$Firma,
    [switch]$Pubblica,
    [switch]$ConfermaTest
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$expectedBranch = 'release/1.0.9-android-auto-mode-and-monitor'
$versionName = '1.0.9'
$versionCode = 47

function Run([string]$Step, [scriptblock]$Command) {
    & $Command
    if ($LASTEXITCODE -ne 0) {
        throw "$Step non riuscito (codice $LASTEXITCODE)."
    }
}
function Current-Branch() {
    $name = (& git branch --show-current).Trim()
    if ($LASTEXITCODE -ne 0 -or -not $name) { throw 'Ramo Git non leggibile.' }
    return $name
}
function Require-Clean() {
    $status = @(& git status --porcelain --untracked-files=normal)
    if ($LASTEXITCODE -ne 0) { throw 'Impossibile controllare lo stato Git.' }
    if ($status.Count -gt 0 -and [string]::IsNullOrWhiteSpace(($status -join '').Trim()) -eq $false) {
        Write-Host ($status -join [Environment]::NewLine)
        throw 'Sorgenti locali modificati. Salvali/committali prima di sincronizzare o pubblicare; nessun reset automatico.'
    }
}
if ($Pubblica -and -not $Firma) { throw 'Pubblicazione richiesta: aggiungi -Firma.' }
if ($Pubblica -and -not $ConfermaTest) {
    throw 'Pubblicazione richiesta: prova prima sul telefono e ripeti con -ConfermaTest.'
}
if (-not (Get-Command git -ErrorAction SilentlyContinue)) { throw 'Git non installato.' }
if (-not (Get-Command java -ErrorAction SilentlyContinue)) { throw 'Serve JDK 21 nel PATH.' }

Push-Location $root
try {
    if ($Allinea) {
        Require-Clean
        Run 'git fetch' { git fetch origin main }
        if ((Current-Branch) -ne 'main') {
            Run 'Selezione main' { git switch main }
        }
        Run 'Allineamento fast-forward main' { git pull --ff-only origin main }
    }

    $branch = Current-Branch
    if ($branch -notin @('main', $expectedBranch)) {
        throw "Ramo '$branch' non valido: usa main o $expectedBranch."
    }
    $remote = (& git remote get-url origin).Trim()
    if ($LASTEXITCODE -ne 0 -or $remote -notmatch '(^|[:/])chrelyon/Lyra-Releases(\.git)?$') {
        throw "Repository remoto inatteso: '$remote'."
    }
    $gradleText = Get-Content 'app\build.gradle.kts' -Raw
    if ($gradleText -notmatch 'versionCode\s*=\s*47\b' -or
        $gradleText -notmatch 'versionName\s*=\s*"1\.0\.9"') {
        throw 'Versione non conforme: attesi 1.0.9 / build 47.'
    }
    if ((& git ls-files -- keystore.properties local.properties release-signing.sha256)) {
        throw 'File sensibili tracciati da Git: operazione bloccata.'
    }
    if ($Pubblica) {
        if ($branch -ne 'main') { throw 'Per pubblicare serve main aggiornato.' }
        Require-Clean
        Run 'Controllo main remoto' { git fetch origin main }
        $head = (& git rev-parse HEAD).Trim()
        $remoteHead = (& git rev-parse origin/main).Trim()
        if ($head -ne $remoteHead) {
            throw 'HEAD locale diverso da origin/main. Esegui prima -Allinea.'
        }
        $tagExists = & git ls-remote --tags origin 'refs/tags/v1.0.9'
        if ($LASTEXITCODE -ne 0) { throw 'Impossibile verificare tag remoti.' }
        if ($tagExists) { throw 'Tag v1.0.9 gia esistente: non sovrascrivo una Release.' }
    }

    Write-Host '[Lyra] Esecuzione test unitari e compilazione debug...' -ForegroundColor Cyan
    Run 'Test e build debug' {
        .\gradlew.bat :app:testDebugUnitTest :innertube:testDebugUnitTest :app:assembleDebug
    }
    $debug = Join-Path $root 'app\build\outputs\apk\debug\app-debug.apk'
    if (-not (Test-Path $debug)) { throw 'APK debug non generata.' }
    Write-Host '[Lyra] Test e build debug superati.' -ForegroundColor Green

    if (-not $Firma) {
        Write-Host '[Lyra] Per compilare con la tua firma, ripeti con -Firma.'
        return
    }
    if (-not (Test-Path 'keystore.properties')) {
        throw 'keystore.properties mancante. Usa la chiave ESISTENTE, non crearne una nuova.'
    }
    $trusted = Test-Path 'release-signing.sha256'
    $previous = Test-Path 'dist\Lyra-1.0.8.apk'
    if (-not $trusted -and -not $previous) {
        throw 'Serve il fingerprint release-signing.sha256 oppure dist\Lyra-1.0.8.apk firmata con la vecchia chiave.'
    }

    Run 'Compilazione, firma e verifica certificato' { & '.\tools\build-release.ps1' }
    $apk = Join-Path $root 'dist\Lyra-1.0.9.apk'
    $manifest = Join-Path $root 'dist\update.json'
    if (-not (Test-Path $apk) -or -not (Test-Path $manifest)) {
        throw 'APK o update.json assente.'
    }
    $meta = Get-Content -LiteralPath $manifest -Raw | ConvertFrom-Json
    $digest = (Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($meta.versionCode -ne 47 -or $meta.versionName -ne '1.0.9' -or
        $meta.apkName -ne 'Lyra-1.0.9.apk' -or $meta.sha256 -ne $digest) {
        throw 'Manifest update.json non corrisponde ai byte della APK firmata.'
    }
    Write-Host "[Lyra] Firma e manifest coerenti: $digest" -ForegroundColor Green

    if (-not $Pubblica) {
        Write-Host '[Lyra] Release locale pronta. Collaudala sul telefono prima di pubblicarla.'
        Write-Host '[Lyra] Per pubblicare: -Firma -Pubblica -ConfermaTest.'
        return
    }
    if (-not (Get-Command gh -ErrorAction SilentlyContinue)) {
        throw 'GitHub CLI (gh) non installato: installa gh ed esegui gh auth login.'
    }
    Run 'Verifica accesso GitHub' { gh auth status }
    $head = (& git rev-parse HEAD).Trim()
    $notes = Join-Path $root 'docs\release-notes-1.0.9.md'
    if (-not (Test-Path $notes)) { throw 'Note di rilascio mancanti.' }
    Run 'Creazione release firmata GitHub' {
        gh release create 'v1.0.9' $apk $manifest --repo 'chrelyon/Lyra-Releases' `
            --target $head --title 'Lyra 1.0.9' --notes-file $notes --latest
    }
    $published = & gh release view 'v1.0.9' --repo 'chrelyon/Lyra-Releases' --json assets,tagName | ConvertFrom-Json
    if ($LASTEXITCODE -ne 0) { throw 'Release pubblicata ma verifica GitHub fallita.' }
    $assetNames = @($published.assets | ForEach-Object { $_.name })
    if ('Lyra-1.0.9.apk' -notin $assetNames -or 'update.json' -notin $assetNames) {
        throw 'La Release manca degli asset APK o update.json: controlla GitHub.'
    }
    Write-Host 'Release GitHub v1.0.9 pubblicata con APK firmata e update.json.' -ForegroundColor Green
}
finally {
    Pop-Location
}

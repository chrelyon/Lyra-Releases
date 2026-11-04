<#
.SYNOPSIS
  Build generica di una APK release Android firmata, senza chiavi nel repo.
.DESCRIPTION
  L'utente conserva signing.properties e keystore fuori dal repository
  e ne passa il percorso completo. Controlla la firma con apksigner,
  crea l'APK in dist e update.json con SHA-256.
.PARAMETER SigningPropertiesPath
  Percorso ASSOLUTO del file di configurazione privata (vedere
  signing.properties.example). Esempio: D:\AndroidSigning\signing.properties
.PARAMETER PreviousApk
  APK release firmata precedentemente: se presente, il certificato DEVE
  corrispondere. Necessario per garantire aggiornamenti in-place.
.PARAMETER OutputDirectory
  Dove salvare APK e update.json. Default: dist/ nel progetto.
.EXAMPLE
  .\tools\build-release.ps1 -SigningPropertiesPath "D:\AndroidSigning\signing.properties" -PreviousApk "D:\AndroidSigning\precedente.apk"
#>
param(
    [Parameter(Mandatory = $true)]
    [string]$SigningPropertiesPath,

    [string]$PreviousApk = "",
    [string]$OutputDirectory = ""
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$gradle = Join-Path $root 'gradlew.bat'
$buildFile = Join-Path $root 'app\build.gradle.kts'

if (-not (Test-Path -LiteralPath $gradle -PathType Leaf)) {
    throw 'Gradle wrapper non trovato.'
}
if (-not (Test-Path -LiteralPath $SigningPropertiesPath -PathType Leaf)) {
    throw 'File signing.properties non trovato al percorso indicato.'
}
$propertiesFile = (Resolve-Path -LiteralPath $SigningPropertiesPath).Path

$gradleText = Get-Content -LiteralPath $buildFile -Raw
$version = [regex]::Match($gradleText, 'versionName\s*=\s*"([^"]+)"')
$build = [regex]::Match($gradleText, 'versionCode\s*=\s*(\d+)')
if (-not $version.Success -or -not $build.Success) {
    throw 'versionName/versionCode non rilevati in app/build.gradle.kts.'
}
$versionName = $version.Groups[1].Value
$versionCode = [int]$build.Groups[1].Value

if (-not $OutputDirectory) { $OutputDirectory = Join-Path $root 'dist' }
[void](New-Item -ItemType Directory -Path $OutputDirectory -Force)
$outputDir = (Resolve-Path -LiteralPath $OutputDirectory).Path

$sdkCandidates = @($env:ANDROID_SDK_ROOT, $env:ANDROID_HOME)
if ($env:LOCALAPPDATA) {
    $sdkCandidates += (Join-Path $env:LOCALAPPDATA 'Android\Sdk')
}
$apksigner = $null
foreach ($sdk in $sdkCandidates) {
    if (-not $sdk) { continue }
    $btDir = Join-Path $sdk 'build-tools'
    if (-not (Test-Path -LiteralPath $btDir)) { continue }
    $dirs = Get-ChildItem -LiteralPath $btDir -Directory |
        Sort-Object { try { [version]$_.Name } catch { [version]'0.0' } } -Descending
    foreach ($d in $dirs) {
        $candidate = Join-Path $d.FullName 'apksigner.bat'
        if (Test-Path -LiteralPath $candidate) {
            $apksigner = $candidate
            break
        }
    }
    if ($apksigner) { break }
}
if (-not $apksigner) { throw 'Android SDK build-tools: apksigner.bat non trovato.' }

function Get-Fingerprint([string]$Apk) {
    $lines = & $apksigner verify --print-certs $Apk 2>&1 | Out-String
    if ($LASTEXITCODE -ne 0) { throw 'APK non firmata correttamente o apksigner fallito.' }
    $match = [regex]::Match(
        $lines, 'certificate SHA-256 digest:\s*([0-9a-fA-F:]+)',
        [System.Text.RegularExpressions.RegexOptions]::IgnoreCase
    )
    if (-not $match.Success) { throw 'Fingerprint certificato non trovata.' }
    return $match.Groups[1].Value.Replace(':', '').ToLowerInvariant()
}

Push-Location $root
try {
    & $gradle ':app:assembleRelease' "-PlyraSigningPropertiesFile=$propertiesFile"
    if ($LASTEXITCODE -ne 0) { throw "Build Gradle fallita (exit $LASTEXITCODE)." }

    $source = Join-Path $root 'app\build\outputs\apk\release\app-release.apk'
    if (-not (Test-Path -LiteralPath $source -PathType Leaf)) {
        throw 'APK release non prodotta; verificare la firma Gradle.'
    }
    $actualSigner = Get-Fingerprint $source

    if ($PreviousApk) {
        if (-not (Test-Path -LiteralPath $PreviousApk -PathType Leaf)) {
            throw 'APK precedente indicata non trovata.'
        }
        $previousSigner = Get-Fingerprint ((Resolve-Path -LiteralPath $PreviousApk).Path)
        if ($actualSigner -ne $previousSigner) {
            throw 'CERTIFICATO DIVERSO: non puoi aggiornare la vecchia installazione con questa chiave.'
        }
    }
    else {
        Write-Warning 'Nessuna APK precedente per confronto: verifica la compatibilita della firma prima di aggiornare un''app installata.'
    }

    $apkName = "Lyra-$versionName.apk"
    $destination = Join-Path $outputDir $apkName
    Copy-Item -LiteralPath $source -Destination $destination -Force
    $digest = (Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash.ToLowerInvariant()
    $update = [ordered]@{
        versionCode = $versionCode
        versionName = $versionName
        apkName = $apkName
        sha256 = $digest
        signerSha256 = $actualSigner
        mandatory = $false
        notes = "Lyra $versionName"
    }
    $jsonPath = Join-Path $outputDir 'update.json'
    [System.IO.File]::WriteAllText(
        $jsonPath, ($update | ConvertTo-Json) + [Environment]::NewLine,
        [System.Text.UTF8Encoding]::new($false)
    )
    Write-Host 'APK release firmata e verificata:' -ForegroundColor Green
    Write-Host "  $destination"
    Write-Host "  $jsonPath"
    Write-Host "  SHA256 APK: $digest"
    Write-Host "  SHA256 certificato: $actualSigner"
}
finally {
    Pop-Location
}

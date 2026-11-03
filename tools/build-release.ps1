$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $PSScriptRoot
$gradleFile = Join-Path $root "app\build.gradle.kts"
$gradleText = Get-Content $gradleFile -Raw

$versionNameMatch = [regex]::Match($gradleText, 'versionName\s*=\s*"([^"]+)"')
$versionCodeMatch = [regex]::Match($gradleText, 'versionCode\s*=\s*(\d+)')

if (-not $versionNameMatch.Success -or -not $versionCodeMatch.Success) {
    throw "Impossibile leggere versionName/versionCode da app/build.gradle.kts"
}

$versionName = $versionNameMatch.Groups[1].Value
$versionCode = [int]$versionCodeMatch.Groups[1].Value

Push-Location $root
try {
    & .\gradlew.bat assembleRelease
    if ($LASTEXITCODE -ne 0) {
        throw "assembleRelease non riuscito (exit code $LASTEXITCODE)"
    }

    $sourceApk = Join-Path $root "app\build\outputs\apk\release\app-release.apk"
    if (-not (Test-Path $sourceApk)) {
        throw "APK release non trovato: $sourceApk"
    }

    $signatureVerifier = Join-Path $PSScriptRoot "verify-release-signature.ps1"

    if (-not (Test-Path $signatureVerifier)) {
        throw "Verifier firma non trovato: $signatureVerifier"
    }

    $signerSha256 = & $signatureVerifier `
        -ApkPath $sourceApk `
        -ProjectRoot $root

    if ([string]::IsNullOrWhiteSpace($signerSha256)) {
        throw "Verifica firma completata senza fingerprint."
    }

    $dist = Join-Path $root "dist"
    New-Item -ItemType Directory -Force -Path $dist | Out-Null

    $apkName = "Lyra-$versionName.apk"
    $destApk = Join-Path $dist $apkName
    Copy-Item $sourceApk $destApk -Force

    $sha256 = (Get-FileHash $destApk -Algorithm SHA256).Hash.ToLowerInvariant()
    $metadata = [ordered]@{
        versionCode = $versionCode
        versionName = $versionName
        apkName = $apkName
        sha256 = $sha256
        signerSha256 = $signerSha256.Trim().ToLowerInvariant()
        mandatory = $false
        notes = "Aggiornamento Lyra $versionName"
    }
    # Windows PowerShell 5.1 writes a UTF-8 BOM by default; JSON consumers
    # may reject it. Always emit strict UTF-8 without BOM.
    $updatePath = Join-Path $dist "update.json"
    $json = $metadata | ConvertTo-Json
    [System.IO.File]::WriteAllText(
        $updatePath,
        $json + [Environment]::NewLine,
        [System.Text.UTF8Encoding]::new($false)
    )

    Write-Host ""
    Write-Host "Release pronta:" -ForegroundColor Green
    Write-Host "  $destApk"
    Write-Host "  $(Join-Path $dist 'update.json')"
    Write-Host "SHA-256: $sha256"
    Write-Host "Certificate SHA-256: $signerSha256"
    Write-Host ""
    Write-Host "Carica entrambi i file come asset della stessa GitHub Release (tag v$versionName)."
}
finally {
    Pop-Location
}

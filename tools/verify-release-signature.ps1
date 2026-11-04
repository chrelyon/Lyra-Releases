param(
    [Parameter(Mandatory = $true)]
    [string]$ApkPath,

    [Parameter(Mandatory = $true)]
    [string]$ProjectRoot
)

$ErrorActionPreference = "Stop"

$ApkPath = (Resolve-Path $ApkPath).Path
$ProjectRoot = (Resolve-Path $ProjectRoot).Path

$sdkCandidates = @()

if (-not [string]::IsNullOrWhiteSpace($env:ANDROID_SDK_ROOT)) {
    $sdkCandidates += $env:ANDROID_SDK_ROOT
}

if (-not [string]::IsNullOrWhiteSpace($env:ANDROID_HOME)) {
    $sdkCandidates += $env:ANDROID_HOME
}

if (-not [string]::IsNullOrWhiteSpace($env:LOCALAPPDATA)) {
    $sdkCandidates +=
        (Join-Path $env:LOCALAPPDATA "Android\Sdk")
}

$sdk =
    $sdkCandidates |
    Where-Object { Test-Path $_ } |
    Select-Object -First 1

if ($null -eq $sdk) {
    throw "Android SDK non trovato."
}

$buildTools =
    Get-ChildItem `
        (Join-Path $sdk "build-tools") `
        -Directory `
        -ErrorAction SilentlyContinue |
    Sort-Object {
        try { [version]$_.Name }
        catch { [version]"0.0" }
    } -Descending

$apksigner = $null

foreach ($dir in $buildTools) {
    $candidate =
        Join-Path $dir.FullName "apksigner.bat"

    if (Test-Path $candidate) {
        $apksigner = $candidate
        break
    }
}

if ($null -eq $apksigner) {
    throw "apksigner.bat non trovato."
}

function Get-SignerFingerprint([string]$Path) {

    $output =
        (& $apksigner verify --print-certs $Path 2>&1 |
            Out-String)

    if ($LASTEXITCODE -ne 0) {
        throw "Firma APK non valida: ${Path}"
    }

    $match = [regex]::Match(
        $output,
        'certificate SHA-256 digest:\s*([0-9A-Fa-f:]+)',
        [System.Text.RegularExpressions.RegexOptions]::IgnoreCase
    )

    if (-not $match.Success) {
        throw "Fingerprint SHA-256 non rilevato."
    }

    return (
        $match.Groups[1].Value `
            -replace ':', ''
    ).ToLowerInvariant()
}

$actual =
    Get-SignerFingerprint $ApkPath

$trustedFile =
    Join-Path $ProjectRoot "release-signing.sha256"

if (Test-Path $trustedFile) {

    $trustedFingerprint = Get-Content -LiteralPath $trustedFile -Raw
    $expected = $trustedFingerprint.Trim().Replace(":", "").ToLowerInvariant()

}
else {

    $dist =
        Join-Path $ProjectRoot "dist"

    $gradleText = Get-Content -LiteralPath (Join-Path $ProjectRoot "app\build.gradle.kts") -Raw
    $matchVersion = [regex]::Match($gradleText, 'versionName\s*=\s*"([^"]+)"')
    if (-not $matchVersion.Success) { throw "Versione Lyra non identificata." }
    $CurrentVersion = $matchVersion.Groups[1].Value

    $previous =
        Get-ChildItem `
            $dist `
            -Filter "Lyra-*.apk" `
            -File `
            -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -ne "Lyra-$($CurrentVersion).apk" } |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1

    # Never establish trust using the same candidate version just built.
    # Prefer an independently signed previous release or pinned fingerprint.
    if ($null -eq $previous) {
        throw @"
Fingerprint Release non inizializzato.

Lascia in:
  dist\

una APK Release precedente sicuramente firmata
con la tua key, ad esempio:

  Lyra-1.0.7.apk

e ripeti la build.
"@
    }

    $expected =
        Get-SignerFingerprint $previous.FullName

    [System.IO.File]::WriteAllText(
        $trustedFile,
        $expected,
        [System.Text.UTF8Encoding]::new($false)
    )

    Write-Host `
        "Fingerprint inizializzato da $($previous.Name)" `
        -ForegroundColor Yellow
}

if (
    [string]::IsNullOrWhiteSpace($expected) -or
    $actual -ne $expected
) {
    throw "FIRMA APK NON VALIDA: certificato diverso dalla Release fidata."
}

Write-Host "Firma APK: OK" -ForegroundColor Green
Write-Host "Certificate SHA-256: $actual"

Write-Output $actual
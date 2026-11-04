# Genera un APK debug Android con la normale firma di sviluppo di Gradle.
# Nessuna chiave release o credenziale GitHub e' necessaria.
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$gradle = Join-Path $root 'gradlew.bat'
if (-not (Test-Path -LiteralPath $gradle)) { throw 'Gradle wrapper mancante.' }
Push-Location $root
try {
    & $gradle ':app:testDebugUnitTest' ':innertube:testDebugUnitTest' ':app:assembleDebug'
    if ($LASTEXITCODE -ne 0) { throw "Gradle fallito (exit $LASTEXITCODE)." }
    $apk = Join-Path $root 'app\build\outputs\apk\debug\app-debug.apk'
    if (-not (Test-Path -LiteralPath $apk -PathType Leaf)) {
        throw 'APK debug non trovato.'
    }
    Write-Host 'APK debug pronto:' -ForegroundColor Green
    Write-Host (Resolve-Path -LiteralPath $apk).Path
    Write-Host "Nota: la firma DEBUG automatica non aggiorna una APK RELEASE installata."
}
finally { Pop-Location }

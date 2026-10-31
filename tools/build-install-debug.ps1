param(
    [switch]$Install
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot

Push-Location $root
try {
    Write-Host "[Lyra] Build debug..."
    & .\gradlew.bat :app:assembleDebug
    if ($LASTEXITCODE -ne 0) {
        throw "Compilazione Debug fallita (exit code $LASTEXITCODE)."
    }

    $apk = Join-Path $root "app\build\outputs\apk\debug\app-debug.apk"
    if (-not (Test-Path $apk)) {
        throw "APK Debug non trovato: $apk"
    }
    $apk = (Resolve-Path $apk).Path
    Write-Host "[Lyra] APK Debug: $apk"

    if ($Install) {
        if (-not (Get-Command adb -ErrorAction SilentlyContinue)) {
            throw "ADB non disponibile. Installa Android Platform Tools e collega un dispositivo autorizzato."
        }
        Write-Host "[Lyra] Installazione ADB richiesta esplicitamente (-Install)..."
        & adb install -r $apk
        if ($LASTEXITCODE -ne 0) {
            throw "Installazione ADB fallita (exit code $LASTEXITCODE)."
        }
        Write-Host "[Lyra] Installazione completata."
    }
    else {
        Write-Host "[Lyra] Solo compilazione: nessuna installazione ADB."
    }
}
finally {
    Pop-Location
}

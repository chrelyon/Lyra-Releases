param(
    [string]$Version = "",
    [string]$OutputDirectory = ""
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
if ([string]::IsNullOrWhiteSpace($Version)) {
    $gradle = Get-Content (Join-Path $root "app/build.gradle.kts") -Raw
    $m = [regex]::Match($gradle, 'versionName\s*=\s*"([^"]+)"')
    if (-not $m.Success) { throw "Versione Lyra non identificata." }
    $Version = $m.Groups[1].Value
}
if ($Version -notmatch '^[A-Za-z0-9._-]+$') { throw "Versione non valida." }
if ([string]::IsNullOrWhiteSpace($OutputDirectory)) {
    $OutputDirectory = Join-Path $root "dist"
}
New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null
$archive = Join-Path $OutputDirectory "Lyra-$Version-sorgenti.zip"
$temp = Join-Path ([System.IO.Path]::GetTempPath()) ([System.Guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Force -Path $temp | Out-Null
try {
    $folders = @("app/src", "innertube/src", "tools", "gradle/wrapper")
    $rootFiles = @(
        "settings.gradle.kts", "build.gradle.kts", "gradle.properties",
        "gradlew", "gradlew.bat", "app/build.gradle.kts",
        "innertube/build.gradle.kts", "release-signing.sha256",
        ".gitignore", ".gitattributes",
        "README.md", "CHANGELOG.md", "RELEASE_NOTES_Lyra-1.0.0.md",
        "RELEASE_NOTES_Lyra-1.0.2-PREPARAZIONE.md",
        "GUIDA_RILASCIO_1.0.0.md"
    )
    $items = New-Object 'System.Collections.Generic.List[System.IO.FileInfo]'
    foreach ($relative in $folders) {
        $path = Join-Path $root ($relative.Replace('/', [System.IO.Path]::DirectorySeparatorChar))
        if (Test-Path $path) {
            Get-ChildItem -LiteralPath $path -Recurse -File | ForEach-Object { $items.Add($_) }
        }
    }
    foreach ($relative in $rootFiles) {
        $path = Join-Path $root ($relative.Replace('/', [System.IO.Path]::DirectorySeparatorChar))
        if (Test-Path -LiteralPath $path -PathType Leaf) {
            $items.Add((Get-Item -LiteralPath $path))
        }
    }
    $forbidden = '(?i)(?:^|[\\/])(?:\.git|\.gradle|\.kotlin|\.idea|build|dist|node_modules)(?:[\\/]|$)|(?:^|[\\/])(?:keystore\.properties|local\.properties|\.env(?:\..*)?|\.dev\.vars(?:\..*)?|[^\\/]+\.(?:jks|keystore|p12|pfx|pem|key|apk|aab))(?:$)'
    $count = 0
    foreach ($item in $items) {
        $relative = $item.FullName.Substring($root.Length).TrimStart('\','/')
        if ($relative -match $forbidden) { continue }
        $target = Join-Path $temp $relative
        $parent = Split-Path -Parent $target
        New-Item -ItemType Directory -Force -Path $parent | Out-Null
        Copy-Item -LiteralPath $item.FullName -Destination $target
        $count++
    }
    if ($count -lt 100) { throw "Solo $count file selezionati: pacchetto troppo piccolo, verificare." }
    if (Test-Path $archive) { Remove-Item -LiteralPath $archive -Force }
    Compress-Archive -Path (Join-Path $temp '*') -DestinationPath $archive -CompressionLevel Optimal
    Write-Host "Pacchetto sorgenti creato: $archive" -ForegroundColor Green
    Write-Host "File inclusi: $count - senza password, keystore, .git o compilati."
} finally {
    if (Test-Path $temp) { Remove-Item -LiteralPath $temp -Force -Recurse }
}

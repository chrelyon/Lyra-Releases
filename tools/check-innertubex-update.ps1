param(
    [switch]$IncludePrerelease,
    [switch]$FailOnUpdate
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$moduleGradle = Join-Path $root "innertube\build.gradle.kts"
$repository = "MetrolistGroup/innertubex"
$repoUrl = "https://github.com/$repository"

if (-not (Test-Path $moduleGradle)) {
    throw "File non trovato: $moduleGradle"
}

$gradleText = Get-Content $moduleGradle -Raw
$match = [regex]::Match(
    $gradleText,
    'innertubex:innertubex:([0-9A-Za-z._+-]+)'
)
if (-not $match.Success) {
    throw "Impossibile rilevare la versione InnerTubeX in innertube/build.gradle.kts"
}

$currentRaw = $match.Groups[1].Value.Trim()
$currentClean = $currentRaw.TrimStart('v')
$currentVersion = $null
if (-not [version]::TryParse($currentClean, [ref]$currentVersion)) {
    throw "Versione InnerTubeX locale non confrontabile: $currentRaw"
}

$headers = @{
    "Accept" = "application/vnd.github+json"
    "User-Agent" = "Lyra-InnerTubeX-Update-Checker"
    "X-GitHub-Api-Version" = "2022-11-28"
}

function Invoke-GitHubJson([string]$Uri) {
    Invoke-RestMethod -Uri $Uri -Headers $headers -Method Get
}

$latestTag = $null
$latestVersion = $null
$source = $null

try {
    $release = Invoke-GitHubJson "https://api.github.com/repos/$repository/releases/latest"
    if ($release -and $release.tag_name) {
        $candidate = [string]$release.tag_name
        $candidateClean = $candidate.TrimStart('v')
        $parsed = $null
        if ([version]::TryParse($candidateClean, [ref]$parsed)) {
            if ($IncludePrerelease -or -not $release.prerelease) {
                $latestTag = $candidate
                $latestVersion = $parsed
                $source = "GitHub latest release"
            }
        }
    }
} catch {
    Write-Verbose "Latest release non disponibile: $($_.Exception.Message)"
}

if ($null -eq $latestVersion) {
    $tags = Invoke-GitHubJson "https://api.github.com/repos/$repository/tags?per_page=100"

    $candidates = foreach ($tag in $tags) {
        $name = [string]$tag.name
        $clean = $name.TrimStart('v')
        if (-not $IncludePrerelease -and $clean -match '[-+]') { continue }
        $parsed = $null
        if ([version]::TryParse($clean, [ref]$parsed)) {
            [pscustomobject]@{ Name = $name; Version = $parsed }
        }
    }

    $best = $candidates | Sort-Object Version -Descending | Select-Object -First 1
    if ($best) {
        $latestTag = $best.Name
        $latestVersion = $best.Version
        $source = "GitHub tags"
    }
}

Write-Host ""
Write-Host "InnerTubeX - controllo aggiornamenti" -ForegroundColor Cyan
Write-Host "Repository : $repoUrl"
Write-Host "Installata  : $currentRaw"

if ($null -eq $latestVersion) {
    Write-Host "Disponibile : non determinabile" -ForegroundColor Yellow
    Write-Host "Nessuna release/tag SemVer rilevata. Controllare manualmente il repository."
    exit 0
}

Write-Host "Disponibile : $latestTag ($source)"

if ($latestVersion -gt $currentVersion) {
    Write-Host "STATO       : AGGIORNAMENTO DISPONIBILE" -ForegroundColor Yellow
    Write-Host ""
    Write-Host "Non aggiornare automaticamente InnerTubeX." -ForegroundColor Yellow
    Write-Host "Prima leggere changelog/tag, creare un branch dedicato, modificare la versione in:"
    Write-Host "  innertube/build.gradle.kts"
    Write-Host "Poi eseguire build e regressione: login Google, playlist, ricerca, playback,"
    Write-Host "NEXT/PREV, shuffle/repeat, lock screen, Bluetooth e Android Auto."
    Write-Host "Upstream: $repoUrl"
    if ($FailOnUpdate) { exit 2 }
} elseif ($latestVersion -eq $currentVersion) {
    Write-Host "STATO       : aggiornata" -ForegroundColor Green
} else {
    Write-Host "STATO       : la versione locale e' piu' recente del tag rilevato" -ForegroundColor DarkYellow
}

# Asks for your TMDB key and sends it to the Latest app on the TV, which then fetches the newest titles.
# The key is typed here, sent over adb and kept only on the TV; it is never written to this repo.
#
# Usage: powershell -ExecutionPolicy Bypass -File revamp\set-tmdb-key.ps1 -TvIp 192.168.1.50 [-Regions US,IN] [-Langs ta,te,hi,en]
#   -Regions  TMDB watch regions to look in (ISO country codes)
#   -Langs    original languages to rank first (ISO 639-1 codes)
param(
    [Parameter(Mandatory = $true)][string]$TvIp,
    [string]$Regions = "US",
    [string]$Langs = "en"
)
$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { "$env:LOCALAPPDATA\Android\Sdk" }
$adb = Join-Path $sdk "platform-tools\adb.exe"
$dev = "${TvIp}:5555"
& $adb connect $dev | Out-Null
$key = (Read-Host "Paste your TMDB API Key (or API Read Access Token) and press Enter").Trim()
if (-not $key) { Write-Host "No key entered; nothing changed."; exit 1 }
& $adb -s $dev shell am start -n com.tvlatest/.LatestActivity --es key $key --es regions $Regions --es langs $Langs
Write-Host "Sent. The TV should say 'Saved. Fetching from TMDB...'"

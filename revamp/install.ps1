# Builds and installs the revamped Home (launcher) and its companion app "Latest" on an Android TV / Google TV.
#
# Usage (PowerShell, from the repo folder):
#   powershell -ExecutionPolicy Bypass -File revamp\install.ps1 -TvIp 192.168.1.50
#   powershell -ExecutionPolicy Bypass -File revamp\install.ps1 -TvIp 192.168.1.50 -SkipBuild   # reinstall only
#
# Needs: adb (Android SDK platform-tools), JDK 17-21, Android SDK platform 34. See README.md.
param(
    [Parameter(Mandatory = $true)][string]$TvIp,
    [switch]$SkipBuild
)
$ErrorActionPreference = "Stop"
$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { "$env:LOCALAPPDATA\Android\Sdk" }
$adb = Join-Path $sdk "platform-tools\adb.exe"
if (-not (Test-Path $adb)) { throw "adb not found at $adb. Install Android SDK platform-tools or set ANDROID_HOME." }
$dev = "${TvIp}:5555"
$root = Split-Path $PSScriptRoot -Parent
$projects = @(
    @{ Name = "launcher"; Dir = Join-Path $root "launcher"; Pkg = "com.gathin.tvhome" },
    @{ Name = "companion"; Dir = Join-Path $root "companion"; Pkg = "com.tvlatest" }
)

function Build($p) {
    Push-Location $p.Dir
    try {
        if (-not (Test-Path local.properties)) {
            "sdk.dir=" + ($sdk -replace '\\', '/') | Set-Content -Encoding ascii local.properties
        }
        # Gradle sometimes fails once on a locked file; one retry clears it.
        & .\gradlew.bat assembleRelease -q --console=plain
        if ($LASTEXITCODE -ne 0) { & .\gradlew.bat assembleRelease -q --console=plain }
        if ($LASTEXITCODE -ne 0) { throw "Build of $($p.Name) failed" }
    } finally {
        Pop-Location
    }
}

function Install($p) {
    $apk = Join-Path $p.Dir "app\build\outputs\apk\release\app-release.apk"
    & $adb -s $dev push $apk /data/local/tmp/app.apk | Out-Null
    $out = & $adb -s $dev shell pm install -r /data/local/tmp/app.apk   # not `adb install`: it can hang on Android 14
    & $adb -s $dev shell rm -f /data/local/tmp/app.apk
    if ("$out" -notmatch "Success") { throw "Install of $($p.Name) failed: $out" }
    Write-Host "Installed $($p.Name)."
}

if (-not $SkipBuild) {
    if (-not $env:JAVA_HOME) {
        $jdk = Get-ChildItem "C:\Program Files\Eclipse Adoptium\jdk-2[01]*", "C:\Program Files\Eclipse Adoptium\jdk-17*" -ErrorAction SilentlyContinue |
            Select-Object -First 1
        if ($jdk) { $env:JAVA_HOME = $jdk.FullName }
    }
    foreach ($p in $projects) { Build $p }
}

& $adb connect $dev | Out-Null
if ((& $adb -s $dev get-state 2>$null) -ne "device") {
    throw "Can't reach the TV at $dev. Turn on Network debugging (or Wireless debugging) and accept the prompt on the TV."
}
foreach ($p in $projects) { Install $p }

# Continue watching reads Android's Watch Next list.
& $adb -s $dev shell pm grant com.tvlatest android.permission.READ_TV_LISTINGS
# Make the new Home the TV's Home.
& $adb -s $dev shell cmd package set-home-activity --user 0 com.gathin.tvhome/.MainActivity | Out-Null
# The Home button helper: an install unbinds it, so switch it off and on again.
& $adb -s $dev shell "settings put secure enabled_accessibility_services ''; settings put secure accessibility_enabled 0; sleep 1; settings put secure enabled_accessibility_services com.gathin.tvhome/.HomeKeyService; settings put secure accessibility_enabled 1"
& $adb -s $dev shell cmd package compile -m speed -f com.gathin.tvhome | Out-Null
& $adb -s $dev shell am start -n com.gathin.tvhome/.MainActivity | Out-Null
Write-Host ""
Write-Host "Done. Next: set your TMDB key with revamp\set-tmdb-key.ps1 -TvIp $TvIp"
Write-Host "Then press Home on the real remote to check the Home button."

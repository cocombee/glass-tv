# Windows version of build-and-install.sh: build the launcher APK and install it on the TV as the
# default home screen. Uses Android Studio's bundled JDK and the default SDK location unless
# JAVA_HOME / ANDROID_HOME are set.
#
# Usage: launcher\build-and-install.ps1 -TvHost 192.168.1.50 [-StockLauncher <package>|none]
#        The stock launcher is detected from the TV when -StockLauncher is not given.
#        [-InputKeyService]   remote's Input button opens the input switcher (Google TV); re-pass on every install
#        [-HomeKeyService]    remotes whose Home button sends KEYCODE_ALL_APPS (Xiaomi sticks)
#        [-KeepHighContrastText]  leave Android's High contrast text on (it is switched off otherwise)
param(
    [string]$TvHost = $env:TV_HOST,
    [string]$StockLauncher = '',
    [switch]$InputKeyService,
    [switch]$HomeKeyService,
    [switch]$KeepHighContrastText
)
$ErrorActionPreference = 'Stop'
# Pipe plain UTF-8 to native commands; the default here prepends a BOM that corrupts the first command.
$OutputEncoding = New-Object System.Text.UTF8Encoding $false
if (-not $TvHost) { throw 'pass -TvHost <tv ip> or set $env:TV_HOST' }
$env:TV_HOST = $TvHost
Set-Location $PSScriptRoot

if (-not $env:JAVA_HOME -or -not (Test-Path "$env:JAVA_HOME\bin\java.exe")) {
    $jbr = Get-ChildItem 'C:\Program Files\Android' -Directory -ErrorAction SilentlyContinue |
        ForEach-Object { Join-Path $_.FullName 'jbr' } | Where-Object { Test-Path "$_\bin\java.exe" } | Select-Object -First 1
    if (-not $jbr) { throw 'Set JAVA_HOME to a JDK 17+ (Android Studio ships one in its jbr folder)' }
    $env:JAVA_HOME = $jbr
}
$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { "$env:LOCALAPPDATA\Android\Sdk" }
if (-not (Test-Path local.properties)) {
    "sdk.dir=$($sdk -replace '\\', '\\')" | Out-File -Encoding ascii local.properties
}

.\gradlew.bat assembleRelease -q --console=plain
if ($LASTEXITCODE -ne 0) { throw 'Gradle build failed' }

$py = '..\tools\venv\Scripts\python.exe'
# Which app owns Home differs by build; ask the TV, but only accept a launcher we recognise (the answer
# can also be Android's chooser "android", the setup wizard, or another third-party launcher).
$knownStock = @('com.google.android.tvlauncher', 'com.google.android.apps.tv.launcherx', 'com.google.android.leanbacklauncher', 'com.mitv.tvhome')
# Refuse a TV the home screen cannot run on before anything changes, and say why.
$sdk = (& $py -I ..\tools\tv.py 'getprop ro.build.version.sdk' | Select-Object -Last 1)
if ($LASTEXITCODE -ne 0 -or -not $sdk) { throw 'could not reach the TV; check -TvHost and the debugging prompt on the TV. Nothing was changed.' }
if ([int]"$sdk".Trim() -lt 26) { throw "this TV runs Android API $("$sdk".Trim()); the Glass TV home screen needs Android 8 (API 26) or newer. Debloating still works (tools\disable.ps1). Nothing was changed." }
if ("$(& $py -I ..\tools\tv.py 'getprop ro.product.manufacturer' | Select-Object -Last 1)".Trim() -eq 'Amazon') {
    throw 'this is a Fire TV. Fire OS takes Home back from any other launcher, so the Glass TV home screen cannot stay. Debloating still works (tools\disable.ps1). Nothing was changed.'
}
# Where Home goes now, so a failed install can put it back exactly.
$origHome = "$(& $py -I ..\tools\tv.py 'cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME' | Select-Object -Last 1)".Trim()
if (-not $StockLauncher) {
    $homePkg = & $py -I ..\tools\tv.py 'cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME' | Select-Object -Last 1
    if ($LASTEXITCODE -ne 0 -or -not $homePkg) { throw 'could not ask the TV which launcher owns Home; check -TvHost and the debugging prompt, or pass -StockLauncher <package>' }
    $homePkg = "$homePkg".Trim().Split('/')[0]
    if ($knownStock -contains $homePkg) { $StockLauncher = $homePkg }
    elseif ($homePkg -eq 'com.gathin.tvhome') { $StockLauncher = 'none'; Write-Host 'Home already belongs to this launcher; nothing will be disabled.' }
    elseif ($homePkg -eq 'android') {
        # Android's chooser: launchers tie and no default is set (a restore followed by a re-applied list
        # ends here). Take the one known stock launcher among the HOME claimants, if there is exactly one.
        $claimants = (& $py -I ..\tools\tv.py 'cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME') -join "`n"
        $found = @($knownStock | Where-Object { $claimants -match ([regex]::Escape($_) + '/') })
        if ($found.Count -eq 1) { $StockLauncher = $found[0]; Write-Host "Home is Android's chooser; the stock launcher among the candidates is $StockLauncher." }
        else { throw "Home is Android's launcher chooser and $($found.Count) known stock launchers claim Home: pick a default on the TV first, or run again with -StockLauncher <package to disable> or -StockLauncher none." }
    }
    else { throw "Home currently resolves to '$homePkg', which is not a stock launcher this script knows (android = launcher chooser, com.google.android.tungsten.setupwraith = setup wizard, see README Troubleshooting; anything else = another launcher is the default). Run again with -StockLauncher <package to disable> or -StockLauncher none." }
}
Write-Host "Stock launcher: $StockLauncher"
$disableStock = if ($StockLauncher -eq 'none') { 'echo stock launcher left alone' } else { "pm disable-user --user 0 $StockLauncher" }

$apk = 'app\build\outputs\apk\release\app-release.apk'

# The safeguard, as in build-and-install.sh: from the install on, any failure puts the TV back as it was.
# $env:SAFEGUARD_TEST = 'start' or 'home' fails that check on purpose and prints the rollback instead.
function Restore-Tv {
    Write-Host 'Putting the TV back the way it was...'
    $restore = @()
    if ($StockLauncher -ne 'none') { $restore = @($StockLauncher) }
    else {
        $disabled = @(& $py -I ..\tools\tv.py 'pm list packages -d' | ForEach-Object { "$_".Trim() -replace '^package:', '' })
        $restore = @($knownStock | Where-Object { $disabled -contains $_ })
    }
    $cmds = @('pm uninstall com.gathin.tvhome') + ($restore | ForEach-Object { "pm enable --user 0 $_" })
    if ($origHome -match '/' -and $origHome -notlike 'com.gathin.tvhome/*') { $cmds += "cmd package set-home-activity --user 0 $origHome" }
    $cmds += 'input keyevent KEYCODE_HOME'
    if ($env:SAFEGUARD_TEST) { Write-Host "SAFEGUARD_TEST: would run on the TV:`n$($cmds -join "`n")"; return }
    try {
        $cmds | & $py -I ..\tools\tv.py --batch | Out-Null
        Write-Host "Done: Glass TV removed and the TV's own home screen is back $($restore -join ' ')."
    } catch {
        Write-Host "Could not reach the TV to undo it. Once it is back on the network, run tools\restore.ps1 with your debloat list $($restore -join ' ')."
    }
}
# Glass TV in front, and the same process a few seconds later (not crashing and restarting).
function Test-Healthy([string]$Stage) {
    if ($env:SAFEGUARD_TEST -eq $Stage) { return $false }
    # Sent on stdin: Windows PowerShell 5.1 drops the inner double quotes from native arguments (#4).
    $s = "$('sleep 4; a=$(pidof com.gathin.tvhome); sleep 3; b=$(pidof com.gathin.tvhome); f=$(dumpsys window windows 2>/dev/null | grep -m1 mCurrentFocus); echo "$a|$b|$f"' | & $py -I ..\tools\tv.py --batch | Select-Object -Last 1)".Trim()
    $p = $s.Split('|', 3)
    return ($p.Count -eq 3 -and $p[0] -and $p[0] -eq $p[1] -and $p[2] -like '*com.gathin.tvhome*')
}
& $py -I ..\tools\b64push.py $apk /data/local/tmp/tvhome.apk
if ($LASTEXITCODE -ne 0) { throw 'APK upload failed' }
$out = & $py -I ..\tools\tv.py 'pm install -r /data/local/tmp/tvhome.apk; rm -f /data/local/tmp/tvhome.apk'
Write-Host $out
if ("$out" -notmatch 'Success') { throw 'pm install failed; the stock launcher was left untouched' }
try {
    @(
        'pm grant com.gathin.tvhome android.permission.ACCESS_FINE_LOCATION 2>/dev/null; echo location-granted',
        'cmd package compile -m speed -f com.gathin.tvhome',
        "am start -W -n com.gathin.tvhome/.MainActivity | grep -E '^(Status|Error)'"
    ) | & $py -I ..\tools\tv.py --batch
    # Check it really runs on this TV before the TV's own launcher is touched.
    if (-not (Test-Healthy 'start')) { throw 'Glass TV did not start properly on this TV (it is not in front, or it keeps restarting).' }
    # Android 9 only accepts a component here; Android 10+ also takes a bare package name.
    @(
        $disableStock,
        'cmd package set-home-activity --user 0 com.gathin.tvhome/.MainActivity'
    ) | & $py -I ..\tools\tv.py --batch
    # Order matters, see build-and-install.sh: start the new launcher, disable the stock one, set the
    # preference last, then check where Home ended up.
    $homeNow = (& $py -I ..\tools\tv.py 'cmd package resolve-activity --brief -c android.intent.category.HOME -a android.intent.action.MAIN' | Select-Object -Last 1).Trim()
    if ($homeNow -notlike 'com.gathin.tvhome/*') {
        throw "Home resolves to '$homeNow', not to the new launcher. On Google TV this usually means com.google.android.tungsten.setupwraith also claims Home: add it to your debloat list (see README, Troubleshooting), apply it, then run this script again."
    }
    Write-Host "Home now resolves to $homeNow"
    # Press Home the way the remote does, and check Glass TV answers and stays up.
    & $py -I ..\tools\tv.py 'input keyevent KEYCODE_HOME' | Out-Null
    if (-not (Test-Healthy 'home')) { throw 'pressing Home did not bring up a working Glass TV.' }
} catch {
    Restore-Tv
    throw
}
# Optional accessibility services, appended to enabled_accessibility_services without dropping others.
function Enable-Service([string]$Name) {
    $svc = "com.gathin.tvhome/.$Name"
    # On stdin, like Test-Healthy: the quotes in this command would not survive PowerShell 5.1 as an argument.
    ('s=$(settings get secure enabled_accessibility_services); case "$s" in *' + $Name + '*) ;; null|"") settings put secure enabled_accessibility_services ' + $svc + ' ;; *) settings put secure enabled_accessibility_services "$s:' + $svc + '" ;; esac; settings put secure accessibility_enabled 1; echo ' + $Name + ' enabled') | & $py -I ..\tools\tv.py --batch | Select-Object -Last 1
}
if ($InputKeyService) { Enable-Service 'InputKeyService' }
if ($HomeKeyService) { Enable-Service 'HomeKeyService' }
# High contrast text outlines every letter in a dark border; over Glass TV's photos it reads as a drop shadow.
# Switched off here, and said so, unless -KeepHighContrastText. Settings > Accessibility turns it back on.
if (-not $KeepHighContrastText) {
    $hc = "$(& $py -I ..\tools\tv.py 'settings get secure high_text_contrast_enabled' | Select-Object -Last 1)".Trim()
    if ($hc -eq '1') {
        & $py -I ..\tools\tv.py 'settings put secure high_text_contrast_enabled 0' | Out-Null
        Write-Host 'High contrast text was on (it outlines all text in a dark border), so it was switched off. If you'
        Write-Host 'need it to read, switch it back on in Settings > Accessibility, or install with -KeepHighContrastText.'
    }
}
Write-Host 'Installed. Press Home on the remote to see it.'

#Requires -Version 5.1
$ErrorActionPreference = 'Continue'
. "$PSScriptRoot\hzzs-common.ps1"

Assert-HzzsAdbDevice | Out-Null

$dir = Join-Path $script:HzzsDiagnosticsRoot (Get-Date -Format 'yyyyMMdd-HHmmss')
New-Item -ItemType Directory -Force -Path $dir | Out-Null
$deviceInfo = Join-Path $dir 'device-info.txt'
$log = Join-Path $dir 'logcat.txt'
$png = Join-Path $dir 'screen.png'

function Write-Info([string]$Text) {
    Add-Content -LiteralPath $deviceInfo -Value $Text -Encoding utf8
}

Write-Info 'HZZS device diagnostics'
Write-Info ("time={0}" -f (Get-Date -Format o))
Write-Info ''
Write-Info '========== ADB devices =========='
adb devices -l | ForEach-Object { Write-Info $_ }
Write-Info ''

foreach ($flavor in @('debug', 'release')) {
    $pkg = Get-HzzsPackageId -Flavor $flavor
    Write-Info ("========== package {0} [{1}] ==========" -f $pkg, $flavor)
    adb shell pm path $pkg 2>&1 | ForEach-Object { Write-Info "$_" }
    adb shell pidof $pkg 2>&1 | ForEach-Object { Write-Info ("pidof: {0}" -f $_) }
    adb shell cmd package resolve-activity --brief -c android.intent.category.LAUNCHER $pkg 2>&1 | ForEach-Object { Write-Info "$_" }
    Write-Info ("component: {0}" -f (Get-HzzsComponent -PackageId $pkg))
    Write-Info ''
}

adb logcat -d -v time | Out-File -LiteralPath $log -Encoding utf8

adb shell screencap -p /sdcard/hzzs_screen.png 2>$null | Out-Null
if ($LASTEXITCODE -eq 0) {
    adb pull /sdcard/hzzs_screen.png $png 2>$null | Out-Null
    adb shell rm /sdcard/hzzs_screen.png 2>$null | Out-Null
}

Write-Host ("Diagnostics exported: {0}" -f $dir)

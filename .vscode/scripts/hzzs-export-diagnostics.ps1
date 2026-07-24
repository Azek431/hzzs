#Requires -Version 5.1
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\hzzs-common.ps1"

Assert-HzzsAdbDevice | Out-Null

$root = Get-HzzsDiagnosticsRoot
$dir = Join-Path $root (Get-Date -Format 'yyyyMMdd-HHmmss')
New-Item -ItemType Directory -Force -Path $dir | Out-Null
$deviceInfo = Join-Path $dir 'device-info.txt'
$log = Join-Path $dir 'logcat.txt'
$png = Join-Path $dir 'screen.png'

function Write-Info([string]$Text) {
    Add-Content -LiteralPath $deviceInfo -Value $Text -Encoding utf8
}

Write-Info 'HZZS device diagnostics'
Write-Info ("time={0}" -f (Get-Date -Format o))
Write-Info ("repo={0}" -f (Get-HzzsRepoRoot))
Write-Info ''
Write-Info '========== ADB devices =========='
$devices = @(Invoke-HzzsAdb -AdbArgs @('devices', '-l') -IgnoreFailure)
foreach ($line in $devices) { Write-Info $line }
Write-Info ''

foreach ($flavor in @('debug', 'release')) {
    $pkg = Get-HzzsPackageId -Flavor $flavor
    Write-Info ("========== package {0} [{1}] ==========" -f $pkg, $flavor)
    $pathLines = @(Invoke-HzzsAdb -AdbArgs @('shell', 'pm', 'path', $pkg) -IgnoreFailure)
    foreach ($line in $pathLines) { Write-Info $line }
    $pidLines = @(Invoke-HzzsAdb -AdbArgs @('shell', 'pidof', $pkg) -IgnoreFailure)
    foreach ($line in $pidLines) { Write-Info ("pidof: {0}" -f $line) }
    $resolve = @(Invoke-HzzsAdb -AdbArgs @(
            'shell', 'cmd', 'package', 'resolve-activity',
            '--brief', '-c', 'android.intent.category.LAUNCHER', $pkg
        ) -IgnoreFailure)
    foreach ($line in $resolve) { Write-Info $line }
    Write-Info ("component: {0}" -f (Get-HzzsComponent -PackageId $pkg))
    Write-Info ''
}

Write-Host 'dumping logcat ...'
$logLines = @(Invoke-HzzsAdb -AdbArgs @('logcat', '-d', '-v', 'time') -IgnoreFailure)
$logLines | Out-File -LiteralPath $log -Encoding utf8

Write-Host 'screencap ...'
Invoke-HzzsAdb -AdbArgs @('shell', 'screencap', '-p', '/sdcard/hzzs_screen.png') -IgnoreFailure | Out-Null
if ($script:HzzsLastAdbExitCode -eq 0) {
    Invoke-HzzsAdb -AdbArgs @('pull', '/sdcard/hzzs_screen.png', $png) -IgnoreFailure | Out-Null
    Invoke-HzzsAdb -AdbArgs @('shell', 'rm', '/sdcard/hzzs_screen.png') -IgnoreFailure | Out-Null
    if (Test-Path -LiteralPath $png) {
        Write-Host ("screenshot: {0}" -f $png)
    }
    else {
        Write-Host 'screenshot: pull failed (optional)'
    }
}
else {
    Write-Host 'screenshot: screencap failed (optional)'
}

Write-Host ("Diagnostics exported: {0}" -f $dir)
Write-Host '(default local-diagnostics/device; override with HZZS_DIAGNOSTICS_ROOT)'

#Requires -Version 5.1
$ErrorActionPreference = 'Continue'
. "$PSScriptRoot\hzzs-common.ps1"

Write-HzzsHeader 'ADB devices'
try {
    $count = Assert-HzzsAdbDevice -AllowMultiple
    $listed = @(Invoke-HzzsAdb -AdbArgs @('devices', '-l') -IgnoreFailure)
    foreach ($line in $listed) { Write-Host $line }
    Write-Host ("device count: {0}" -f $count)
    if ($env:ANDROID_SERIAL) {
        Write-Host ("ANDROID_SERIAL={0}" -f $env:ANDROID_SERIAL)
    }
}
catch {
    Write-Host $_.Exception.Message
    $listed = @(Invoke-HzzsAdb -AdbArgs @('devices', '-l') -IgnoreFailure)
    foreach ($line in $listed) { Write-Host $line }
}

function Show-Package {
    param([string]$Flavor)
    $pkg = Get-HzzsPackageId -Flavor $Flavor
    Write-HzzsHeader ("package {0} [{1}]" -f $pkg, $Flavor)
    if (-not (Test-HzzsPackageInstalled -PackageId $pkg)) {
        Write-Host 'not installed'
        return
    }
    $paths = @(Invoke-HzzsAdb -AdbArgs @('shell', 'pm', 'path', $pkg) -IgnoreFailure)
    foreach ($line in $paths) { Write-Host $line }
    $appPid = Get-HzzsPackagePid -PackageId $pkg
    if ($appPid) {
        Write-Host ("PID: {0}" -f $appPid)
    }
    else {
        Write-Host 'process: not running'
    }
    Write-Host 'LAUNCHER resolve:'
    $resolve = @(Invoke-HzzsAdb -AdbArgs @(
            'shell', 'cmd', 'package', 'resolve-activity',
            '--brief', '-c', 'android.intent.category.LAUNCHER', $pkg
        ) -IgnoreFailure)
    foreach ($line in $resolve) { Write-Host $line }
    Write-Host ("component: {0}" -f (Get-HzzsComponent -PackageId $pkg))
}

Show-Package -Flavor debug
Show-Package -Flavor release

Write-Host ''
Write-Host 'Daily VS Code tasks use Debug: top.azek431.hzzs.debug'
Write-Host 'Release store id:            top.azek431.hzzs'

# Status dump is best-effort; do not fail the VS Code task on last adb exit code.
exit 0

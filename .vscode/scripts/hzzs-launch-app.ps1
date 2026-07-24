#Requires -Version 5.1
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('debug', 'release')]
    [string]$Flavor,

    [string]$Activity
)

$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\hzzs-common.ps1"

if (-not $Activity) {
    $Activity = $script:HzzsMainActivity
}

$packageId = Get-HzzsPackageId -Flavor $Flavor
Assert-HzzsAdbDevice | Out-Null

if (-not (Test-HzzsPackageInstalled -PackageId $packageId)) {
    if ($Flavor -eq 'debug') {
        throw 'Debug package not installed (top.azek431.hzzs.debug). Run task: install Debug APK, or .\gradlew.bat :app:installDebug'
    }
    throw 'Release package not installed (top.azek431.hzzs). Install a signed Release APK first, or use Debug tasks.'
}

$component = '{0}/{1}' -f $packageId, $Activity
Write-Host ("Launching {0} ({1}) ..." -f $component, $Flavor)

$startOut = @(Invoke-HzzsAdb -AdbArgs @(
        'shell', 'am', 'start',
        '-n', $component,
        '-a', 'android.intent.action.MAIN',
        '-c', 'android.intent.category.LAUNCHER'
    ) -IgnoreFailure)
foreach ($line in $startOut) { Write-Host $line }

if ($script:HzzsLastAdbExitCode -eq 0) {
    Write-Host ("OK am start: {0}" -f $component)
    # Do not use exit: when invoked by jdwp-prepare, exit ends the parent process.
    return
}

Write-Host 'am start failed; falling back to monkey ...'
$monkeyOut = @(Invoke-HzzsAdb -AdbArgs @(
        'shell', 'monkey',
        '-p', $packageId,
        '-c', 'android.intent.category.LAUNCHER',
        '1'
    ) -IgnoreFailure)
foreach ($line in $monkeyOut) { Write-Host $line }

if ($script:HzzsLastAdbExitCode -ne 0) {
    throw ("Failed to launch {0}" -f $packageId)
}
Write-Host ("OK monkey: {0}" -f $packageId)

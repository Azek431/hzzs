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
        throw 'Debug package not installed (top.azek431.hzzs.debug). Run task: 安装 Debug APK  or  .\gradlew.bat :app:installDebug'
    }
    throw 'Release package not installed (top.azek431.hzzs). Install a signed Release APK first, or use Debug tasks for daily work.'
}

$component = '{0}/{1}' -f $packageId, $Activity
Write-Host ("Launching {0} ({1}) ..." -f $component, $Flavor)

adb shell am start -n $component -a android.intent.action.MAIN -c android.intent.category.LAUNCHER
if ($LASTEXITCODE -eq 0) {
    Write-Host ("OK am start: {0}" -f $component)
    exit 0
}

Write-Host 'am start failed; falling back to monkey ...'
adb shell monkey -p $packageId -c android.intent.category.LAUNCHER 1
if ($LASTEXITCODE -ne 0) {
    throw ("Failed to launch {0}" -f $packageId)
}
Write-Host ("OK monkey: {0}" -f $packageId)

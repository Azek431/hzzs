#Requires -Version 5.1
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('debug', 'release')]
    [string]$Flavor,

    [ValidateSet('force-stop', 'clear-data', 'uninstall')]
    [string]$Action = 'force-stop'
)

$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\hzzs-common.ps1"

$packageId = Get-HzzsPackageId -Flavor $Flavor
Assert-HzzsAdbDevice | Out-Null

if (-not (Test-HzzsPackageInstalled -PackageId $packageId)) {
    Write-Host ("Package not installed (skip {0}): {1}" -f $Action, $packageId)
    exit 0
}

switch ($Action) {
    'force-stop' {
        Write-Host ("force-stop {0}" -f $packageId)
        adb shell am force-stop $packageId
    }
    'clear-data' {
        Write-Host ("clear data {0} (permissions/settings wiped)" -f $packageId)
        adb shell pm clear $packageId
    }
    'uninstall' {
        Write-Host ("uninstall {0}" -f $packageId)
        adb uninstall $packageId
        if ($LASTEXITCODE -ne 0) {
            throw ("uninstall failed: {0}" -f $packageId)
        }
    }
}

if ($LASTEXITCODE -ne 0 -and $Action -ne 'uninstall') {
    throw ("{0} failed for {1}" -f $Action, $packageId)
}
Write-Host 'OK'

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
    return
}

switch ($Action) {
    'force-stop' {
        Write-Host ("force-stop {0}" -f $packageId)
        Invoke-HzzsAdb -AdbArgs @('shell', 'am', 'force-stop', $packageId) | Out-Null
    }
    'clear-data' {
        Write-Host ("clear data {0} (permissions/settings wiped)" -f $packageId)
        Invoke-HzzsAdb -AdbArgs @('shell', 'pm', 'clear', $packageId) | Out-Null
    }
    'uninstall' {
        Write-Host ("uninstall {0}" -f $packageId)
        try {
            Invoke-HzzsAdb -AdbArgs @('uninstall', $packageId) | Out-Null
        }
        catch {
            throw ("uninstall failed: {0}{1}{2}" -f $packageId, [Environment]::NewLine, $_.Exception.Message)
        }
    }
}

Write-Host 'OK'

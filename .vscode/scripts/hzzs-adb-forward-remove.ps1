#Requires -Version 5.1
# Remove a local adb forward without failing when none exists.
[CmdletBinding()]
param(
    [int]$Port = 0
)

$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\hzzs-common.ps1"

if ($Port -le 0) {
    $Port = $script:HzzsJdwpPort
}

Assert-HzzsAdbDevice | Out-Null
$spec = 'tcp:{0}' -f $Port
Write-Host ("adb forward --remove {0}" -f $spec)
Invoke-HzzsAdb -AdbArgs @('forward', '--remove', $spec) -IgnoreFailure | Out-Null
Write-Host 'OK (ignored if listener was not present)'

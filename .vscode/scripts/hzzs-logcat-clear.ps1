#Requires -Version 5.1
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\hzzs-common.ps1"

Assert-HzzsAdbDevice | Out-Null
Write-Host 'adb logcat -c'
Invoke-HzzsAdb -AdbArgs @('logcat', '-c') | Out-Null
Write-Host 'OK logcat cleared'

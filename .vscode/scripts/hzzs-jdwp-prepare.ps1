#Requires -Version 5.1
[CmdletBinding()]
param(
    [ValidateSet('debug')]
    [string]$Flavor = 'debug',

    [int]$JdwpPort = 0,

    [switch]$SkipInstall
)

$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\hzzs-common.ps1"

if ($JdwpPort -le 0) {
    $JdwpPort = $script:HzzsJdwpPort
}

$packageId = Get-HzzsPackageId -Flavor $Flavor
$repo = Get-HzzsRepoRoot
Set-Location $repo

Write-Host ("Repo: {0}" -f $repo)
Write-Host ("Package: {0}" -f $packageId)
Assert-HzzsAdbDevice | Out-Null

if (-not $SkipInstall) {
    Invoke-HzzsGradle -GradleArgs @('--console=plain', ':app:installDebug')
}

& "$PSScriptRoot\hzzs-launch-app.ps1" -Flavor $Flavor
if ($LASTEXITCODE -ne 0) {
    throw ("launch failed: {0}" -f $packageId)
}

Start-Sleep -Seconds 2

$appPid = Get-HzzsPackagePid -PackageId $packageId
if (-not $appPid) {
    throw ("process not found after launch: {0}" -f $packageId)
}

adb forward --remove ("tcp:{0}" -f $JdwpPort) 2>$null | Out-Null
adb forward ("tcp:{0}" -f $JdwpPort) ("jdwp:{0}" -f $appPid)
if ($LASTEXITCODE -ne 0) {
    throw 'JDWP forward failed'
}

Write-Host ("JDWP ready: localhost:{0} -> {1} / PID {2}" -f $JdwpPort, $packageId, $appPid)
Write-Host 'Attach VS Code Java debugger to localhost:5005 (launch config already does this).'

#Requires -Version 5.1
# installDebug with an early readable device gate.
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\hzzs-common.ps1"

$repo = Get-HzzsRepoRoot
Set-Location $repo

Assert-HzzsAdbDevice | Out-Null
Invoke-HzzsGradle -GradleArgs @('--console=plain', ':app:installDebug')
Write-Host 'OK installDebug'

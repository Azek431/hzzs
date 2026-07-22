#Requires -Version 5.1
# Run project quality gates (no Android device required).
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\hzzs-common.ps1"

$repo = Get-HzzsRepoRoot
Set-Location $repo

Write-HzzsHeader 'check_resources'
python tools/quality/check_resources.py
if ($LASTEXITCODE -ne 0) { throw 'check_resources failed' }

Write-HzzsHeader 'check_project'
python tools/quality/check_project.py
if ($LASTEXITCODE -ne 0) { throw 'check_project failed' }

Write-HzzsHeader 'compileall tools'
python -m compileall -q tools
if ($LASTEXITCODE -ne 0) { throw 'compileall failed' }

Write-Host ''
Write-Host 'Quality gates PASS'

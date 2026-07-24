#Requires -Version 5.1
# Run project quality gates (no Android device required).
$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\hzzs-common.ps1"

$repo = Get-HzzsRepoRoot
Set-Location $repo

Write-HzzsHeader 'check_resources'
Invoke-HzzsPython -PythonArgs @('tools/quality/check_resources.py') -FailureMessage 'check_resources failed'

Write-HzzsHeader 'check_project'
Invoke-HzzsPython -PythonArgs @('tools/quality/check_project.py') -FailureMessage 'check_project failed'

Write-HzzsHeader 'compileall tools'
Invoke-HzzsPython -PythonArgs @('-m', 'compileall', '-q', 'tools') -FailureMessage 'compileall tools failed'

Write-Host ''
Write-Host 'Quality gates PASS'

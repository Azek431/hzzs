#Requires -Version 5.1
[CmdletBinding()]
param(
    [ValidateSet('debug')]
    [string]$Flavor = 'debug',

    [int]$JdwpPort = 0,

    [switch]$SkipInstall,

    [int]$PidWaitSeconds = 15
)

$ErrorActionPreference = 'Stop'
. "$PSScriptRoot\hzzs-common.ps1"

if ($JdwpPort -le 0) {
    $JdwpPort = $script:HzzsJdwpPort
}
if ($PidWaitSeconds -lt 3) {
    $PidWaitSeconds = 3
}

$packageId = Get-HzzsPackageId -Flavor $Flavor
$repo = Get-HzzsRepoRoot
Set-Location $repo

Write-Host ("Repo: {0}" -f $repo)
Write-Host ("Package: {0}" -f $packageId)
Write-Host ("JDWP port: {0}" -f $JdwpPort)
Assert-HzzsAdbDevice | Out-Null

if (-not $SkipInstall) {
    Write-HzzsHeader 'installDebug'
    Invoke-HzzsGradle -GradleArgs @('--console=plain', ':app:installDebug')
}
else {
    if (-not (Test-HzzsPackageInstalled -PackageId $packageId)) {
        throw ("Debug package not installed: {0}. Run install task, or drop -SkipInstall." -f $packageId)
    }
}

Write-HzzsHeader 'launch'
# Nested script throws on failure; do NOT inspect $LASTEXITCODE under StrictMode after &.
try {
    & "$PSScriptRoot\hzzs-launch-app.ps1" -Flavor $Flavor
}
catch {
    throw ("launch failed: {0}{1}{2}" -f $packageId, [Environment]::NewLine, $_.Exception.Message)
}

Write-HzzsHeader 'wait pid'
$appPid = Wait-HzzsPackagePid -PackageId $packageId -TimeoutSeconds $PidWaitSeconds
if (-not $appPid) {
    throw ("process not found within {0}s after launch: {1}. App may have crashed; check logcat." -f $PidWaitSeconds, $packageId)
}
Write-Host ("PID: {0}" -f $appPid)

Write-HzzsHeader 'jdwp forward'
# --remove emits "listener not found" when nothing is forwarded yet; must IgnoreFailure under Stop.
Invoke-HzzsAdb -AdbArgs @('forward', '--remove', ("tcp:{0}" -f $JdwpPort)) -IgnoreFailure | Out-Null

try {
    Invoke-HzzsAdb -AdbArgs @('forward', ("tcp:{0}" -f $JdwpPort), ("jdwp:{0}" -f $appPid)) | Out-Null
}
catch {
    $list = @(Invoke-HzzsAdb -AdbArgs @('forward', '--list') -IgnoreFailure)
    Write-Host 'current adb forward --list:'
    if ($list.Count -eq 0) {
        Write-Host '  (empty)'
    }
    else {
        foreach ($line in $list) { Write-Host ("  {0}" -f $line) }
    }
    throw (
        "JDWP forward failed: localhost:{0} -> jdwp:{1} ({2}). " +
        "Tips: free port {0}; run adb forward --list; or -JdwpPort PORT (sync launch.json). " +
        "Cause: {3}"
    ) -f $JdwpPort, $appPid, $packageId, $_.Exception.Message
}

Write-Host ("JDWP ready: localhost:{0} -> {1} / PID {2}" -f $JdwpPort, $packageId, $appPid)
Write-Host ("Attach VS Code Java debugger to localhost:{0} (launch config does this)." -f $JdwpPort)

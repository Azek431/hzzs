#Requires -Version 5.1
$ErrorActionPreference = 'Continue'
. "$PSScriptRoot\hzzs-common.ps1"

Write-HzzsHeader 'ADB devices'
try {
    $count = Assert-HzzsAdbDevice
    adb devices -l
    Write-Host ("device count: {0}" -f $count)
}
catch {
    Write-Host $_.Exception.Message
    adb devices -l
}

function Show-Package {
    param([string]$Flavor)
    $pkg = Get-HzzsPackageId -Flavor $Flavor
    Write-HzzsHeader ("package {0} [{1}]" -f $pkg, $Flavor)
    if (-not (Test-HzzsPackageInstalled -PackageId $pkg)) {
        Write-Host 'not installed'
        return
    }
    adb shell pm path $pkg
    $appPid = Get-HzzsPackagePid -PackageId $pkg
    if ($appPid) {
        Write-Host ("PID: {0}" -f $appPid)
    }
    else {
        Write-Host 'process: not running'
    }
    Write-Host 'LAUNCHER resolve:'
    adb shell cmd package resolve-activity --brief -c android.intent.category.LAUNCHER $pkg
    Write-Host ("component: {0}" -f (Get-HzzsComponent -PackageId $pkg))
}

Show-Package -Flavor debug
Show-Package -Flavor release

Write-Host ''
Write-Host 'Daily VS Code tasks use Debug: top.azek431.hzzs.debug'
Write-Host 'Release store id:            top.azek431.hzzs'

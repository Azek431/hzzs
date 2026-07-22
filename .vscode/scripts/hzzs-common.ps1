#Requires -Version 5.1
# Shared constants and helpers for HZZS VS Code tasks.
# Dot-source only:  . "$PSScriptRoot\hzzs-common.ps1"

Set-StrictMode -Version Latest

$script:HzzsReleasePackageId = 'top.azek431.hzzs'
$script:HzzsDebugPackageId = 'top.azek431.hzzs.debug'
$script:HzzsMainActivity = 'top.azek431.hzzs.MainActivity'
$script:HzzsJdwpPort = 5005
$script:HzzsDiagnosticsRoot = 'D:\Azek431-Archives\hzzs-device-diagnostics'

function Get-HzzsRepoRoot {
    # .vscode/scripts -> repo root
    return (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
}

function Get-HzzsPackageId {
    param(
        [Parameter(Mandatory = $true)]
        [ValidateSet('debug', 'release')]
        [string]$Flavor
    )
    if ($Flavor -eq 'debug') { return $script:HzzsDebugPackageId }
    return $script:HzzsReleasePackageId
}

function Get-HzzsComponent {
    param(
        [Parameter(Mandatory = $true)]
        [string]$PackageId
    )
    return ('{0}/{1}' -f $PackageId, $script:HzzsMainActivity)
}

function Assert-HzzsAdbDevice {
    $null = Get-Command adb -ErrorAction Stop
    $lines = @(adb devices 2>&1 | Where-Object { $_ -match '\tdevice$' })
    if ($lines.Count -lt 1) {
        throw 'No Android device in "device" state. Connect USB debugging and authorize this PC.'
    }
    return $lines.Count
}

function Test-HzzsPackageInstalled {
    param([Parameter(Mandatory = $true)][string]$PackageId)
    $pathLines = @(adb shell pm path $PackageId 2>&1)
    $pathText = ($pathLines | Out-String)
    return ($LASTEXITCODE -eq 0 -and $pathText -match 'package:')
}

function Get-HzzsPackagePid {
    param([Parameter(Mandatory = $true)][string]$PackageId)
    $pidLines = @(adb shell pidof $PackageId 2>&1)
    $pidText = ($pidLines | Out-String).Trim()
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($pidText)) {
        return $null
    }
    if ($pidText -match 'error|Exception|not found') {
        return $null
    }
    return ($pidText -split '\s+')[0].Trim()
}

function Invoke-HzzsGradle {
    param(
        [Parameter(Mandatory = $true)]
        [string[]]$GradleArgs
    )
    $repo = Get-HzzsRepoRoot
    $wrapper = Join-Path $repo 'gradlew.bat'
    if (-not (Test-Path -LiteralPath $wrapper)) {
        throw ("gradlew.bat not found: {0}" -f $wrapper)
    }
    if (-not $env:CMAKE_BUILD_PARALLEL_LEVEL) {
        $env:CMAKE_BUILD_PARALLEL_LEVEL = '2'
    }
    Push-Location $repo
    try {
        Write-Host ("gradlew {0}" -f ($GradleArgs -join ' '))
        & $wrapper @GradleArgs
        if ($LASTEXITCODE -ne 0) {
            throw ("Gradle failed (exit {0}): {1}" -f $LASTEXITCODE, ($GradleArgs -join ' '))
        }
    }
    finally {
        Pop-Location
    }
}

function Write-HzzsHeader {
    param([string]$Title)
    Write-Host ''
    Write-Host ('========== {0} ==========' -f $Title)
}

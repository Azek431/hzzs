#Requires -Version 5.1
# Shared constants and helpers for HZZS VS Code tasks.
# Dot-source only:  . "$PSScriptRoot\hzzs-common.ps1"

Set-StrictMode -Version Latest

$script:HzzsReleasePackageId = 'top.azek431.hzzs'
$script:HzzsDebugPackageId = 'top.azek431.hzzs.debug'
$script:HzzsMainActivity = 'top.azek431.hzzs.MainActivity'
$script:HzzsJdwpPort = 5005
$script:HzzsLastAdbExitCode = 0

function Get-HzzsRepoRoot {
    # .vscode/scripts -> repo root
    return (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
}

function Get-HzzsDiagnosticsRoot {
    # Prefer env override; default under repo (gitignored: local-diagnostics).
    if ($env:HZZS_DIAGNOSTICS_ROOT -and -not [string]::IsNullOrWhiteSpace($env:HZZS_DIAGNOSTICS_ROOT)) {
        return $env:HZZS_DIAGNOSTICS_ROOT.Trim()
    }
    return (Join-Path (Get-HzzsRepoRoot) 'local-diagnostics\device')
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

function ConvertTo-HzzsTextLines {
    param([AllowNull()][object]$InputObject)
    if ($null -eq $InputObject) {
        return @()
    }
    $lines = New-Object System.Collections.Generic.List[string]
    foreach ($item in @($InputObject)) {
        if ($null -eq $item) { continue }
        if ($item -is [System.Management.Automation.ErrorRecord]) {
            $msg = $item.ToString()
            if ([string]::IsNullOrWhiteSpace($msg) -and $item.Exception) {
                $msg = $item.Exception.Message
            }
            if (-not [string]::IsNullOrWhiteSpace($msg)) {
                [void]$lines.Add($msg.TrimEnd())
            }
            continue
        }
        $text = "$item".TrimEnd()
        if (-not [string]::IsNullOrWhiteSpace($text)) {
            [void]$lines.Add($text)
        }
    }
    return @($lines)
}


function Get-HzzsNativeExitCode {
    # Under Set-StrictMode, $LASTEXITCODE may be unset before any native command.
    if (Get-Variable -Name LASTEXITCODE -Scope Global -ErrorAction SilentlyContinue) {
        $code = (Get-Variable -Name LASTEXITCODE -Scope Global).Value
        if ($null -eq $code) { return 0 }
        return [int]$code
    }
    return 0
}

function Invoke-HzzsAdb {
    <#
    .SYNOPSIS
      Run adb without letting native stderr become a terminating error under $ErrorActionPreference=Stop.
    #>
    param(
        [Parameter(Mandatory = $true)]
        [string[]]$AdbArgs,

        [switch]$IgnoreFailure,

        [int[]]$OkExitCodes = @(0)
    )
    if (-not (Get-Command adb -ErrorAction SilentlyContinue)) {
        throw 'adb not found in PATH. Install Android platform-tools and retry: adb devices'
    }

    $prevEap = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $global:LASTEXITCODE = 0
        $raw = & adb @AdbArgs 2>&1
        $code = Get-HzzsNativeExitCode
        $lines = ConvertTo-HzzsTextLines -InputObject $raw
        $script:HzzsLastAdbExitCode = $code
        if (-not $IgnoreFailure -and ($OkExitCodes -notcontains $code)) {
            $text = ($lines -join [Environment]::NewLine)
            if ([string]::IsNullOrWhiteSpace($text)) {
                $text = '(no adb output)'
            }
            throw ("adb {0} failed (exit {1}):{2}{3}" -f ($AdbArgs -join ' '), $code, [Environment]::NewLine, $text)
        }
        return $lines
    }
    finally {
        $ErrorActionPreference = $prevEap
    }
}

function Assert-HzzsAdbDevice {
    param(
        [switch]$AllowMultiple
    )

    if (-not (Get-Command adb -ErrorAction SilentlyContinue)) {
        throw 'adb not found in PATH. Install Android SDK platform-tools, then run: adb devices'
    }

    $raw = @(Invoke-HzzsAdb -AdbArgs @('devices') -IgnoreFailure)
    $ready = @($raw | Where-Object { $_ -match '\tdevice$' })
    if ($ready.Count -ge 1) {
        if ($ready.Count -gt 1 -and -not $AllowMultiple) {
            Write-Host ("NOTE: {0} devices in 'device' state; set ANDROID_SERIAL if adb may pick the wrong one." -f $ready.Count)
            Write-Host "  example: `$env:ANDROID_SERIAL = 'SERIAL'"
            foreach ($line in $ready) {
                Write-Host ("  - {0}" -f $line)
            }
        }
        return $ready.Count
    }

    $unauthorized = @($raw | Where-Object { $_ -match '\tunauthorized' })
    $offline = @($raw | Where-Object { $_ -match '\toffline' })
    $hints = New-Object System.Collections.Generic.List[string]
    if ($unauthorized.Count -gt 0) {
        [void]$hints.Add('Device is unauthorized: unlock phone, accept USB debugging, retry.')
    }
    if ($offline.Count -gt 0) {
        [void]$hints.Add('Device is offline: replug USB, or reconnect wireless adb (adb connect HOST:5555).')
    }
    if ($unauthorized.Count -eq 0 -and $offline.Count -eq 0) {
        [void]$hints.Add('No device listed. Enable USB debugging, plug USB, or: adb connect HOST:5555')
        [void]$hints.Add('State must be "device" (not empty/offline/unauthorized). Check: adb devices')
    }

    Write-Host 'adb devices output:'
    if ($raw.Count -eq 0) {
        Write-Host '  (empty)'
    }
    else {
        foreach ($line in $raw) {
            Write-Host ("  {0}" -f $line)
        }
    }
    $hintText = ($hints | ForEach-Object { "  - $_" }) -join [Environment]::NewLine
    throw ("No Android device in `"device`" state.{0}{1}" -f [Environment]::NewLine, $hintText)
}

function Test-HzzsPackageInstalled {
    param([Parameter(Mandatory = $true)][string]$PackageId)
    $pathLines = @(Invoke-HzzsAdb -AdbArgs @('shell', 'pm', 'path', $PackageId) -IgnoreFailure)
    $pathText = ($pathLines -join [Environment]::NewLine)
    return ($script:HzzsLastAdbExitCode -eq 0 -and $pathText -match 'package:')
}

function Get-HzzsPackagePid {
    param([Parameter(Mandatory = $true)][string]$PackageId)
    $pidLines = @(Invoke-HzzsAdb -AdbArgs @('shell', 'pidof', $PackageId) -IgnoreFailure)
    $pidText = ($pidLines -join ' ').Trim()
    if ($script:HzzsLastAdbExitCode -ne 0 -or [string]::IsNullOrWhiteSpace($pidText)) {
        return $null
    }
    if ($pidText -match 'error|Exception|not found') {
        return $null
    }
    return ($pidText -split '\s+')[0].Trim()
}

function Wait-HzzsPackagePid {
    param(
        [Parameter(Mandatory = $true)][string]$PackageId,
        [int]$TimeoutSeconds = 15,
        [int]$PollMilliseconds = 400
    )
    if ($TimeoutSeconds -lt 1) { $TimeoutSeconds = 1 }
    if ($PollMilliseconds -lt 100) { $PollMilliseconds = 100 }

    $deadline = [datetime]::UtcNow.AddSeconds($TimeoutSeconds)
    do {
        $appPid = Get-HzzsPackagePid -PackageId $PackageId
        if ($appPid) { return $appPid }
        Start-Sleep -Milliseconds $PollMilliseconds
    } while ([datetime]::UtcNow -lt $deadline)

    return $null
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
        $prevEap = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'
        try {
            $global:LASTEXITCODE = 0
            & $wrapper @GradleArgs
            $code = Get-HzzsNativeExitCode
        }
        finally {
            $ErrorActionPreference = $prevEap
        }
        if ($code -ne 0) {
            throw ("Gradle failed (exit {0}): {1}" -f $code, ($GradleArgs -join ' '))
        }
    }
    finally {
        Pop-Location
    }
}

function Invoke-HzzsPython {
    param(
        [Parameter(Mandatory = $true)]
        [string[]]$PythonArgs,

        [string]$FailureMessage = 'Python command failed'
    )
    $py = Get-Command python -ErrorAction SilentlyContinue
    if (-not $py) {
        $py = Get-Command py -ErrorAction SilentlyContinue
    }
    if (-not $py) {
        throw 'python (or py) not found in PATH. Install Python 3 and retry.'
    }

    $exe = $py.Source
    Write-Host ("{0} {1}" -f $exe, ($PythonArgs -join ' '))
    $prevEap = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $global:LASTEXITCODE = 0
        & $exe @PythonArgs
        $code = Get-HzzsNativeExitCode
    }
    finally {
        $ErrorActionPreference = $prevEap
    }
    if ($code -ne 0) {
        throw ("{0} (exit {1})" -f $FailureMessage, $code)
    }
}

function Write-HzzsHeader {
    param([string]$Title)
    Write-Host ''
    Write-Host ('========== {0} ==========' -f $Title)
}

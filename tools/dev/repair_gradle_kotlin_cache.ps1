#Requires -Version 5.1
<#
.SYNOPSIS
  修复本机 Gradle / Kotlin 增量编译损坏，并可选验证 compileDebugKotlin。

.DESCRIPTION
  典型症状：
  - kaptGenerateStubs* / compile*Kotlin 报 FileNotFoundException: *classpath-snapshot*.bin
  - Incremental compilation failed / Falling back to non-incremental
  - Gradle build daemon has been stopped: stop command received（IDE 停 daemon / OOM）
  - 本机可用内存极低时 Kotlin daemon 写半截快照

  本脚本：
  1. gradlew --stop
  2. 删除模块 Kotlin IC / KSP / kapt 残留与 .cxx（可选）
  3. 可选 --Compile 跑 :app:compileDebugKotlin

  不删除整个 app/build（保留资源等缓存）；需要全清请自行 clean。

.PARAMETER Compile
  清理后执行 :app:compileDebugKotlin

.PARAMETER AlsoCxx
  同时删除 app/.cxx（强制重配 CMake；native 改动后若配置脏可用）

.PARAMETER SkipStop
  不调用 gradlew --stop（已手动停过时）

.EXAMPLE
  .\tools\dev\repair_gradle_kotlin_cache.ps1 -Compile
#>
[CmdletBinding()]
param(
    [switch]$Compile,
    [switch]$AlsoCxx,
    [switch]$SkipStop
)

$ErrorActionPreference = 'Stop'
$RepoRoot = Resolve-Path (Join-Path $PSScriptRoot '..\..')
Set-Location $RepoRoot

function Write-Step([string]$Message) {
    Write-Host ""
    Write-Host "==> $Message" -ForegroundColor Cyan
}

function Remove-PathIfExists([string]$RelativePath) {
    $full = Join-Path $RepoRoot $RelativePath
    if (Test-Path -LiteralPath $full) {
        Write-Host "  remove $RelativePath"
        Remove-Item -LiteralPath $full -Recurse -Force -ErrorAction Stop
    }
    else {
        Write-Host "  skip   $RelativePath (missing)"
    }
}

Write-Host "HZZS Gradle/Kotlin IC repair"
Write-Host "Repo: $RepoRoot"

$os = Get-CimInstance Win32_OperatingSystem
$freeMb = [math]::Round($os.FreePhysicalMemory / 1KB, 0)
# 只打印空闲量，不打印本机总内存容量（避免把硬件画像写进日志/对话）
Write-Host ("Memory: free {0} MB" -f $freeMb)
if ($freeMb -lt 1500) {
    Write-Host "WARNING: free RAM is tight. Close IDE language servers / extra Claude / browser tabs before compile." -ForegroundColor Yellow
}

if (-not $SkipStop) {
    Write-Step "Stop Gradle daemons"
    $gradlew = Join-Path $RepoRoot 'gradlew.bat'
    if (Test-Path -LiteralPath $gradlew) {
        & $gradlew --stop
    }
    else {
        Write-Host "gradlew.bat not found; skip --stop" -ForegroundColor Yellow
    }
}

Write-Step "Remove Kotlin / KSP / legacy kapt incremental caches"
$paths = @(
    'app\build\kotlin',
    'app\build\kspCaches',
    'app\build\generated\ksp',
    'app\build\generated\source\ksp',
    'app\build\generated\source\kapt',
    'app\build\generated\source\kaptKotlin',
    'app\build\tmp\kapt3',
    'app\build\tmp\kspCaches',
    'app\build\tmp\kotlin-classes',
    '.kotlin'
)
foreach ($p in $paths) {
    Remove-PathIfExists $p
}

if ($AlsoCxx) {
    Write-Step "Remove CMake/.cxx"
    Remove-PathIfExists 'app\.cxx'
    Remove-PathIfExists 'app\build\intermediates\cxx'
    Remove-PathIfExists 'app\build\intermediates\cmake'
}

Write-Step "Done cleaning"
Write-Host "Next: keep CMAKE_BUILD_PARALLEL_LEVEL=2; prefer hzzs.native.abis=arm64-v8a in gradle.local.properties"
Write-Host "      低内存时加 --no-daemon，避免 IDE 误 stop 常驻 daemon："
Write-Host "      .\gradlew.bat --no-daemon --console=plain :app:compileDebugKotlin"
Write-Host "      # or :app:installDebug when free RAM is healthier"

if ($Compile) {
    Write-Step "Compile :app:compileDebugKotlin"
    if ($freeMb -lt 900) {
        Write-Host "Refuse auto-compile: free RAM too low ($freeMb MB). Free memory then re-run with -Compile." -ForegroundColor Red
        exit 2
    }
    $env:CMAKE_BUILD_PARALLEL_LEVEL = if ($env:CMAKE_BUILD_PARALLEL_LEVEL) { $env:CMAKE_BUILD_PARALLEL_LEVEL } else { '2' }
    # --no-daemon：避免 VS Code/其它终端对常驻 daemon 发 stop
    & (Join-Path $RepoRoot 'gradlew.bat') --no-daemon --console=plain :app:compileDebugKotlin
    exit $LASTEXITCODE
}

exit 0

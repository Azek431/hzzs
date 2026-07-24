# Build and run FastContourV2 host smokes (isolated from Android NDK / libhzzs_vision).
# Does not modify CMakeLists.txt or link into the APK.
# Usage:
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools/vision_v2/build_host_smoke.ps1
#   ... -Sanitize address
#   ... -Sanitize undefined
#   ... -Test all|core|boundary|pipeline
param(
    [ValidateSet("", "address", "undefined")]
    [string]$Sanitize = "",
    [ValidateSet("all", "core", "boundary", "pipeline")]
    [string]$Test = "all",
    [switch]$SkipRun
)

$ErrorActionPreference = "Stop"
$Root = Resolve-Path (Join-Path $PSScriptRoot "..\..")
$CppDir = Join-Path $Root "app\src\main\cpp\vision_v2"
$OutDir = Join-Path $Root "build\vision-v2-host"
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

$compilerCmd = $null
foreach ($name in @("clang++", "g++")) {
    $cmd = Get-Command $name -ErrorAction SilentlyContinue
    if ($cmd) {
        $compilerCmd = $cmd.Source
        break
    }
}
if (-not $compilerCmd) {
    throw "No clang++/g++ in PATH. Install LLVM-MinGW or MSYS2, then re-run."
}

$coreCpp = Join-Path $CppDir "fast_contour_core.cpp"
$pipelineCpp = Join-Path $CppDir "fast_contour_pipeline.cpp"
$coreTest = Join-Path $CppDir "fast_contour_core_test.cpp"
$boundaryTest = Join-Path $CppDir "fast_contour_core_boundary_test.cpp"
$pipelineTest = Join-Path $CppDir "fast_contour_pipeline_test.cpp"

$suffix = if ($Sanitize) { "_$Sanitize" } else { "" }
$common = @(
    "-std=c++20",
    "-Wall",
    "-Wextra",
    "-Werror",
    "-O1",
    "-g",
    "-I$CppDir"
)
if ($Sanitize -eq "address") {
    $common += @("-fsanitize=address")
} elseif ($Sanitize -eq "undefined") {
    $common += @("-fsanitize=undefined")
}

function Invoke-OneSmoke {
    param(
        [string]$Name,
        [string[]]$Sources
    )
    $exe = Join-Path $OutDir ("fast_contour_${Name}${suffix}.exe")
    $args = $common + $Sources + @("-o", $exe)
    Write-Host "+ $compilerCmd $($args -join ' ')"
    & $compilerCmd @args
    if ($LASTEXITCODE -ne 0) {
        throw "build failed for $Name : $LASTEXITCODE"
    }
    if (-not $SkipRun) {
        Write-Host "+ $exe"
        & $exe
        if ($LASTEXITCODE -ne 0) {
            throw "run failed for $Name : $LASTEXITCODE"
        }
    }
    Write-Host "PASS $Name$suffix -> $exe"
}

$ran = 0
if ($Test -eq "all" -or $Test -eq "core") {
    Invoke-OneSmoke -Name "core_test" -Sources @($coreCpp, $coreTest)
    $ran++
}
if ($Test -eq "all" -or $Test -eq "boundary") {
    Invoke-OneSmoke -Name "boundary_test" -Sources @($coreCpp, $boundaryTest)
    $ran++
}
if ($Test -eq "all" -or $Test -eq "pipeline") {
    Invoke-OneSmoke -Name "pipeline_test" -Sources @($coreCpp, $pipelineCpp, $pipelineTest)
    $ran++
}
if ($ran -eq 0) {
    throw "no tests selected"
}
Write-Host "All selected FastContourV2 host smokes passed."

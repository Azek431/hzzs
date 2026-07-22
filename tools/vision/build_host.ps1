# Build host shared library for offline batch recognition on Windows.
# Requires g++/clang++ in PATH (MSYS2 / LLVM / MinGW).
$ErrorActionPreference = "Stop"
$Root = Resolve-Path (Join-Path $PSScriptRoot "..\..")
$Cpp = Join-Path $Root "app\src\main\cpp"
$OutDir = Join-Path $Root "build\host"
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

$compiler = $null
foreach ($name in @("g++", "clang++")) {
    $cmd = Get-Command $name -ErrorAction SilentlyContinue
    if ($cmd) { $compiler = $cmd.Source; break }
}
if (-not $compiler) {
    throw "No g++/clang++ in PATH. Install MSYS2 mingw-w64 or LLVM, then re-run."
}

$sources = @(
    (Join-Path $Cpp "algorithm_runtime.cpp"),
    (Join-Path $Cpp "vision_engine.cpp"),
    (Join-Path $Cpp "sweet_factory.cpp"),
    (Join-Path $Cpp "bamboo_bookstore.cpp"),
    (Join-Path $Cpp "sea_salt_living_room.cpp"),
    (Join-Path $Cpp "legacy_main\vision2\HzzsVisionCore.cpp"),
    (Join-Path $Cpp "legacy_main\vision_bamboo\BambooVisionCore.cpp"),
    (Join-Path $Cpp "legacy_main\vision_bamboo\BambooVisionEngine.cpp"),
    (Join-Path $Root "tools\vision\host_api.cpp")
)

$outLib = Join-Path $OutDir "libhzzs_vision.dll"
$args = @(
    "-std=c++17", "-O3", "-DNDEBUG", "-shared",
    "-I$Cpp",
    "-I$(Join-Path $Cpp 'legacy_main\vision2')",
    "-I$(Join-Path $Cpp 'legacy_main\vision_bamboo')"
) + $sources + @("-o", $outLib)

Write-Host "+ $compiler $($args -join ' ')"
& $compiler @args
if ($LASTEXITCODE -ne 0) { throw "host build failed: $LASTEXITCODE" }
Write-Host $outLib

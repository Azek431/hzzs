#Requires -Version 5.1
<#
.SYNOPSIS
  清理本机 Serena / 多余 Kotlin LS 与缓存，恢复与 IDE 共存时的可用内存。

.DESCRIPTION
  典型症状：
  - Claude 会话里 Serena 工具迟迟不出现 / ToolSearch 为空
  - 日志 initialize 超时、cancelled (-32800)
  - 多端口 dashboard（24282+）与多个 serena 进程
  - Gradle「daemon has been stopped: stop command received」与 KLS 抢内存
  - 空闲内存 <1GB：多会话各起一套 start-mcp-server（serena.exe + python 包装）

  本脚本：
  1. 列出 serena / Serena Kotlin LS / VS Code fwcd KLS 进程（含 uv/python 包装）
  2. 默认结束全部 serena 及其子 Kotlin LS（Claude 会按需再起 1 个）
  3. 可选清理 .serena/cache/kotlin
  4. 打印空闲内存、dashboard 端口与残留实例数

  永久配置（勿回退）：
  - .serena/project.yml → kotlin jvm_options -Xmx768m；默认仅 languages: kotlin
  - ~/.serena/serena_config.yml → 全局 kotlin jvm 768m；web_dashboard false
  - .vscode/settings.json → kotlin.languageServer.enabled false（关 fwcd 双 KLS）
  - 同时只开 1 个本仓库 Claude Code 会话连 Serena，避免 MCP 多实例

.PARAMETER KeepRunning
  只诊断，不杀进程

.PARAMETER ClearCache
  删除仓库 .serena/cache/kotlin（及空 cache 子目录残留 pkl）

.PARAMETER AlsoStopFwcd
  同时结束 VS Code fwcd.kotlin 的 Language Server（编辑器补全会暂时不可用，重载窗口后由 JetBrains 扩展接管）

.EXAMPLE
  .\tools\dev\repair_serena.ps1 -ClearCache
#>
[CmdletBinding()]
param(
    [switch]$KeepRunning,
    [switch]$ClearCache,
    [switch]$AlsoStopFwcd
)

$ErrorActionPreference = 'Stop'
$RepoRoot = Resolve-Path (Join-Path $PSScriptRoot '..\..')

function Write-Step([string]$Message) {
    Write-Host ""
    Write-Host "==> $Message" -ForegroundColor Cyan
}

function Get-FreeMb {
    $os = Get-CimInstance Win32_OperatingSystem
    return [math]::Round($os.FreePhysicalMemory / 1024, 0)
}

function Get-SerenaRelated {
    $rows = @()
    Get-CimInstance Win32_Process -ErrorAction SilentlyContinue | ForEach-Object {
        $cmd = $_.CommandLine
        if (-not $cmd) { return }
        $hint = $null
        # 覆盖：serena.exe / uv 包装的 python ... serena.exe start-mcp-server / serena-agent
        # 注意：命令行常为 serena.exe（带 .exe），旧正则 serena\s+ 会漏掉 python 子进程。
        if (
            $_.Name -match '^(serena|serena\.exe)$' -or
            $cmd -match 'serena(\.exe)?(\s+|").*start-mcp-server' -or
            $cmd -match '[\\/]serena(\.exe)?["\s].*start-mcp-server' -or
            $cmd -match 'serena-agent' -or
            ($cmd -match 'solidlsp' -and $cmd -match 'mcp')
        ) {
            $hint = 'serena-mcp'
        }
        elseif (
            $cmd -match '\\.serena\\language_servers\\' -or
            $cmd -match 'language_servers.*[Kk]otlin' -or
            $cmd -match 'kotlin-lsp' -or
            ($cmd -match 'kotlin-language-server' -and $cmd -match '\.serena')
        ) {
            $hint = 'serena-kotlin-ls'
        }
        elseif ($cmd -match 'fwcd\.kotlin|kotlinLanguageServer\.version|langServerInstall\\server') {
            $hint = 'vscode-fwcd-kls'
        }
        elseif ($cmd -match 'KotlinLspServer|jetbrains\.kotlin') {
            $hint = 'jetbrains-or-other-kls'
        }
        if ($hint) {
            $rows += [PSCustomObject]@{
                Pid    = $_.ProcessId
                Name   = $_.Name
                Hint   = $hint
                WS_MB  = [math]::Round($_.WorkingSetSize / 1MB, 1)
                Cmd    = if ($cmd.Length -gt 140) { $cmd.Substring(0, 140) + '...' } else { $cmd }
            }
        }
    }
    return $rows | Sort-Object Hint, WS_MB -Descending
}

Write-Host "HZZS Serena repair"
Write-Host "Repo: $RepoRoot"
Write-Host "Free RAM before: $(Get-FreeMb) MiB"

Write-Step "Related processes"
$procs = @(Get-SerenaRelated)
if (-not $procs) {
    Write-Host "  (none)"
}
else {
    $procs | Format-Table Pid, Hint, WS_MB, Name -AutoSize | Out-String | Write-Host
}

Write-Step "Dashboard ports 24280-24300"
Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue |
    Where-Object { $_.LocalPort -ge 24280 -and $_.LocalPort -le 24300 } |
    Select-Object LocalAddress, LocalPort, OwningProcess |
    Format-Table -AutoSize | Out-String | Write-Host

if ($KeepRunning) {
    Write-Host "KeepRunning set — no processes killed."
}
else {
    Write-Step "Stop serena MCP + Serena Kotlin LS"
    $killHints = @('serena-mcp', 'serena-kotlin-ls')
    if ($AlsoStopFwcd) { $killHints += 'vscode-fwcd-kls' }
    $targets = @($procs | Where-Object { $killHints -contains $_.Hint })
    # 先子后父：按 WS 升序杀，减少「父还活着又拉子」的瞬时抖动
    $targets = $targets | Sort-Object WS_MB
    $stopped = 0
    foreach ($t in $targets) {
        try {
            Stop-Process -Id $t.Pid -Force -ErrorAction Stop
            Write-Host "  stopped pid=$($t.Pid) $($t.Hint) ~$($t.WS_MB)MB"
            $stopped++
        }
        catch {
            Write-Host "  skip pid=$($t.Pid): $($_.Exception.Message)"
        }
    }
    if ($stopped -eq 0) {
        Write-Host "  nothing to stop"
    }
    else {
        Write-Host "  stopped $stopped process(es)"
        Start-Sleep -Seconds 1
        # 二次清扫：Stop 后残留的 python 包装
        $again = @(Get-SerenaRelated | Where-Object { $killHints -contains $_.Hint })
        foreach ($t in $again) {
            try {
                Stop-Process -Id $t.Pid -Force -ErrorAction Stop
                Write-Host "  re-stopped pid=$($t.Pid) $($t.Hint)"
            }
            catch { }
        }
    }
}

if ($ClearCache) {
    Write-Step "Clear .serena/cache/kotlin"
    $cache = Join-Path $RepoRoot '.serena\cache\kotlin'
    if (Test-Path -LiteralPath $cache) {
        Remove-Item -LiteralPath $cache -Recurse -Force
        Write-Host "  removed $cache"
    }
    else {
        Write-Host "  skip (missing)"
    }
}

Write-Step "Config reminders"
$projectYml = Join-Path $RepoRoot '.serena\project.yml'
if (Test-Path $projectYml) {
    $text = Get-Content -LiteralPath $projectYml -Raw
    if ($text -match 'Xmx768m') {
        Write-Host "  OK project.yml kotlin jvm_options includes -Xmx768m"
    }
    else {
        Write-Host "  WARN project.yml missing -Xmx768m in ls_specific_settings.kotlin" -ForegroundColor Yellow
    }
    if ($text -match '(?m)^\s*-\s*cpp\s*$') {
        Write-Host "  NOTE languages still includes cpp (extra LS memory)" -ForegroundColor Yellow
    }
}
Write-Host "  Global: $env:USERPROFILE\.serena\serena_config.yml (kotlin jvm + web_dashboard)"
Write-Host "  Claude MCP: 全局 serena 用 --project-from-cwd；多会话会各起一套，尽量只开 1 个本仓库会话"
Write-Host "  After Claude restarts MCP: keep enough free RAM for Gradle (close extra KLS if tight)"

Write-Step "Post-check"
$left = @(Get-SerenaRelated)
$mcpLeft = @($left | Where-Object { $_.Hint -eq 'serena-mcp' -or $_.Hint -eq 'serena-kotlin-ls' }).Count
Write-Host "  remaining serena-mcp/kls rows: $mcpLeft"
if ($mcpLeft -gt 2) {
    Write-Host "  WARN still many instances — close extra Claude sessions then re-run this script" -ForegroundColor Yellow
}

Write-Host ""
Write-Host "Free RAM after: $(Get-FreeMb) MiB"
Write-Host "Done. Reload Claude MCP (or reopen session) so a single Serena reconnects."

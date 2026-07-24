#Requires -Version 5.1
<#
.SYNOPSIS
  Prepare GitHub Actions Secrets materials for HZZS app auto-update (not algorithm).

.DESCRIPTION
  Reads gitignored keystore.properties (or -StoreFile) and writes ANDROID_KEYSTORE_BASE64
  under build/release-secrets/. Prints a copy-paste checklist for GitHub Secrets.
  Optional -ApplyWithGh uploads ANDROID_* secrets via GitHub CLI (never prints secret values).

  Examples:
    cd <repo-root>
    powershell -NoProfile -File tools/release/setup_github_app_update_secrets.ps1
    powershell -NoProfile -File tools/release/setup_github_app_update_secrets.ps1 -ApplyWithGh -CopyBase64ToClipboard

  Security: do not paste base64/passwords into chat or public issues; only GitHub Secrets UI or local gh.
#>
[CmdletBinding()]
param(
    [string] $RepoRoot = "",
    [string] $PropertiesPath = "",
    [string] $StoreFile = "",
    [switch] $ApplyWithGh,
    [string] $GitHubOwner = "Azek431",
    [string] $GitHubRepo = "hzzs",
    [switch] $CopyBase64ToClipboard
)

$ErrorActionPreference = "Stop"
if (-not $RepoRoot) {
    $RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "../..")).Path
}
Set-Location $RepoRoot

function Read-Props([string] $path) {
    $map = @{}
    if (-not (Test-Path -LiteralPath $path)) { return $map }
    Get-Content -LiteralPath $path -Encoding UTF8 | ForEach-Object {
        $line = $_.Trim()
        if (-not $line -or $line.StartsWith("#")) { return }
        $i = $line.IndexOf("=")
        if ($i -lt 1) { return }
        $k = $line.Substring(0, $i).Trim()
        $v = $line.Substring($i + 1).Trim()
        $map[$k] = $v
    }
    return $map
}

if (-not $PropertiesPath) {
    foreach ($name in @("keystore.properties", "signing.properties", "local.secrets.properties")) {
        $candidate = Join-Path $RepoRoot $name
        if (Test-Path -LiteralPath $candidate) {
            $PropertiesPath = $candidate
            break
        }
    }
}

$props = @{}
if ($PropertiesPath -and (Test-Path -LiteralPath $PropertiesPath)) {
    $props = Read-Props $PropertiesPath
    Write-Host "Loaded properties: $PropertiesPath"
} else {
    Write-Host "No keystore.properties found. Pass -StoreFile or copy keystore.properties.example"
}

if (-not $StoreFile) {
    $StoreFile = $props["storeFile"]
    if (-not $StoreFile) { $StoreFile = $props["store.file"] }
    if (-not $StoreFile) { $StoreFile = $props["keystore.path"] }
}
if (-not $StoreFile) {
    throw "Missing keystore path. Set storeFile in keystore.properties or pass -StoreFile"
}
if (-not (Test-Path -LiteralPath $StoreFile)) {
    throw "Keystore file not found: $StoreFile"
}

$alias = $props["keyAlias"]
if (-not $alias) { $alias = $props["key.alias"] }
$storePassword = $props["storePassword"]
if (-not $storePassword) { $storePassword = $props["store.password"] }
$keyPassword = $props["keyPassword"]
if (-not $keyPassword) { $keyPassword = $props["key.password"] }

$bytes = [System.IO.File]::ReadAllBytes((Resolve-Path -LiteralPath $StoreFile))
$b64 = [Convert]::ToBase64String($bytes)
$outDir = Join-Path $RepoRoot "build/release-secrets"
New-Item -ItemType Directory -Force -Path $outDir | Out-Null
$b64Path = Join-Path $outDir "ANDROID_KEYSTORE_BASE64.txt"
[System.IO.File]::WriteAllText($b64Path, $b64)

$secretsUrl = "https://github.com/$GitHubOwner/$GitHubRepo/settings/secrets/actions"
$giteeTokenUrl = "https://gitee.com/profile/personal_access_tokens"

Write-Host ""
Write-Host "========== 1) Copy into GitHub Secrets (App update only) =========="
Write-Host "Open: $secretsUrl"
Write-Host ""
Write-Host "[1/5] Name: ANDROID_KEYSTORE_BASE64"
Write-Host "      Value file (copy ALL text): $b64Path"
Write-Host "      Length: $($b64.Length) chars"
if ($CopyBase64ToClipboard) {
    Set-Clipboard -Value $b64
    Write-Host "      Copied base64 to clipboard."
}
Write-Host ""
Write-Host "[2/5] Name: ANDROID_KEY_ALIAS"
if ($alias) {
    Write-Host "      Value: loaded from properties (len=$($alias.Length)). Paste from keystore.properties keyAlias only on GitHub."
} else {
    Write-Host "      Value: your keyAlias from keystore.properties"
}
Write-Host ""
Write-Host "[3/5] Name: ANDROID_STORE_PASSWORD"
if ($storePassword) {
    Write-Host "      Value: loaded from properties (len=$($storePassword.Length)). Paste only on GitHub. Do NOT send to chat."
} else {
    Write-Host "      Value: your storePassword from keystore.properties"
}
Write-Host ""
Write-Host "[4/5] Name: ANDROID_KEY_PASSWORD"
if ($keyPassword) {
    Write-Host "      Value: loaded from properties (len=$($keyPassword.Length)). Paste only on GitHub. Do NOT send to chat."
} else {
    Write-Host "      Value: your keyPassword from keystore.properties"
}
Write-Host ""
Write-Host "[5/5] Name: GITEE_TOKEN"
Write-Host "      Value: Gitee personal access token with project write scope."
Write-Host "      Create: $giteeTokenUrl"
Write-Host "      Required by current .github/workflows/release.yml dual-publish path."
Write-Host ""
Write-Host "========== 2) Optional: gh secret set =========="

$gh = Get-Command gh -ErrorAction SilentlyContinue
if ($ApplyWithGh) {
    if (-not $gh) {
        throw "gh not found. Install https://cli.github.com/ then: gh auth login"
    }
    if (-not $alias -or -not $storePassword -or -not $keyPassword) {
        throw "ApplyWithGh needs keyAlias/storePassword/keyPassword in keystore.properties"
    }
    $repoSlug = "$GitHubOwner/$GitHubRepo"
    Write-Host "Writing ANDROID_* secrets to $repoSlug via gh (values not printed)..."
    $b64 | & gh secret set ANDROID_KEYSTORE_BASE64 --repo $repoSlug
    $alias | & gh secret set ANDROID_KEY_ALIAS --repo $repoSlug
    $storePassword | & gh secret set ANDROID_STORE_PASSWORD --repo $repoSlug
    $keyPassword | & gh secret set ANDROID_KEY_PASSWORD --repo $repoSlug
    Write-Host "ANDROID_* done. Set GITEE_TOKEN separately:"
    Write-Host "  gh secret set GITEE_TOKEN --repo $repoSlug"
} elseif ($gh) {
    Write-Host "gh found. Re-run with -ApplyWithGh, or:"
    Write-Host "  Get-Content -LiteralPath '$b64Path' -Raw | gh secret set ANDROID_KEYSTORE_BASE64 --repo $GitHubOwner/$GitHubRepo"
} else {
    Write-Host "gh not installed: paste secrets on the web UI only."
    Write-Host "Optional install: winget install GitHub.cli"
}

Write-Host ""
Write-Host "========== 3) First release (creates online update files) =========="
Write-Host "1. Push main when ready; version/CHANGELOG ok"
Write-Host "2. GitHub Releases: Draft a new release"
Write-Host "   Tag example: v0.1.0   (or v0.1.0-beta.1 for beta channel)"
Write-Host "   Click Publish release  (triggers .github/workflows/release.yml)"
Write-Host "3. Watch Actions: Publish GitHub and Gitee release = green"
Write-Host "4. Install APK signed with THE SAME release keystore on device"
Write-Host "5. Settings: keep app autoCheck ON (default)"
Write-Host ""
Write-Host "Done. Do not commit build/release-secrets/."

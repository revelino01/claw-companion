# AI Companion Skill Installer for Windows (PowerShell / CMD)
# One-line installer for AI harnesses: Antigravity, OpenClaw, Hermes Agent, and Pi Agent.
#
# Usage (PowerShell):
#   irm https://raw.githubusercontent.com/revelino01/claw-companion/main/install.ps1 | iex
#
# Usage (CMD):
#   powershell -NoProfile -ExecutionPolicy Bypass -Command "irm https://raw.githubusercontent.com/revelino01/claw-companion/main/install.ps1 | iex"

[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12 -bor [Net.SecurityProtocolType]::Tls13

$ErrorActionPreference = "Stop"

$RepoRaw = "https://raw.githubusercontent.com/revelino01/claw-companion/main"
$TempDir = Join-Path ([System.IO.Path]::GetTempPath()) ("ai-companion-" + [System.Guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Path $TempDir -Force | Out-Null

Write-Host "==========================================" -ForegroundColor Cyan
Write-Host "  🤖 Installing AI Companion Skill        " -ForegroundColor Cyan
Write-Host "==========================================" -ForegroundColor Cyan

try {
    # 1. Fetch Skill Files
    Write-Host "[1/4] Fetching skill files..." -ForegroundColor Yellow

    $LocalSkillDir = Join-Path $PSScriptRoot "skills\ai-companion"
    if (Test-Path (Join-Path $LocalSkillDir "SKILL.md")) {
        Write-Host "  -> Using local repository files..." -ForegroundColor DarkGray
        Copy-Item -Path "$LocalSkillDir\*" -Destination $TempDir -Recurse -Force
    } else {
        Write-Host "  -> Downloading from GitHub repository..." -ForegroundColor DarkGray
        New-Item -ItemType Directory -Path "$TempDir\scripts" -Force | Out-Null
        New-Item -ItemType Directory -Path "$TempDir\references" -Force | Out-Null

        Invoke-WebRequest -Uri "$RepoRaw/skills/ai-companion/SKILL.md" -OutFile "$TempDir\SKILL.md" -UseBasicParsing
        Invoke-WebRequest -Uri "$RepoRaw/skills/ai-companion/references/api_reference.md" -OutFile "$TempDir\references\api_reference.md" -UseBasicParsing
        Invoke-WebRequest -Uri "$RepoRaw/skills/ai-companion/scripts/companion.py" -OutFile "$TempDir\scripts\companion.py" -UseBasicParsing
        Invoke-WebRequest -Uri "$RepoRaw/skills/ai-companion/scripts/companion.cmd" -OutFile "$TempDir\scripts\companion.cmd" -UseBasicParsing
    }

    # 2. Define Target Harness Locations
    Write-Host "[2/4] Configuring AI harnesses..." -ForegroundColor Yellow

    $UserProfile = $env:USERPROFILE
    $Targets = @(
        @{ Name = "Antigravity (Global)";  Path = Join-Path $UserProfile ".gemini\antigravity-cli\skills\ai-companion" },
        @{ Name = "Antigravity (.agents)"; Path = Join-Path $UserProfile ".agents\skills\ai-companion" },
        @{ Name = "OpenClaw";             Path = Join-Path $UserProfile ".openclaw\skills\ai-companion" },
        @{ Name = "Hermes Agent";         Path = Join-Path $UserProfile ".hermes\skills\ai-companion" },
        @{ Name = "Pi Agent";             Path = Join-Path $UserProfile ".pi\skills\ai-companion" }
    )

    # 3. Deploy Skill Files
    Write-Host "[3/4] Deploying skill to harnesses..." -ForegroundColor Yellow
    foreach ($target in $Targets) {
        $dest = $target.Path
        New-Item -ItemType Directory -Path $dest -Force | Out-Null
        Copy-Item -Path "$TempDir\*" -Destination $dest -Recurse -Force
        Write-Host "  ✓ $($target.Name) -> $dest" -ForegroundColor Green
    }

    # 4. Global CLI Setup (companion.cmd)
    Write-Host "[4/4] Setting up global 'companion' CLI command..." -ForegroundColor Yellow
    $CliInstalled = $false

    # WindowsApps is included in Windows PATH by default
    $WinAppsDir = Join-Path $env:LOCALAPPDATA "Microsoft\WindowsApps"
    if (Test-Path $WinAppsDir) {
        Copy-Item -Path "$TempDir\scripts\companion.cmd" -Destination (Join-Path $WinAppsDir "companion.cmd") -Force
        Copy-Item -Path "$TempDir\scripts\companion.py" -Destination (Join-Path $WinAppsDir "companion.py") -Force
        Write-Host "  ✓ Installed CLI to $WinAppsDir\companion.cmd" -ForegroundColor Green
        $CliInstalled = $true
    }

    $LocalBin = Join-Path $UserProfile ".local\bin"
    New-Item -ItemType Directory -Path $LocalBin -Force | Out-Null
    Copy-Item -Path "$TempDir\scripts\companion.cmd" -Destination (Join-Path $LocalBin "companion.cmd") -Force
    Copy-Item -Path "$TempDir\scripts\companion.py" -Destination (Join-Path $LocalBin "companion.py") -Force

    # Ensure $LocalBin is in User PATH if not already
    $UserPath = [Environment]::GetEnvironmentVariable("PATH", "User")
    if ($UserPath -notlike "*$LocalBin*") {
        [Environment]::SetEnvironmentVariable("PATH", "$UserPath;$LocalBin", "User")
    }

    Write-Host ""
    Write-Host "==========================================" -ForegroundColor Cyan
    Write-Host "  🎉 Installation Complete!               " -ForegroundColor Cyan
    Write-Host "==========================================" -ForegroundColor Cyan
    Write-Host ""
    Write-Host "Supported harnesses configured:"
    Write-Host "  • Antigravity  (~/.gemini/antigravity-cli/skills & ~/.agents/skills)"
    Write-Host "  • OpenClaw     (~/.openclaw/skills)"
    Write-Host "  • Hermes Agent (~/.hermes/skills)"
    Write-Host "  • Pi Agent     (~/.pi/skills)"
    Write-Host ""
    Write-Host "Quick Test in CMD or PowerShell:" -ForegroundColor Yellow
    Write-Host "  companion health"
    Write-Host "  companion ui true"
    Write-Host ""

} finally {
    if (Test-Path $TempDir) {
        Remove-Item -Path $TempDir -Recurse -Force -ErrorAction SilentlyContinue
    }
}

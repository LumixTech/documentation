# =====================================================================
# Lumix akilli commit (Windows / PowerShell)
# Build kapisi + Conventional Commit (Turkce). Hook'lar formati
# dogrular ve ClickUp footer'ini ekler.
#
# Kullanim:
#   pwsh scripts/commit.ps1 feat auth "kullanici giris akisi eklendi"
#   pwsh scripts/commit.ps1 fix - "iade tutari yanlis hesaplaniyordu"   (kapsamsiz icin -)
#   pwsh scripts/commit.ps1 -NoBuild docs - "README guncellendi"
# =====================================================================
param(
  [switch]$NoBuild,
  [Parameter(Mandatory=$true)][string]$Type,
  [Parameter(Mandatory=$true)][string]$Scope,    # kapsamsiz icin "-"
  [Parameter(Mandatory=$true)][string]$Subject
)
$ErrorActionPreference = 'Stop'
$root = git rev-parse --show-toplevel 2>$null
if ($LASTEXITCODE -ne 0 -or -not $root) { Write-Error "Burasi bir git deposu degil. Once 'git init' yapin (bkz. docs/git-workflow.md)."; exit 1 }
Set-Location $root

if (-not (git diff --cached --name-only)) {
  Write-Error "Staged dosya yok. Once 'git add <dosya>' yapin."
  exit 1
}

function Find-Bash {
  # WSL'nin System32\bash.exe'sini DISLA; Git for Windows bash'ini bul.
  foreach ($c in @(Get-Command bash -All -ErrorAction SilentlyContinue)) {
    if ($c.Source -and $c.Source -notmatch '\\System32\\bash\.exe$') { return $c.Source }
  }
  # git.exe ...\Git\cmd\ veya ...\Git\mingw64\bin\ altinda olabilir -> Git kokunu cikar.
  $git = Get-Command git -ErrorAction SilentlyContinue
  if ($git) {
    $dir = Split-Path $git.Source
    $gitRoot = $dir -replace '\\(cmd|mingw64\\bin|mingw32\\bin|usr\\bin|bin)$', ''
    foreach ($rel in @('bin\bash.exe', 'usr\bin\bash.exe')) {
      $p = Join-Path $gitRoot $rel
      if (Test-Path $p) { return $p }
    }
  }
  return $null
}

if (-not $NoBuild) {
  Write-Host "Commit oncesi build kontrolu..." -ForegroundColor Cyan
  $bash = Find-Bash
  if ($bash) {
    & $bash "scripts/build-check.sh" "--changed"
    if ($LASTEXITCODE -ne 0) { Write-Error "Build basarisiz -> commit iptal."; exit 1 }
  } else {
    Write-Warning "bash bulunamadi; commit-oncesi build atlandi. (Push aninda pre-push hook yine build edecek.)"
  }
}

if ($Scope -eq '-') {
  $header = "$($Type): $Subject"
} else {
  $header = "$($Type)($Scope): $Subject"
}

git commit -m $header
Write-Host "Commit olusturuldu: $header" -ForegroundColor Green
Write-Host "Hatirlatma: push'tan once kod incelemesi yapin -> /code-review (Claude) veya scripts/review.ps1"

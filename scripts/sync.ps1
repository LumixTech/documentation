# =====================================================================
# Lumix sync (Windows / PowerShell)
# Branch'i guncel main ile rebase eder (trunk-based).
#   pwsh scripts/sync.ps1
# =====================================================================
$ErrorActionPreference = 'Stop'
$root = git rev-parse --show-toplevel 2>$null
if ($LASTEXITCODE -ne 0 -or -not $root) { Write-Error "Burasi bir git deposu degil."; exit 1 }
Set-Location $root

$cur = git symbolic-ref --short HEAD 2>$null
if (-not $cur) { Write-Error "Detached HEAD (bir branch uzerinde degilsiniz). Once: git switch <branch>"; exit 1 }
git fetch origin main

if ($cur -eq 'main') {
  git pull --rebase origin main
  Write-Host "main guncellendi." -ForegroundColor Green
  exit 0
}

Write-Host "Branch '$cur', origin/main ile rebase ediliyor..."
git rebase origin/main
if ($LASTEXITCODE -ne 0) {
  Write-Host "Rebase catismasi! Adimlar:" -ForegroundColor Yellow
  Write-Host "  1) Catismayi cozun"
  Write-Host "  2) git add <cozulen dosyalar>"
  Write-Host "  3) git rebase --continue"
  Write-Host "  (vazgecmek icin: git rebase --abort)"
  exit 1
}
Write-Host "Rebase tamam. Uzak branch'i guncellemek icin:" -ForegroundColor Green
Write-Host "  git push --force-with-lease"

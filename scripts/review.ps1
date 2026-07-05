# =====================================================================
# Lumix review (Windows / PowerShell)
# AI kod incelemesi icin girdi hazirlar ve panoya kopyalar.
# Claude Code kullaniyorsan dogrudan /code-review komutu daha pratiktir.
#
# Kullanim:
#   pwsh scripts/review.ps1            # staged degisiklikler
#   pwsh scripts/review.ps1 -Branch    # main'e gore tum branch farki
# =====================================================================
param([switch]$Branch)
$ErrorActionPreference = 'Stop'
$root = (git rev-parse --show-toplevel)
Set-Location $root

$checklist = "docs/REVIEW_CHECKLIST.md"
$out = @()
$out += "===== LUMIX KOD INCELEME ISTEGI ====="
$out += ""
if (Test-Path $checklist) { $out += (Get-Content $checklist -Raw) } else { $out += "(docs/REVIEW_CHECKLIST.md bulunamadi)" }
$out += ""
$out += "===== DEGISIKLIKLER ====="
if ($Branch) {
  $base = (git merge-base origin/main HEAD 2>$null)
  if (-not $base) { $base = "main" }
  $out += "(main'e gore tum branch farki)"
  $out += (git diff "$base...HEAD" | Out-String)
} else {
  $out += "(staged degisiklikler)"
  $out += (git diff --staged | Out-String)
}

$text = $out -join "`n"
$text | Write-Output
try {
  $text | Set-Clipboard
  Write-Host "`n(Inceleme istegi panoya kopyalandi.)" -ForegroundColor Green
} catch {
  Write-Host "`n(Pano kopyalama desteklenmiyor; yukaridaki cikti'yi elle kopyalayin.)" -ForegroundColor Yellow
}

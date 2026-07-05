# =====================================================================
# Lumix git kurulum (Windows / PowerShell)
# Depo init/klon SONRASI BIR KEZ calistirin. Idempotenttir (tekrar guvenli).
#   pwsh scripts/setup-git.ps1   (veya)   powershell -File scripts\setup-git.ps1
# =====================================================================
$ErrorActionPreference = 'Stop'
$root = git rev-parse --show-toplevel 2>$null
if ($LASTEXITCODE -ne 0 -or -not $root) { Write-Error "Burasi bir git deposu degil. Once 'git init -b main' yapin."; exit 1 }
Set-Location $root

git config core.hooksPath .githooks
git config core.autocrlf false
git config pull.rebase true
git config branch.autosetuprebase always
git config rerere.enabled true
git config commit.template .gitmessage

# Exec-bit'i index'e yaz: Windows'tan commit'lense bile Linux'ta calistirilabilir kalsin.
# (Sadece dosyalar zaten izleniyorsa etki eder; ilk commit'ten once sessizce gecer.)
git update-index --chmod=+x .githooks/commit-msg .githooks/pre-commit .githooks/pre-push 2>$null
foreach ($f in (git ls-files 'scripts/*.sh')) { git update-index --chmod=+x $f 2>$null }

Write-Host "Lumix git kurulumu tamam (Windows)." -ForegroundColor Green
Write-Host "  - core.hooksPath = .githooks  (commit/push kapilari aktif)"
Write-Host "  - pull.rebase = true          (trunk-based, dogrusal gecmis)"
Write-Host "  - autocrlf = false            (EOL'u .gitattributes yonetir, hepsi LF)"
Write-Host "  - commit.template = .gitmessage"
Write-Host ""
Write-Host "NOT: Hook'lar Git for Windows ile gelen bash uzerinden calisir." -ForegroundColor Yellow
Write-Host "     Git zaten kurulu oldugundan ek bir sey gerekmez." -ForegroundColor Yellow

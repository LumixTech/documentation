# =====================================================================
# Lumix yeni branch (Windows / PowerShell)
# Guncel main'den kisa omurlu feature branch acar.
#
# Kullanim:
#   pwsh scripts/new-branch.ps1 feature 86abc123 "kullanici giris akisi"
#   sonuc: feature/CU-86abc123-kullanici-giris-akisi
# =====================================================================
param(
  [Parameter(Mandatory=$true)]
  [ValidateSet('feature','fix','chore','docs','refactor','hotfix','experiment')]
  [string]$Type,
  [Parameter(Mandatory=$true)][string]$CuId,
  [Parameter(Mandatory=$true, ValueFromRemainingArguments=$true)][string[]]$Desc
)
$ErrorActionPreference = 'Stop'

$cu = $CuId -replace '^(CU-|cu-)',''
# Once Turkce harfleri ASCII'ye cevir (-replace varsayilan olarak buyuk/kucuk
# duyarsiz, yani 'C' kalibi 'c'yi de kapsar), SONRA kucult.
$map = @{ 'Ç'='c'; 'Ğ'='g'; 'İ'='i'; 'I'='i'; 'ı'='i'; 'Ö'='o'; 'Ş'='s'; 'Ü'='u' }
$text = ($Desc -join ' ')
foreach ($k in $map.Keys) { $text = $text -replace $k, $map[$k] }
$text = $text.ToLowerInvariant()
$slug = ($text -replace '[^a-z0-9]+','-').Trim('-')
if ($slug.Length -gt 40) { $slug = $slug.Substring(0,40).Trim('-') }

$branch = "$Type/CU-$cu-$slug"

Write-Host "main guncelleniyor..."
if (git status --porcelain) { Write-Error "Calisma agaci temiz degil. Once commit/stash yapin."; exit 1 }
git switch main 2>$null; if ($LASTEXITCODE -ne 0) { git checkout main }
git rev-parse --verify --quiet origin/main *> $null
if ($LASTEXITCODE -eq 0) {
  git fetch origin main;        if ($LASTEXITCODE -ne 0) { Write-Error "'git fetch origin main' basarisiz."; exit 1 }
  git merge --ff-only origin/main; if ($LASTEXITCODE -ne 0) { Write-Error "main fast-forward edilemedi (yerel main ayrismis)."; exit 1 }
} else {
  Write-Host "Bilgi: origin/main yok -> yerel main'den dallaniliyor."
}

git switch -c $branch 2>$null; if ($LASTEXITCODE -ne 0) { git checkout -b $branch }
Write-Host "Yeni branch olusturuldu: $branch" -ForegroundColor Green

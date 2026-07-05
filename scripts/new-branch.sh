#!/usr/bin/env bash
# =====================================================================
# Lumix yeni branch  -- guncel main'den kisa omurlu feature branch acar.
# Trunk-based: her is icin main'den dallan, ClickUp ID'sini ada goml.
#
# Kullanim:
#   bash scripts/new-branch.sh <tip> <CU-id> "<kisa aciklama>"
#   ornek: bash scripts/new-branch.sh feature 86abc123 "kullanici giris akisi"
#   sonuc: feature/CU-86abc123-kullanici-giris-akisi
#
# Tipler: feature fix chore docs refactor hotfix experiment
# =====================================================================
set -euo pipefail

type="${1:-}"; cu="${2:-}"
shift 2 2>/dev/null || true
desc="${*:-}"

valid_types="feature fix chore docs refactor hotfix experiment"
if [ -z "$type" ] || ! printf '%s' "$valid_types" | grep -qw "$type"; then
  echo "Kullanim: new-branch.sh <tip> <CU-id> \"<aciklama>\"" >&2
  echo "Tipler  : $valid_types" >&2
  exit 1
fi
if [ -z "$cu" ] || [ -z "$desc" ]; then
  echo "HATA: CU-id ve aciklama zorunlu." >&2
  echo "Ornek: new-branch.sh feature 86abc123 \"giris akisi\"" >&2
  exit 1
fi

# CU- onekini normalize et (hem '86abc' hem 'CU-86abc' kabul edilir)
cu="${cu#CU-}"; cu="${cu#cu-}"

# Aciklamayi slug'a cevir.
# Once Turkce harfleri (BUYUK ve kucuk) ASCII'ye cevir, SONRA tr ile kucult.
# (tr ASCII-only oldugu icin Turkce buyuk harfleri tek basina kucultemez.)
slug="$(printf '%s' "$desc" \
  | sed -e 's/Ç/c/g; s/ç/c/g; s/Ğ/g/g; s/ğ/g/g; s/İ/i/g; s/I/i/g; s/ı/i/g; s/Ö/o/g; s/ö/o/g; s/Ş/s/g; s/ş/s/g; s/Ü/u/g; s/ü/u/g' \
  | tr '[:upper:]' '[:lower:]' \
  | sed -e 's/[^a-z0-9]\+/-/g; s/^-//; s/-$//' \
  | cut -c1-40)"
slug="${slug%-}"

branch="${type}/CU-${cu}-${slug}"

echo "main guncelleniyor..."
# Calisma agaci temiz olmali (yanlislikla degisiklik tasimamak icin)
if [ -n "$(git status --porcelain)" ]; then
  echo "HATA: Calisma agaci temiz degil. Once commit/stash yapin." >&2
  exit 1
fi
git switch main 2>/dev/null || git checkout main 2>/dev/null || { echo "HATA: 'main' branch'i bulunamadi." >&2; exit 1; }
# origin/main varsa fast-forward zorunlu kil (bayat/yarim main'den dallanma)
if git rev-parse --verify --quiet origin/main >/dev/null; then
  git fetch origin main || { echo "HATA: 'git fetch origin main' basarisiz." >&2; exit 1; }
  git merge --ff-only origin/main || { echo "HATA: main fast-forward edilemedi (yerel main ayrismis). Once duzeltin." >&2; exit 1; }
else
  echo "Bilgi: origin/main yok (remote eklenmemis) -> yerel main'den dallaniliyor."
fi

git switch -c "$branch" 2>/dev/null || git checkout -b "$branch"
echo "Yeni branch olusturuldu: $branch"

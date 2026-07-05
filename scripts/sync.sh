#!/usr/bin/env bash
# =====================================================================
# Lumix sync  -- branch'i guncel main ile rebase eder (trunk-based).
# Kisa omurlu branch'leri taze tutar, merge curultusunu onler.
#   bash scripts/sync.sh
# =====================================================================
set -euo pipefail
root="$(git rev-parse --show-toplevel 2>/dev/null)" || { echo "HATA: Burasi bir git deposu degil." >&2; exit 1; }
cd "$root"

cur="$(git symbolic-ref --short HEAD 2>/dev/null || true)"
if [ -z "$cur" ]; then
  echo "HATA: Detached HEAD (bir branch uzerinde degilsiniz). Once: git switch <branch>" >&2
  exit 1
fi
git fetch origin main

if [ "$cur" = "main" ]; then
  git pull --rebase origin main
  echo "main guncellendi."
  exit 0
fi

echo "Branch '$cur', origin/main ile rebase ediliyor..."
if ! git rebase origin/main; then
  cat >&2 <<EOF
Rebase catismasi! Adimlar:
  1) Catismayi cozun
  2) git add <cozulen dosyalar>
  3) git rebase --continue
  (vazgecmek icin: git rebase --abort)
EOF
  exit 1
fi
echo "Rebase tamam. Uzak branch'i guncellemek icin:"
echo "  git push --force-with-lease"

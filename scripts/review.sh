#!/usr/bin/env bash
# =====================================================================
# Lumix review  -- AI kod incelemesi icin girdi hazirlar.
# Staged (veya main'e gore tum branch) degisikliklerini + kontrol
# listesini birlestirir. Cikti'yi kendi AI'niza verin:
#   - Claude Code kullanan (Windows): /code-review komutunu tercih edin.
#   - Diger AI kullanan: bu cikti'yi kopyalayip yapistirin.
#
# Kullanim:
#   bash scripts/review.sh            # staged degisiklikler
#   bash scripts/review.sh --branch   # main'e gore tum branch farki
# =====================================================================
set -euo pipefail
root="$(git rev-parse --show-toplevel 2>/dev/null)" || { echo "HATA: Burasi bir git deposu degil." >&2; exit 1; }
cd "$root"

checklist="docs/REVIEW_CHECKLIST.md"
mode="${1:-staged}"

echo "===== LUMIX KOD INCELEME ISTEGI ====="
echo
if [ -f "$checklist" ]; then
  cat "$checklist"
else
  echo "(docs/REVIEW_CHECKLIST.md bulunamadi)"
fi
echo
echo "===== DEGISIKLIKLER ====="
if [ "$mode" = "--branch" ]; then
  base="$(git merge-base origin/main HEAD 2>/dev/null || echo main)"
  echo "(main'e gore tum branch farki)"
  git diff "$base"...HEAD
else
  echo "(staged degisiklikler)"
  git diff --staged
fi

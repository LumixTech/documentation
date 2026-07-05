#!/usr/bin/env bash
# =====================================================================
# Lumix akilli commit  -- build kapisi + Conventional Commit (Turkce).
# Build'i commit'ten ONCE calistirir (kullanicinin istegi). Hook'lar
# ayrica formati dogrular ve ClickUp footer'ini ekler.
#
# Kullanim:
#   bash scripts/commit.sh [--no-build] <tip> <kapsam|-> "<konu>"
#   ornek: bash scripts/commit.sh feat auth "kullanici giris akisi eklendi"
#          bash scripts/commit.sh fix - "iade tutari yanlis hesaplaniyordu"  (kapsamsiz)
#          bash scripts/commit.sh --no-build docs - "README guncellendi"
# =====================================================================
set -euo pipefail
root="$(git rev-parse --show-toplevel 2>/dev/null)" || { echo "HATA: Burasi bir git deposu degil. Once 'git init' yapin (bkz. docs/git-workflow.md)." >&2; exit 1; }
cd "$root"

do_build=1
if [ "${1:-}" = "--no-build" ]; then do_build=0; shift; fi

type="${1:-}"; scope="${2:-}"; subject="${3:-}"
if [ -z "$type" ] || [ -z "$scope" ] || [ -z "$subject" ]; then
  cat >&2 <<EOF
Kullanim: commit.sh [--no-build] <tip> <kapsam|-> "<konu>"
  Tipler : feat fix docs style refactor perf test build ci chore revert
  Ornek  : commit.sh feat auth "kullanici giris akisi eklendi"
           commit.sh fix - "iade tutari yanlis hesaplaniyordu"   (kapsamsiz icin -)
EOF
  exit 1
fi

if [ -z "$(git diff --cached --name-only)" ]; then
  echo "HATA: Staged dosya yok. Once 'git add <dosya>' yapin." >&2
  exit 1
fi

if [ "$do_build" -eq 1 ]; then
  echo "Commit oncesi build kontrolu..." >&2
  if [ -f "$root/scripts/build-check.sh" ]; then
    bash "$root/scripts/build-check.sh" --changed || { echo "Build basarisiz -> commit iptal." >&2; exit 1; }
  fi
fi

if [ "$scope" = "-" ]; then
  header="${type}: ${subject}"
else
  header="${type}(${scope}): ${subject}"
fi

git commit -m "$header"
echo "Commit olusturuldu: $header"
echo "Hatirlatma: push'tan once kod incelemesi yapin -> bash scripts/review.sh"

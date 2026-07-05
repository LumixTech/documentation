#!/usr/bin/env bash
# =====================================================================
# Lumix build-check  -- mono-repo build/test kapisinin TEK kaynagi.
# Hook'lar, commit helper ve CI ayni bu scripti cagirir (tutarlilik).
# Bilesenleri otomatik tespit eder: backend (Gradle), frontend (pnpm/npm).
#
# Kullanim:
#   scripts/build-check.sh            # mevcut tum bilesenleri build et
#   scripts/build-check.sh --changed  # sadece degisen bilesenleri build et (hizli)
# =====================================================================
set -uo pipefail

root="$(git rev-parse --show-toplevel 2>/dev/null || pwd)"
cd "$root"

mode="${1:-all}"
rc=0
built=0

changed_paths() {
  { git diff --cached --name-only; git diff --name-only; } 2>/dev/null | sort -u
}

# $1: yol oneki. --changed modunda degilse her zaman calistir.
has_change() {
  [ "$mode" != "--changed" ] && return 0
  changed_paths | grep -q "^$1" && return 0 || return 1
}

run() { echo ">> $*" >&2; "$@"; }

# --- Backend (Gradle) ---
if [ -f "backend/gradlew" ]; then
  built=1
  if has_change "backend/"; then
    echo "== Backend build (Gradle) ==" >&2
    ( cd backend && run ./gradlew --console=plain build ) || rc=1
  else
    echo "== Backend: degisiklik yok, atlandi ==" >&2
  fi
elif [ -f "gradlew" ]; then
  built=1
  echo "== Build (Gradle, kok) ==" >&2
  run ./gradlew --console=plain build || rc=1
fi

# --- Frontend (pnpm tercih, yoksa npm) ---
if [ -f "frontend/package.json" ]; then
  built=1
  if has_change "frontend/"; then
    echo "== Frontend build (pnpm/npm) ==" >&2
    if command -v pnpm >/dev/null 2>&1; then
      ( cd frontend && run pnpm install --frozen-lockfile && run pnpm run build ) || rc=1
    elif command -v npm >/dev/null 2>&1; then
      ( cd frontend && run npm ci && run npm run build ) || rc=1
    else
      echo "UYARI: pnpm/npm bulunamadi -> frontend build atlandi." >&2
    fi
  else
    echo "== Frontend: degisiklik yok, atlandi ==" >&2
  fi
fi

if [ "$built" -eq 0 ]; then
  echo "build-check: build edilecek bilesen bulunamadi (gradlew/package.json yok) -> atlandi." >&2
  exit 0
fi

if [ "$rc" -ne 0 ]; then
  echo "build-check: BASARISIZ" >&2
else
  echo "build-check: OK" >&2
fi
exit $rc

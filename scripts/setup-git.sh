#!/usr/bin/env bash
# =====================================================================
# Lumix git kurulum (Linux/macOS/Git Bash)
# Depo init/klon SONRASI BIR KEZ calistirin. Idempotenttir (tekrar guvenli).
#   bash scripts/setup-git.sh
# =====================================================================
set -euo pipefail
root="$(git rev-parse --show-toplevel 2>/dev/null)" || { echo "HATA: Burasi bir git deposu degil. Once 'git init -b main' yapin." >&2; exit 1; }
cd "$root"

git config core.hooksPath .githooks      # hook'lari aktive et
git config core.autocrlf false           # satir sonlarini .gitattributes yonetir
git config pull.rebase true              # trunk-based: dogrusal gecmis
git config branch.autosetuprebase always # yeni branch'lerde de rebase
git config rerere.enabled true           # rebase catismalarini hatirla
git config commit.template .gitmessage   # commit mesaj sablonu

# Hook ve scriptlere calistirma izni (Linux/macOS icin sart)
chmod +x .githooks/* scripts/*.sh 2>/dev/null || true

# Exec-bit'i index'e de yaz: Windows'tan commit'lense bile Linux'ta calistirilabilir kalsin.
# (Sadece dosyalar zaten izleniyorsa etki eder; ilk commit'ten once sessizce gecer.)
git update-index --chmod=+x .githooks/commit-msg .githooks/pre-commit .githooks/pre-push 2>/dev/null || true
git ls-files -z 'scripts/*.sh' 2>/dev/null | xargs -0 -r -I{} git update-index --chmod=+x "{}" 2>/dev/null || true

echo "Lumix git kurulumu tamam."
echo "  - core.hooksPath = .githooks  (commit/push kapilari aktif)"
echo "  - pull.rebase = true          (trunk-based, dogrusal gecmis)"
echo "  - autocrlf = false            (EOL'u .gitattributes yonetir, hepsi LF)"
echo "  - commit.template = .gitmessage"

# Lumix Mono-repo — Geliştirme Talimatları

Bu klasör (`campus/`) Lumix **mono-repo**'sudur: **backend + frontend + infra**
birlikte yaşar (trunk-based). `documentation/` ayrı bir projedir, burada değildir.
ClickUp senkron politikası için üst dizindeki [../CLAUDE.md](../CLAUDE.md) geçerlidir.

## Git & Commit İş Akışı

Tam playbook: [docs/git-workflow.md](docs/git-workflow.md). Branch: trunk-based,
`<tip>/CU-<taskID>-<ad>`. Commit: Conventional Commits (Türkçe konu).

**Commit etmeden önce her seferinde, bu sırayla:**

1. **Build başarılı mı?** — `bash scripts/build-check.sh --changed` (veya
   `cd backend && ./gradlew build`). Build kırmızıysa commit etme, önce düzelt.
2. **Kod incelemesi** — [docs/REVIEW_CHECKLIST.md](docs/REVIEW_CHECKLIST.md)
   eksenlerinde gözden geçir: task uyumu, gereksiz yorum/ölü kod/debug çıktısı,
   SOLID · DRY · KISS, optimizasyon, sır sızması. Gerekirse `/code-review` çalıştır.
3. **Commit** — `bash scripts/commit.sh <tip> <kapsam|-> "<konu>"`.
   - Commit **konusunu sen (Claude) üret**: `git diff --staged`'i oku, değişikliği
     özetleyen doğru `<tip>(<kapsam>): <konu>` **Türkçe** mesajı yaz; konuyu kullanıcıya
     sorma, diff'ten türet. Konu ~72 karakter, sonunda nokta yok.
   - `commit-msg` hook formatı doğrular ve branch'ten ClickUp `Refs: CU-<id>` ekler.
   - Acil durumda format kapısını atlamak için mesajı `!` ile başlat (örn. `!acil ...`);
     `!` mesajda kalır, hook ve CI bunu bypass eder.

Yeni iş için branch: `bash scripts/new-branch.sh feature <CU-id> "<ad>"`.
Push'tan önce `pre-push` hook build'i **zorunlu** çalıştırır. Doğrudan `main`'e push etme.

## Kurulum (bir kez)

Bu klasörde `git init -b main` yaptıktan sonra `bash scripts/setup-git.sh`
(Windows: `pwsh scripts/setup-git.ps1`) çalıştırın — hook'ları ve ayarları aktive eder.
Ayrıntı: [docs/git-workflow.md](docs/git-workflow.md) bölüm 0.

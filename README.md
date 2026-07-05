# Lumix Campus — Mono-repo

Lumix, on-prem çalışan **multi-tenant okul yönetim platformudur**. Bu repo, platformun
**mono-repo**'sudur: backend mikroservisleri, frontend ve infra tanımları birlikte yaşar
(trunk-based development).

> Uzak repo: `https://gitlab.hsoylu.dev/lumix/campus` — doğrudan `main`'e push **yasak**,
> her değişiklik MR ile girer (bkz. [docs/git-workflow.md](docs/git-workflow.md)).

## Depo düzeni

| Dizin | İçerik |
|---|---|
| [backend/](backend/) | Gradle (Kotlin DSL) multi-module backend. Her servis [service-template](backend/service-template/README.md)'ten türer (Hexagonal, Java 25, Spring Boot 4). |
| [infra/apicurio/](infra/apicurio/README.md) | Apicurio Registry (Protobuf schema registry) — docker compose kurulumu. |
| [scripts/](scripts/) | Geliştirici betikleri: branch açma, commit, review, sync, schema smoke test (`.sh` + `.ps1` çiftleri). |
| [docs/](docs/) | Repo içi süreç dokümanları: [git-workflow.md](docs/git-workflow.md), [REVIEW_CHECKLIST.md](docs/REVIEW_CHECKLIST.md). |
| [.githooks/](.githooks/) | `commit-msg` (Conventional Commits + ClickUp ref), `pre-commit`, `pre-push` (build zorunlu). |

Ürün/mimari dokümantasyonu ayrı projededir: `../documentation` (Docusaurus).
Sprint 0 kurulumlarının implementasyon anlatımı: `documentation/docs/sprint-implementations/`.

## Hızlı başlangıç

```bash
# 1) Toolchain — mise (Windows dahil) veya asdf; .tool-versions'ı okur (Java 25 + Node 24)
mise install

# 2) Git hook + ayarları etkinleştir (bir kez)
bash scripts/setup-git.sh          # Windows: pwsh scripts/setup-git.ps1

# 3) Backend derle + test + statik analiz
cd backend && ./gradlew check
```

## Günlük akış (özet)

```bash
bash scripts/new-branch.sh feature <CU-id> "<kisa-ad>"   # ClickUp task'ına bağlı branch
# ... kod ...
bash scripts/build-check.sh --changed                     # build yeşil mi?
bash scripts/commit.sh <tip> <kapsam|-> "<konu>"          # Conventional Commit (Türkçe konu)
git push -u origin HEAD                                   # MR aç; CI: build + commit-lint + schema
```

Ayrıntılar: [docs/git-workflow.md](docs/git-workflow.md) · Contract/şema standartları:
[CONTRIBUTING.md](CONTRIBUTING.md) · Claude Code talimatları: [CLAUDE.md](CLAUDE.md).

## CI

Asıl pipeline **GitLab CI** ([.gitlab-ci.yml](.gitlab-ci.yml)): `gradle check` + commit-lint +
buf (proto lint/breaking) → distroless image (Kaniko) → deploy placeholder.
`.github/workflows/ci.yml` yalnızca GitHub mirror için yedek güvenlik ağıdır.

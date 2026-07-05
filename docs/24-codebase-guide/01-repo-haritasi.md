---
title: "1 · Repo Haritası — campus/ içinde ne nerede?"
description: "Mono-repo'daki her dosya ve klasörün ne işe yaradığı; hangi değişiklik için hangi dosyaya bakacağını öğreten harita."
sidebar_position: 1
---

# Repo Haritası — `campus/` içinde ne nerede?

## Bu sayfa ne anlatıyor?

Repo'yu ilk kez klonlayan biri "bu dosyalar da ne?" diye bakakalır. Bu sayfayı
bitirdiğinde `campus/` içindeki **her dosyanın neden var olduğunu** bileceksin ve
"X'i değiştirmem lazım, hangi dosyaya bakayım?" sorusuna kendin cevap verebileceksin.
Hiçbir ön bilgi gerektirmez.

## 1. Büyük resim: neden mono-repo?

Lumix'in tüm kodu **tek git deposunda** yaşar: backend mikroservisleri, frontend
(gelecek sprintlerde) ve infra tanımları. Buna **mono-repo** denir
([ADR-0001](../adr/0001-mono-repo.md)). İki kişilik takımda bunun getirisi büyük:

- Tek `git clone`, tek PR akışı, tek CI — ayrı ayrı 10 repo yönetilmez.
- Ortak kurallar (`.gitignore`, hook'lar, format) **bir kez** tanımlanır, her koda uygulanır.
- Servisler arası değişiklik (örn. bir şemayı hem üretici hem tüketici tarafında
  güncellemek) **tek atomik commit** olur.

> `documentation/` (şu an okuduğun site) bilerek **ayrı repodadır** — kendi yaşam
> döngüsü var, kod pipeline'ını tetiklemez.

## 2. Kök dizin: dosya dosya

```
campus/
├── backend/          ← tüm backend kodu (Gradle multi-module)
├── infra/            ← altyapı tanımları (şimdilik: Apicurio compose)
├── scripts/          ← geliştirici betikleri (.sh + .ps1 ikizleri)
├── docs/             ← süreç dokümanları (git akışı, review listesi)
├── .githooks/        ← git hook'ları (commit/push kapıları)
├── .github/          ← GitHub mirror CI + PR şablonu + CODEOWNERS
├── .gitlab-ci.yml    ← ASIL CI pipeline (GitLab)
├── CLAUDE.md         ← Claude Code'a proje talimatları
├── CONTRIBUTING.md   ← Protobuf/şema sözleşme standartları
├── README.md         ← hızlı başlangıç
└── (sürüm/stil pinleme dosyaları — aşağıda)
```

### Sürüm ve stil pinleme dosyaları (kökte duran "küçük" ama kritik dosyalar)

| Dosya | Kimin için | Ne yapar |
|---|---|---|
| `.tool-versions` | **mise** / asdf | Java `temurin-25.0.1` + Node `24` — Windows'ta önerilen tek araç `mise install` |
| `.sdkmanrc` | SDKMAN (Linux/macOS) | Java `25.0.1-tem` (`sdk env`) |
| `.nvmrc` | nvm | Node `24` (`nvm use`) |
| `.editorconfig` | tüm IDE'ler | UTF-8, LF, boşluk indent (Java 4, yml/ts 2), satır 120 — IDE fark etmeksizin aynı stil |
| `.gitattributes` | git | satır sonu normalizasyonu (Windows/Linux karışık takım için) |
| `.gitignore` | git | build çıktıları, IDE dosyaları, **sırlar** (`.env`, `*.pem`, `*.key`...) asla commit'lenmez |
| `.gitmessage` | git commit şablonu | `<tip>(<kapsam>): <konu>` formatını hatırlatan yorumlu şablon |

Üç sürüm dosyasının da **aynı sürümleri** söylemesi kuraldır: sürüm yükseltirken
`.tool-versions` + `.sdkmanrc` + `.nvmrc` + CI imajını birlikte güncelle.

:::warning `.gitignore` tuzağı
`out/` kuralı **`/out/`** diye anchor'lıdır. Anchor'sız yazılsaydı hexagonal
`port/out/` ve `adapter/out/` paketlerindeki **kaynak kodu da yok sayardı** —
yaşanmış bir hatadır. Yeni bir dosyan commit'e girmiyorsa ilk bakacağın komut:
`git check-ignore -v <dosya>`.
:::

## 3. `backend/` — Gradle multi-module kökü

```
backend/
├── settings.gradle.kts        ← hangi modüller var? (build'in "içindekiler" sayfası)
├── build.gradle.kts           ← TÜM modüllere ortak kurallar (toolchain, format, analiz)
├── gradle.properties          ← Gradle davranış ayarları (parallel, cache)
├── gradle/
│   ├── libs.versions.toml     ← MERKEZI bağımlılık kataloğu (sürümler SADECE burada)
│   ├── schema-registry.gradle.kts ← Apicurio görevleri (schemaRegister/Validate/SmokeTest)
│   └── wrapper/               ← Gradle wrapper (herkes aynı Gradle sürümünü kullanır)
├── gradlew / gradlew.bat      ← wrapper başlatıcıları (Linux / Windows)
├── buf.yaml                   ← Protobuf lint + breaking-change kuralları (CI bekçisi)
├── config/checkstyle/         ← statik analiz kuralları + test bastırmaları
└── service-template/          ← HER YENİ SERVİSİN KOPYALANACAĞI iskelet (7 modül)
```

Bu dosyaların her birinin derin anlatımı: [Gradle Build Sistemi](02-gradle-build-sistemi.md).
`service-template/`'in dosya dosya turu: [Service Template Turu](03-service-template-turu.md).

Gelecekte her mikroservis buraya kardeş klasör olarak gelecek:

```
backend/
├── service-template/     ← iskelet (dokunma, kopyala)
├── identity-service/     ← Sprint 2-3
├── organization-service/ ← Sprint 4
└── academic-service/     ← Sprint 6 ...
```

## 4. `infra/` — altyapı tanımları

Şimdilik tek bileşen var: `infra/apicurio/` — Protobuf şema kayıt sunucusunun
(Apicurio Registry) Docker Compose kurulumu.

| Dosya | Ne işe yarar |
|---|---|
| `docker-compose.yml` | 3 container: PostgreSQL (şema deposu) + Registry API (`:8080`) + Web UI (`:8888`) |
| `.env.example` | Ortam değişkenleri örneği — `cp .env.example .env` yapıp şifreyi değiştirirsin; `.env` asla commit'lenmez |
| `README.md` | Sunucuya kurulum, BACKWARD kuralı, smoke test, üretim notları |

Neden var, nasıl kullanılır: [Sprint 0 §6](../sprint-implementations/sprint-0-hazirlik-ve-toolchain.md).
İleride K8s/Helm/Terraform tanımları da `infra/` altına gelecek (Sprint 14-15).

## 5. `scripts/` — günlük işlerin otomasyonu

Her betiğin `.sh` (Linux/macOS/Git Bash) ve `.ps1` (Windows PowerShell) ikizi vardır;
ikisi de aynı işi yapar. Ayrıntılı anlatım: [Kalite Güvencesi & Git](05-kalite-guvence-ve-git.md).

| Betik | Ne zaman kullanırsın |
|---|---|
| `setup-git.sh/.ps1` | Repo'yu klonladıktan sonra **bir kez** — hook'ları ve git ayarlarını aktive eder |
| `new-branch.sh/.ps1` | Yeni işe başlarken — güncel main'den `<tip>/CU-<id>-<slug>` branch'i açar |
| `build-check.sh` | Build kapısının **tek kaynağı** — hook'lar, commit helper ve CI hep bunu çağırır |
| `commit.sh/.ps1` | Commit atarken — önce build, sonra `<tip>(<kapsam>): <konu>` formatlı commit |
| `review.sh/.ps1` | Push'tan önce — diff'i AI incelemesi için hazırlar/panoya kopyalar |
| `sync.sh/.ps1` | Branch'i main ile taze tutmak — rebase yapar |
| `schema-smoke.sh/.ps1` | Apicurio kurulumunu uçtan uca test eder (register + pull + sil) |

## 6. `docs/`, `.githooks/`, CI dosyaları

- **`docs/git-workflow.md`** — git akışının tam playbook'u: kurulumdan branch
  korumasına her adım. Süreçle ilgili sorunun cevabı büyük ihtimalle burada.
- **`docs/REVIEW_CHECKLIST.md`** — kod incelemesinin eksenleri (task uyumu, ölü kod,
  SOLID/DRY/KISS, optimizasyon, sır sızması). Hem insana checklist hem AI'ya prompt.
- **`.githooks/`** — 3 hook: `commit-msg` (format + ClickUp ref), `pre-commit`
  (merge işareti/büyük dosya/sır taraması), `pre-push` (**zorunlu build**).
  `setup-git.sh` bunları `git config core.hooksPath .githooks` ile devreye alır.
- **`.gitlab-ci.yml`** — **asıl CI** (GitLab self-hosted): build+test → commit-lint →
  proto doğrulama → distroless imaj → deploy placeholder.
- **`.github/workflows/ci.yml`** — yalnızca GitHub mirror kullanılırsa devreye giren
  yedek; GitLab'da çalışmaz.

## 7. "Şunu değiştirmem lazım — hangi dosya?"

| Yapmak istediğin | Dokunacağın yer |
|---|---|
| Bir bağımlılığın sürümünü yükseltmek | `backend/gradle/libs.versions.toml` (SADECE burası) |
| Tüm modüllere ortak kural eklemek (örn. yeni compiler flag) | `backend/build.gradle.kts` → `subprojects {}` |
| Yeni mikroservis açmak | `service-template`'i kopyala + `settings.gradle.kts`'e modülleri ekle ([nasıl?](03-service-template-turu.md#yeni-servis)) |
| Yeni REST endpoint / iş kuralı / tablo | İlgili servisin modüllerinde ([hangi sırayla?](03-service-template-turu.md#yeni-ozellik)) |
| Yeni Kafka/gRPC şeması | `<servis>/adapter-grpc/src/main/proto/` + adlandırma kuralı (`CONTRIBUTING.md`) |
| Java/Node sürümü değiştirmek | `.tool-versions` + `.sdkmanrc` + `.nvmrc` + `.gitlab-ci.yml` imajı (dördü birlikte!) |
| Format/statik analiz kuralı değiştirmek | Spotless: `backend/build.gradle.kts`; Checkstyle: `backend/config/checkstyle/checkstyle.xml` |
| CI'a yeni job eklemek | `.gitlab-ci.yml` (GitHub mirror için ayrıca `.github/workflows/ci.yml`) |
| Commit/branch kurallarını değiştirmek | `.githooks/*` + `docs/git-workflow.md` + CI `commit-lint` (üçü senkron kalmalı) |
| Apicurio ayarı değiştirmek | `infra/apicurio/docker-compose.yml` + `.env.example` |

## 8. Sonraki adım

Sırayla devam et: [Gradle Build Sistemi](02-gradle-build-sistemi.md) — "build" dediğimiz
şeyin ne olduğunu ve bu repoda nasıl kurgulandığını sıfırdan öğreneceksin.

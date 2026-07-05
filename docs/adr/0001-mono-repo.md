---
title: "ADR-001: Mono-repo seçimi"
description: Tüm servis kodu (backend + frontend + infra) tek Git repo'sunda; dokümantasyon ayrı repo.
sidebar_position: 1
---

# ADR-001: Mono-repo seçimi

| Alan | Değer |
|---|---|
| **Status** | Accepted |
| **Tarih** | 2026-06-05 |
| **Deciders** | Lumix çekirdek ekip |
| **Sprint** | Sprint 0 |

## Context

Lumix 12 microservice'ten oluşuyor ([ADR-004](./0004-microservice-topology-no-shared-lib.md)) ve
buna backend, frontend (web + mobile) ve infra (IaC, Helm) kodu ekleniyor. Ekip **2 kişi** ve
**trunk-based** çalışıyor. Bu boyutta iki temel soru var:

- Kod tek bir Git repo'sunda mı (mono-repo), yoksa her servis/bileşen ayrı repo'da mı (polyrepo) yaşasın?
- Cross-cutting değişiklikler (örn. bir gRPC sözleşmesi + onu kullanan iki servis + frontend tipi) tek
  atomik değişiklikte mi yapılabilsin?

Güçler: küçük ekipte **operasyon yükü** (N repo × N CI × N sürüm), değişikliklerin **atomikliği**,
**tutarlı toolchain** (tek Gradle sürümü, tek version catalog), ve microservice **bağımsızlığını**
koruma isteği.

## Decision

**Tek mono-repo** kullanıyoruz: `campus` (GitLab: `lumix/campus`). İçinde `backend/`, `frontend/`,
`infra/` birlikte yaşar; her servis kendi Gradle alt-projesi olur. Servisler runtime'da hâlâ **bağımsız
deploy edilir** — mono-repo bir *kod organizasyonu* kararıdır, dağıtım kararı değil.

**İstisna:** `documentation/` (Docusaurus portalı) **ayrı repo**'dur; farklı yayın döngüsü (GitLab
Pages), farklı toolchain (Node) ve farklı katkı profili (kod değil içerik) olduğu için kod mono-repo'suna
dahil edilmedi.

## Consequences

- **Olumlu:**
  - Cross-cutting değişiklik **tek MR** + tek atomik commit (proto + üreten + tüketen birlikte).
  - **Tek CI** (`.gitlab-ci.yml`), tek toolchain sürümü, tek version catalog → sürüm drift'i yok.
  - Refactor/rename tüm repo'da tek seferde; kod arama ve gözden geçirme kolay.
- **Olumsuz / bedel:**
  - Repo büyür; CI her değişiklikte her şeyi build etmemeli.
  - Servis-bazlı erişim granularitesi kaba (herkes her şeyi görür).
  - Git geçmişi tüm bileşenler için ortak; gürültü artabilir.
- **Azaltıcı önlemler:**
  - CI'da **path-filter** (`rules:changes: [backend/**/*]`) ile yalnızca değişen bileşen build edilir.
  - `CODEOWNERS` ile dizin-bazlı sahiplik ve zorunlu review.
  - Servisler ayrı Gradle alt-projesi + ayrı deploy artefaktı (bağımsızlık korunur).

## Alternatives Considered

- **Polyrepo (repo-per-service)** — Her microservice/bileşen ayrı Git repo'su. Servis bağımsızlığı en
  yüksek, erişim kontrolü ince. → **Elendi:** 2 kişilik ekip için 12+ repo × ayrı CI × ayrı sürümleme
  operasyonel olarak ağır; cross-cutting değişiklikler çok-repo koordinasyonu ve sürüm kilidi gerektirir.
- **Hibrit (kod mono, kütüphaneler ayrı)** — Servisler mono, paylaşılan kütüphaneler ayrı repo. → **Elendi:**
  Lumix'te **paylaşılan kütüphane yok** ([ADR-004](./0004-microservice-topology-no-shared-lib.md)), dolayısıyla
  hibridi haklı çıkaracak ayrı-versiyonlanan artefakt bulunmuyor.
- **Monolith (tek deployable)** — Tek repo + tek deployable. → **Elendi:** Müşteri başına ayrı kurulum,
  bağımsız ölçekleme ve bounded-context sınırları microservice topolojisini gerektiriyor (ayrı karar, ADR-004).

## References

- [Teknoloji Kararları — Tek Sayfa Özet](../00-overview/02-technology-stack-decisions.md)
- [ADR-004: Microservice topoloji + no shared lib](./0004-microservice-topology-no-shared-lib.md)
- `campus/CLAUDE.md`, `campus/docs/git-workflow.md` — mono-repo git iş akışı

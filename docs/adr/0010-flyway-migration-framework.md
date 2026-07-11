---
title: "ADR-010: Flyway migration framework (SQL-first, forward-only)"
description: Şema değişiklikleri Flyway ile; SQL-first, forward-only (undo yok), V<NNN>__ + R__ isimlendirme, boot-time migrate, migrator/app kullanıcı ayrımı. Liquibase, elle script ve Hibernate ddl-auto elendi.
sidebar_position: 10
---

# ADR-010: Flyway migration framework (SQL-first, forward-only)

| Alan | Değer |
|---|---|
| **Status** | Accepted |
| **Tarih** | 2026-07-11 |
| **Deciders** | Lumix çekirdek ekip |
| **Sprint** | Sprint 1 |

## Context

DB-per-service ([ADR-004](./0004-microservice-topology-no-shared-lib.md)) gereği her servisin kendi şeması
var ve **versiyonlu, tekrarlanabilir** şema değişikliği gerekiyor. Kısıtlar:

- **Zero-downtime** dağıtım (rolling deploy sırasında eski+yeni kod aynı şemayla çalışabilmeli).
- **Denetlenebilir** ve gözden geçirilebilir değişiklik (kod review'da SQL görünsün).
- **Kullanıcı ayrımı**: migration DDL'i migrator, runtime DML'i app kullanıcısıyla; PgBouncer transaction-mode
  havuzu session-level advisory lock ile uyumsuz olduğundan migration havuzu **bypass** etmeli.
- 12 servis **aynı** migration standardını izlemeli.

## Decision

Şema yönetimi **Flyway** ile yapılır: **SQL-first**, **forward-only** (undo/`U__` migration yok), boot-time
otomatik migrate (Spring Boot autoconfig) + CI'da ayrı Gradle `flywayValidate` kapısı.

- İsimlendirme: `V<NNN>__<service>_<feature>_<action>.sql` (versiyonlu) ve `R__<ad>.sql` (repeatable —
  view/function/reference seed). `validateMigrationNaming=true` + DB-free naming-lint testi.
- **Bağlantı modeli**: migration `<svc>_migrator` kullanıcısıyla doğrudan Postgres'e (5432, PgBouncer bypass);
  runtime app `<svc>_app` ile PgBouncer'a (6432). Hibernate `ddl-auto=validate` — şemadan Flyway sorumlu,
  JPA DDL üretmez.

## Consequences

- **Olumlu:** SQL şeffaf ve gözden geçirilebilir; değişiklik versiyonlu ve CI'da valide; boot'ta otomatik
  uygulanır; forward-only basit ve öngörülebilir bir mental model verir.
- **Olumsuz / bedel:** Otomatik rollback yok (geri alma için **telafi migration**'ı yazılır); baseline
  yönetimi gerekir; büyük tabloda online DDL disiplini (lock süresi) elde tutulmalı.
- **Azaltıcı önlemler:** Zero-downtime **expand/contract** örüntüsü dokümante edildi (database-architecture/14);
  naming-lint testi konvansiyonu CI'da zorlar; `R__` idempotent seed (bkz.
  [ADR-008](./0008-reference-data-typed-lookup-tables.md)); migrator/app ayrımı least-privilege sağlar.

## Alternatives Considered

- **Liquibase** — XML/YAML/JSON changelog, yerleşik rollback. → **Elendi:** Soyutlama katmanı SQL şeffaflığını
  azaltır; ekip SQL-first istiyor; otomatik rollback yerine forward-only + telafi migration tercih edildi
  (production'da rollback zaten çoğu zaman güvenli değil).
- **Elle SQL script + kendi runner'ımız** — Sıfır bağımlılık, tam kontrol. → **Elendi:** Versiyonlama,
  checksum doğrulama, "hangi migration çalıştı" takibi ve tekrar-çalıştırma güvenliğini elle yazmak gerekir;
  Flyway bunu olgun biçimde standart veriyor.
- **Hibernate `ddl-auto` (schema generation)** — Kod-first, otomatik. → **Elendi:** Production'da
  öngörülemez/tehlikeli, şema kontrolü kaybolur. Tamamlayıcı olarak `ddl-auto=validate` kullanıyoruz (entity ↔
  şema uyumunu boot'ta doğrular), ama şemayı Flyway üretir.

## References

- [Flyway Zero-Downtime Migration Strategy](../database-architecture/14-flyway-zero-downtime-migration-strategy.md)
- [ADR-004: DB-per-service topolojisi](./0004-microservice-topology-no-shared-lib.md)
- [ADR-008: Reference data typed lookup tablolar](./0008-reference-data-typed-lookup-tables.md) — `R__` seed
- `campus/backend/*/adapter-persistence/src/main/resources/db/migration/`, `campus/CONTRIBUTING.md`

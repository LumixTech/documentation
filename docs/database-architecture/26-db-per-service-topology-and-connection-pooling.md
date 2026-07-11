---
title: DB-per-service Topolojisi ve Bağlantı Havuzlama (PgBouncer)
description: Tek PostgreSQL cluster üzerinde servis-başına ayrı veritabanı, migrator/app iki-kullanıcı modeli ve PgBouncer transaction-mode bağlantı havuzlama.
sidebar_position: 7
---

## Bu sayfa ne anlatıyor?

Lumix'te her microservice **kendi veritabanına** sahiptir (DB-per-service), ama hepsi **tek bir
PostgreSQL 17 cluster** içinde yaşar ve önlerinde **PgBouncer (transaction mode)** bağlantı havuzu
bulunur. Bu sayfa bu topolojiyi, **iki-kullanıcı (migrator/app) modelini**, PgBouncer'ın neden ve
nasıl kullanıldığını ve isimlendirme konvansiyonunu anlatır.

Çalışan referans implementasyon — **kaynak-of-truth**: [`campus/infra/postgres/`](https://gitlab.hsoylu.dev/lumix/campus/-/tree/main/infra/postgres)
(docker-compose + initdb script'leri + `README.md` + `verify.sh`). Karar temeli:
[ADR-004 — DB-per-service](../adr/0004-microservice-topology-no-shared-lib.md) ve
[Teknoloji Kararları §3](../00-overview/02-technology-stack-decisions.md).

> **İki ekseni karıştırma.** Bu sayfa **servis izolasyonu** eksenidir (identity vs academic).
> **Tenant izolasyonu** (Kadıköy vs Beşiktaş şubesi) ayrı bir eksendir: shared DB + `tenant_id` + RLS —
> bkz. [Tenant-based RLS](./09-tenant-based-rls-policy-design.md). Bu altyapı RLS'i etkilemez, onun temelini kurar.

## 1. Neden DB-per-service?

| Sebep | Açıklama |
|---|---|
| **Veri sahipliği** | Her bounded context kendi verisine sahiptir; başka servis onun tablolarına *doğrudan* erişemez. Erişim yalnızca gRPC + Kafka ile. |
| **Bağımsız evrim** | Bir servisin şeması değişince diğerleri derleme/çalışma-zamanı kırılmaz. |
| **Patlama yarıçapı** | Bir DB'deki sorun (kilit, bozuk migration) yalnızca o servisi etkiler. |
| **İleride ayırma** | Bir servis kızışırsa DB'si ayrı cluster'a taşınır — `pg_dump` kadar kolay (ayrı schema sökmekten kolay). |

**Bilinçli takas:** Servisler-arası SQL `JOIN` **yoktur** (ayrı DB'ler). Bu bir kısıt değil, mikro-servis
sınırını koruyan bir *özelliktir*; veri paylaşımı gRPC/Kafka ile yapılır.

## 2. Topoloji: tek cluster, çok veritabanı

```
                    ┌──────────────── PostgreSQL 17 (tek cluster) ────────────────┐
  Flyway (DDL) ─────┼─▶ :5432   lumix_identity   lumix_academic   ...  lumix_audit│
  <svc>_migrator    │           (her DB'nin sahibi <svc>_migrator)                │
                    └───────────────────────────▲────────────────────────────────┘
                                                 │ scram (auth_query pass-through)
  Uygulama (DML) ──▶ :6432 ┌──────────────────── PgBouncer ──────────────────────┐
  <svc>_app  HikariCP      │ pool_mode = transaction ,  * = host=postgres         │
                          └──────────────────────────────────────────────────────┘
```

**DB-per-service ≠ ayrı sunucu.** Servis başına ayrı **veritabanı**, tek cluster içinde. On-prem her
installation kendi cluster'ını çalıştırır (bkz. [Installation/Tenant/Scope](../01-tenancy-and-domain-model/01-installation-tenant-scope.md)).

## 3. İsimlendirme konvansiyonu

**DB:** `lumix_<servis>` · **Kullanıcılar:** `<servis>_migrator` (DDL), `<servis>_app` (DML).

| Servis (ADR-004) | Veritabanı | Migrator | App |
|---|---|---|---|
| identity | `lumix_identity` | `identity_migrator` | `identity_app` |
| organization | `lumix_organization` | `organization_migrator` | `organization_app` |
| academic | `lumix_academic` | `academic_migrator` | `academic_app` |
| assessment | `lumix_assessment` | `assessment_migrator` | `assessment_app` |
| counseling | `lumix_counseling` | `counseling_migrator` | `counseling_app` |
| performance | `lumix_performance` | `performance_migrator` | `performance_app` |
| communication | `lumix_communication` | `communication_migrator` | `communication_app` |
| finance | `lumix_finance` | `finance_migrator` | `finance_app` |
| file | `lumix_file` | `file_migrator` | `file_app` |
| **audit** | `lumix_audit` | `audit_migrator` | `audit_app` (append-only) |
| compliance | `lumix_compliance` | `compliance_migrator` | `compliance_app` |
| notification | `lumix_notification` | `notification_migrator` | `notification_app` |

## 4. İki-kullanıcı modeli: migrator (DDL) vs app (DML)

Her servisin **iki** DB kullanıcısı vardır:

- **`<svc>_migrator`** — DDL yetkili, `public` schema sahibi. Flyway migration'ları bununla çalışır.
- **`<svc>_app`** — yalnızca runtime DML. Uygulama (HikariCP) bununla bağlanır.

App yetkileri elle değil, **default privileges** ile verilir:

```sql
-- migrator ileride bir tablo oluşturunca, app OTOMATİK doğru DML'i alır:
ALTER DEFAULT PRIVILEGES FOR ROLE academic_migrator IN SCHEMA public
  GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO academic_app;
```

**Neden ayrım?** En az yetki: uygulama seviyesindeki bir SQL injection bile şema **değiştiremez**
(app'te DDL yok); migrator sırrı yalnızca migration job'ında bulunur.

**Audit özel (append-only):** `audit_app` yalnızca **SELECT + INSERT** alır — UPDATE/DELETE **reddedilir**
→ immutable audit log ([tech-stack §17](../00-overview/02-technology-stack-decisions.md)). SELECT bilerek
verilir (audit-service `QueryAuditLogs` API'sini sunar).

## 5. PgBouncer: transaction-mode havuzlama

**Problem:** `N servis × M replica × HikariCP havuzu` → Postgres'e giden bağlantı sayısı patlar; her PG
bağlantısı bellek/işlem açısından pahalıdır.

**Çözüm:** PgBouncer `pool_mode = transaction` — sunucu bağlantısı **transaction başına** atanır, transaction
bitince havuza döner. Yüzlerce istemci bağlantısı, onlarca sunucu bağlantısıyla karşılanır.

- **Port 6432** — uygulamalar buraya bağlanır.
- **Wildcard DB** (`* = host=postgres`) — yeni `lumix_<servis>` eklendiğinde PgBouncer config'i değişmez.
- **`auth_query`** — app kullanıcılarının scram verifier'ı `userlist.txt`'te tutulmaz; `pgbouncer_auth`
  kullanıcısı `pgbouncer.get_auth()` fonksiyonuyla `pg_shadow`'dan okur (tek yerde yönetim).

## 6. Migration PgBouncer'ı BYPASS eder

Flyway **doğrudan Postgres'e (5432)**, `<svc>_migrator` kullanıcısıyla bağlanır — PgBouncer'dan geçmez.

> **Neden?** Flyway eşzamanlı migration'ları seri hale getirmek için **session-level advisory lock** kullanır.
> Transaction pooling'de lock ile asıl migration farklı sunucu bağlantılarına düşebilir → pooling migration ile
> uyumsuz. Runtime trafiği (6432) havuzdan geçmeye devam eder.

## 7. Spring / HikariCP tarafı (service-template)

```yaml
spring:
  datasource:                           # runtime (DML) → PgBouncer (6432)
    url: jdbc:postgresql://${DB_HOST}:6432/lumix_academic
    username: academic_app
    hikari:
      leak-detection-threshold: 30000   # kapatılmayan bağlantı uyarısı
      data-source-properties:
        prepareThreshold: 0             # PgBouncer transaction mode: server-side prepared stmt KAPALI
  flyway:                               # migration (DDL) → doğrudan Postgres (5432), migrator
    url: jdbc:postgresql://${DB_MIGRATION_HOST}:5432/lumix_academic
    user: academic_migrator
```

## 8. Güvenlik: scram-sha-256 + kullanıcı-başı GRANT

- **`pg_hba.conf`**: tüm `host` (ağ) bağlantıları `scram-sha-256` **zorunlu**; konteyner-içi unix socket
  `trust` (ağa kapalı, yalnızca init/healthcheck).
- **İzolasyon GRANT ile:** her DB'de PUBLIC'ten CONNECT revoke; yalnızca kendi migrator+app kullanıcısına
  verilir → `identity_app` yalnızca `lumix_identity`'ye bağlanabilir (`lumix_finance` → *permission denied*).

## 9. Dikkat edilecek tuzaklar

- **Prepared statement.** Transaction pooling'de server-side prepared statement kırılır → template
  `prepareThreshold=0` kullanır (PgBouncer 1.21+ `max_prepared_statements` ile protokol seviyesinde de destekler).
- **`search_path` kalıcı değildir.** `SET search_path` bir sonraki transaction'a taşınmaz; şema-nitelikli
  isim veya rol düzeyi ayar kullanın (DB-per-service'te tek `public` şema olduğundan genelde sorun olmaz).
- **Tenant değişkeni `SET LOCAL`.** RLS için `SET LOCAL app.tenant_id=...` (transaction-scoped); düz `SET`
  havuzda sızar. Bkz. [Tenant-based RLS](./09-tenant-based-rls-policy-design.md).
- **Migration'ı pooler'dan geçirme.** Flyway daima 5432'ye doğrudan (§6).

## 10. Üretim notları

- Bu **dev** compose'dur. Üretimde her installation kendi K8s cluster'ında; DB/rol provizyonu **Ansible
  playbook** + sırlar **Vault / K8s Secret** ile ([tech-stack §20](../00-overview/02-technology-stack-decisions.md),
  [Customer Onboarding](../20-iac-provisioning/03-customer-onboarding-pipeline.md)).
- **Parolalar:** dev'de rol-tipi başına tek parola (ergonomi); izolasyon parolayla değil **GRANT** ile.
  Üretimde her kullanıcıya **ayrı** sır.

## 11. Doğrulama

`campus/infra/postgres/verify.sh` 14 kabul kontrolü çalıştırır: konteyner sağlığı, PgBouncer üzerinden app
bağlantısı, `pool_mode=transaction`, scram zorunluluğu, 12 DB + roller, servisler-arası izolasyon ve audit
append-only (INSERT geçer, UPDATE/DELETE reddedilir).

## İlgili konular

- [ADR-004 — Microservice topolojisi (DB-per-service)](../adr/0004-microservice-topology-no-shared-lib.md)
- [Teknoloji Kararları §3 — Veritabanı](../00-overview/02-technology-stack-decisions.md)
- [Flyway Zero-Downtime Migration](./14-flyway-zero-downtime-migration-strategy.md) — migration bağlantı modeli
- [Tenant-based RLS](./09-tenant-based-rls-policy-design.md) — tenant izolasyonu (ayrı eksen)
- [Installation / Tenant / Scope](../01-tenancy-and-domain-model/01-installation-tenant-scope.md)
- [Composite Index Ordering](./07-postgresql-index-ordering-and-query-design.md) — tenant_id-first index

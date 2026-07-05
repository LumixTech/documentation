---
title: Genel Mimari (Kuş Bakışı)
description: Lumix sisteminin kuş bakışı görünümü — installation, cluster, servisler, veri akışı.
sidebar_position: 3
---

## Bu sayfa ne anlatıyor?

Lumix sistemini **uçaktan görüyor olsak** ne görürdük? Bu sayfa müşteri kurulumundan en uç servise kadar sistemin **katmanlı haritasını** verir. Detay her katmanın kendi sayfasında.

## 1. Üç ayrı kavramı önce ayıralım

| Kavram | Ne demek | Örnek |
|---|---|---|
| **Installation** | Bir müşteriye ait tam kurulum (kendi K8s cluster + kendi DB + kendi Kafka) | "Ömer Okulları" kurumunun cluster'ı |
| **Tenant** | Bir installation içindeki bağımsız operasyonel birim | "Ömer Okulları → Kadıköy Şubesi", "Ömer Okulları → Beşiktaş Şubesi" |
| **Scope** | Bir kullanıcının tenant içinde gördüğü kapsam | "Hüseyin öğretmen → 11-A ve 12-B sınıfları" |

Bu üçü **çakışmaz, üst üste oturur**. Detay: [Installation/Tenant/Scope Modeli](../01-tenancy-and-domain-model/01-installation-tenant-scope.md).

## 2. Yüksek seviye haritası

```
┌─────────────────────────────────────────────────────────────────────────┐
│                      LUMIX SAAS (sen — sağlayıcı)                       │
│                                                                         │
│   GitLab CE      Rancher Manager        Internal Admin Panel           │
│   (CI + Repo +   (Multi-cluster K8s     (Lumix ekibinin                │
│    Container     yönetimi)              kullandığı panel)              │
│    Registry)                                                            │
│                                                                         │
│   Apicurio       Vault (master)         License Generator              │
│   Registry       (sırlar + KMS)         (JWT signed .lic)              │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
                                  │
                                  │ (her müşteri kendi cluster'ı —
                                  │  ayrı ağ, ayrı disk, ayrı DB)
                                  ▼
┌─────────────────────────────────────────────────────────────────────────┐
│            INSTALLATION 1 — "Ömer Okulları" Kurumu                      │
│  Ubuntu 24.04 LTS VPS'ler + K3s cluster                                 │
│                                                                         │
│   ┌──────── Traefik Ingress ──────── Kong API Gateway ───────┐         │
│   │            (TLS, routing)         (rate limit, auth)      │         │
│   │                                                            │         │
│   │   ┌─────────────── 10 Microservice ─────────────────┐    │         │
│   │   │  identity  organization  academic  assessment    │    │         │
│   │   │  counseling  performance  communication  finance │    │         │
│   │   │  file  audit  compliance                         │    │         │
│   │   │                                                  │    │         │
│   │   │  Each: Spring Boot 3.6 + Java 25 + gRPC server  │    │         │
│   │   │        + Kafka producer/consumer                 │    │         │
│   │   │        + own PostgreSQL DB                       │    │         │
│   │   └──────────────────────────────────────────────────┘    │         │
│   │                          │                                 │         │
│   │   ┌──────────────────────┴────────────────────────┐       │         │
│   │   │ Kafka (broker)        Redis Sentinel (auth)    │       │         │
│   │   │ Apicurio (schema)     Redis Sentinel (cache)   │       │         │
│   │   │ PostgreSQL (per svc)  Elasticsearch            │       │         │
│   │   │ RustFS (S3 storage)   Temporal (workflow)      │       │         │
│   │   │ Vault (this cluster)  Keycloak (opsiyonel)     │       │         │
│   │   └────────────────────────────────────────────────┘       │         │
│   │                                                            │         │
│   │   ┌────────── Observability ────────┐                     │         │
│   │   │ Prometheus + Thanos              │                     │         │
│   │   │ Loki + Promtail                  │                     │         │
│   │   │ Tempo + OTel Collector           │                     │         │
│   │   │ Grafana (dashboard)              │                     │         │
│   │   └──────────────────────────────────┘                     │         │
│   └────────────────────────────────────────────────────────────┘         │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘

    ▲                                          ▲
    │ (web)                                    │ (mobile)
    │                                          │
React SPA (CSR)                       React Native (iOS + Android)
  - Customer Admin Panel              - Öğretmen / Veli / Öğrenci app
  - Öğretmen / Veli portali           - Push notification destekli
  - Redux Toolkit + RTK Query
```

## 3. Bir müşteri (installation) içinde **ne yaşar**?

Her müşteri kurulumu **kendi içinde kapalı bir sistemdir**. Şunları içerir:

### 3.1. Compute katmanı
- **K3s K8s cluster** (1-3 node, müşteri boyutuna göre)
- **Kong Gateway** — dış istekler için L7 router + rate limit + auth header validation
- **Traefik Ingress** — TLS terminasyon + cluster içine yönlendirme
- **10 microservice pod**'u (her biri 1-N replica)

### 3.2. Veri katmanı
- **PostgreSQL** — her microservice'in **kendi DB**'si (DB-per-service)
- **PgBouncer** — connection pool (her DB önünde)
- **Redis Sentinel × 2** — bir cluster auth/session için (persistent), diğeri cache için (eviction'lı)
- **Elasticsearch** — search + log analytics
- **RustFS** — dosya/medya objesi (S3-compatible)

### 3.3. Mesajlaşma katmanı
- **Apache Kafka** — async event broker
- **Apicurio Schema Registry** (Protobuf compatibility)
- **Temporal** — workflow orchestration

### 3.4. Güvenlik katmanı
- **HashiCorp Vault** — sırlar + KMS (envelope encryption için)
- **Keycloak** (opsiyonel) — federated identity, müşteri talep ederse aktif
- **cert-manager** — internal CA + Let's Encrypt
- **ModSecurity** (Kong plugin) — WAF + OWASP CRS

### 3.5. Observability katmanı
- **Prometheus + Thanos** — metrik + uzun süreli storage
- **Loki + Promtail** — log toplama
- **Tempo + OTel Collector** — distributed tracing
- **Grafana** — tek dashboard

## 4. Senin (Lumix sağlayıcısı) tarafında ne var?

Sen müşterileri yönetirken merkezi olarak şu altyapıyı tutarsın:

- **GitLab CE** — kod, CI/CD, container registry
- **Rancher Manager** — tüm müşteri cluster'larını tek UI'dan görme
- **ArgoCD** — GitOps deployment (müşteri cluster'larına)
- **License Generator** — yeni müşteri için JWT-imzalı `.lic` üretim
- **Internal Admin Panel** — Lumix ekibinin kullandığı yönetim arayüzü (lisans, fatura, müşteri lifecycle)
- **Vault (master)** — kök KMS, her müşteri Vault'ı bundan beslenebilir

## 5. Bir API çağrısının yolculuğu

Bir öğretmen "yoklama gönder" butonuna bastığında neler olur?

```
1. React Web App → POST /api/v1/attendance/mark
                   (cookie: refresh_token; header: X-Correlation-Id)
                                      │
2. Traefik Ingress (TLS terminate)    │
                                      ▼
3. Kong Gateway
   - Rate limit check (Redis)
   - Auth header validate (JWT signature)
   - Forward to academic-service
                                      │
                                      ▼
4. academic-service Pod (Spring Boot)
   - SecurityFilterChain: JWT decode
   - Redis check: token status = 'active'?
   - Redis check: session status = 'active'?
   - SET app.tenant_id session variable
   - SET app.tenant_ids[] for multi-tenant roles
                                      │
                                      ▼
5. AttendanceController.markAttendance
   - @PreAuthorize: scope check (school → class)
   - Map to CreateAttendanceCommand
   - AttendanceUseCase.execute(command)
                                      │
                                      ▼
6. AttendanceAggregate (DDD)
   - Validate invariants (sınıf, tarih, öğrenci üyelik)
   - State transition: PENDING → MARKED
   - record DomainEvent(AttendanceMarked)
                                      │
                                      ▼
7. AttendanceRepository.save()
   BEGIN TX
     INSERT INTO attendance ... (RLS policy enforced)
     INSERT INTO outbox_events ... (AttendanceMarked event)
   COMMIT
                                      │
                                      ▼
8. Outbox Relay (background)
   - Read outbox row
   - Publish to Kafka topic: "academic.attendance.v1"
   - Mark outbox row as published
                                      │
                                      ▼
9. Multiple consumers:
   - notification-service → push notification → veliye
   - audit-service → AUDIT_LOGS append
   - elasticsearch-indexer → search index update
   - communication-service → mesaj listesi update
                                      │
                                      ▼
10. Response 200 → Frontend
    - RTK Query cache invalidate (attendance keys)
    - Optimistic update commit
    - WebSocket /topic/attendance.{classId} → diğer öğretmenlere realtime push
```

Bu akışta **her ok bir doc başlığı**. Detaylar ilgili kategori sayfalarında.

## 6. Veri akışı kategorileri

Sistemde 4 tür veri akışı var:

### 6.1. Sync request/response (gRPC veya REST)
- Frontend → Backend (REST, JSON)
- Service → Service (gRPC, Protobuf)
- Kullanım: kullanıcı isteği, hızlı sorgu, query-side

### 6.2. Async event (Kafka)
- Service → Kafka topic → multiple consumer
- Kullanım: side effect (notification, audit, projection update), eventual consistency

### 6.3. Real-time push (WebSocket + Redis Pub/Sub)
- Backend event → Redis Pub/Sub → tüm pod'lar → bağlı client'lar
- Kullanım: chat mesajı, live notification, dashboard güncellemesi

### 6.4. Background workflow (Temporal)
- Trigger → Temporal workflow → multi-step + retry + compensation
- Kullanım: DSAR, payment saga, customer onboarding seed, anonymization job

## 7. Cross-cutting concerns (her yere değen)

Bu konular **tek bir servise ait değil**, sistemin her yerinde:

| Konu | Nasıl yayılır |
|---|---|
| **Authentication** | Kong header validate + her service'te SecurityFilterChain |
| **Authorization** | Servis-level @PreAuthorize + DB-level RLS + audit |
| **Tracing** | OTel auto-instrumentation + manuel business span |
| **Logging** | structlog (Spring) + Promtail → Loki |
| **Metrics** | Micrometer + Prometheus scrape |
| **Audit** | Her kritik aksiyon Kafka audit topic + audit-service tüketir |
| **Tenant context** | JWT → `tenant_id` → MDC + Redis + DB session variable + Kafka header |

## 8. Sistem büyüdükçe neresi nasıl ölçeklenir?

| Bileşen | Bottleneck olduğunda ne yapılır |
|---|---|
| Stateless microservice | Pod replica artır (HPA + CPU/RAM metric) |
| PostgreSQL | İlk: connection pool optimize. Sonra: read replica + Patroni |
| Redis | İlk: memory artır. Sonra: Cluster mode (sharded) |
| Kafka | Topic partition artır + consumer group scale |
| Elasticsearch | Shard sayısı + replica + dedicated nodes |
| RustFS | Disk node ekle (her dosya cluster'da replike) |
| WebSocket | Pod replica + Redis Pub/Sub backplane zaten cross-pod |
| Frontend | Static asset CDN'e taşı (gerekirse) |

## 9. Diğer konularla ilişkisi

- [Vizyon ve Hedefler](./01-vision-and-goals.md) — projenin amacı
- [Teknoloji Kararları](./02-technology-stack-decisions.md) — burada gördüğün her teknolojinin neden seçildiği
- [Öğrenme Yolu](./04-learning-path.md) — bu mimariyi parça parça nasıl öğrenmeli
- [Installation/Tenant/Scope](../01-tenancy-and-domain-model/01-installation-tenant-scope.md) — multi-tenancy detayı
- [Domain Servisleri](../01-tenancy-and-domain-model/02-domain-services-overview.md) — 10 servis listesi

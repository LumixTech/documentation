---
title: Öğrenme Yolu
description: Yeni gelen geliştirici için sırayla okunacak doc'ların yol haritası.
sidebar_position: 4
---

## Bu sayfa ne anlatıyor?

Lumix'i sıfırdan öğrenmek isteyen biri **hangi sırada** ne okumalı? Burası bir **menü değil**, bir **yol haritasıdır**. İlk gün, ilk hafta, ilk ay seviyelerine ayrılmıştır.

## Seviyeler

- **Seviye 0 — İlk gün (Big Picture):** 2-3 saatte sistemi kuşbakışı anla.
- **Seviye 1 — İlk hafta (Foundation):** Domain + auth + DB temellerini kapsa.
- **Seviye 2 — İlk ay (Implementation):** Spesifik teknolojilerin derinine in.
- **Seviye 3 — Sürekli (Mastery):** Pattern'leri ve trade-off'ları içselleştir.

---

## Seviye 0 — İlk gün: Big Picture (2-3 saat)

Bu seviyeyi okuduktan sonra sistemde **ne olduğu**, **kimin için** olduğu, **hangi parçalardan oluştuğu** netleşir.

| Sıra | Doküman | Neden burada |
|---|---|---|
| 1 | [Giriş ve Yol Haritası](../intro) | Bu portal nasıl çalışıyor |
| 2 | [Vizyon ve Hedefler](./vision-and-goals) | Ne yapıyoruz, kim için |
| 3 | [Teknoloji Kararları](./technology-stack-decisions) | Tek sayfada tüm yığın |
| 4 | [Genel Mimari](./overall-architecture) | Sistemin kuş bakışı |
| 5 | [Sözlük](../glossary/glossary) | Sık geçen terimleri tanı |

## Seviye 1 — İlk hafta: Foundation

### 1.1. Domain & Multi-tenancy (Gün 1-2)

Lumix'in özü multi-tenancy. Bu olmadan auth, RLS, scope hiçbir şey anlaşılmıyor.

| Sıra | Doküman | Çıktı |
|---|---|---|
| 6 | [Installation / Tenant / Scope Modeli](../tenancy-and-domain-model/installation-tenant-scope) | "Bölge müdürü neden multi-tenant scope'ta?" sorusunu cevaplayabilirsin |
| 7 | [Domain Servisleri (10 microservice)](../tenancy-and-domain-model/domain-services-overview) | Hangi iş hangi servise ait, bilirsin |

### 1.2. Authentication & Authorization (Gün 3-4)

| Sıra | Doküman | Çıktı |
|---|---|---|
| 8 | Stateful Token Modeli (Auth Flow) | Access/refresh/session lifecycle |
| 9 | Session & Device Lifecycle | logout-all neden first-class |
| 10 | RBAC + ABAC Hibrit Model | permission resolution sırası |
| 11 | Organizational Scope Resolver | school → class → student short-circuit |

### 1.3. Veritabanı temelleri (Gün 5)

| Sıra | Doküman | Çıktı |
|---|---|---|
| 12 | PostgreSQL Composite Index | index sırası neden kritik |
| 13 | Row-Level Security (RLS) | tenant_id'li policy neden defense-in-depth |
| 14 | Flyway Zero-Downtime Migration | expand/contract pattern |

### 1.4. Mimari paradigmalar (Gün 6-7)

| Sıra | Doküman | Çıktı |
|---|---|---|
| 15 | Microservices vs Modular Monolith | Neden microservice'e geçtik |
| 16 | Domain-Driven Design | aggregate, invariant, bounded context |
| 17 | Hexagonal Architecture | adapter (input validation) vs core (business invariant) |

## Seviye 2 — İlk ay: Implementation

Şimdi konular kategorilere göre. Sırasını kendi ilgine göre değiştirebilirsin.

### 2.1. Messaging & Events

| Doküman | Konu |
|---|---|
| Kafka Fundamentals | Topic, partition, consumer group |
| Protobuf Schemas | Schema tasarımı, versiyonlama |
| Apicurio Schema Registry | Compatibility mode, BACKWARD vs FORWARD |
| Domain Event vs Integration Event | Internal/external ayrımı |
| Outbox Pattern | Atomic DB write + event publish |
| DLQ & Retry Strategy | Poison message izolasyonu |
| Idempotent Consumers | At-least-once toleransı |
| Command vs Event | Orchestration vs choreography |

### 2.2. Real-time & Cache

| Doküman | Konu |
|---|---|
| Redis Fundamentals | Data structures, persistence |
| Redis Sentinel Topology | HA failover |
| Cache-Aside Pattern | Cache hit/miss flow |
| Cache Invalidation | Entity vs view cache |
| TTL Strategy | Consistency trade-off |
| WebSocket + STOMP | Spring real-time |
| Redis Pub/Sub Backplane | Multi-pod fan-out |
| User-Pod Mapping | convertAndSendToUser cross-pod |

### 2.3. Backend stack

| Doküman | Konu |
|---|---|
| Spring Boot Foundation | Konvansiyonlar, profile, config |
| Java 25 Virtual Threads | Loom, Spring Boot 3.x entegrasyonu |
| Spring Security | Filter chain, JWT, custom provider |
| Spring Data JPA | Repository pattern, transaction |
| Validation Strategy | Adapter vs core, Jakarta Bean Validation |
| Error Handling | RFC 7807 Problem Details |
| gRPC Service Communication | Stub generation, interceptor |

### 2.4. Frontend (Web + Mobile)

| Doküman | Konu |
|---|---|
| React CSR Foundation | Vite + React 18 setup |
| Feature-Sliced Design (FSD) | Layer + slice + segment |
| Redux Toolkit | Slice, action, selector |
| RTK Query | Query, mutation, tag invalidation |
| Smart Navigation Routing | Config-driven URL |
| Frontend Token Storage | httpOnly cookie + refresh flow |
| Form Handling | React Hook Form + Zod |
| i18n Strategy | react-i18next |
| Frontend Permission Cache | /me/permissions invalidation |
| React Native Foundation | Mobile entegrasyonu |
| Push Notifications | FCM/OneSignal adapter |
| Mobile Distribution | App Store + Google Play |

### 2.5. Storage, Search, Payment, Notification

| Doküman | Konu |
|---|---|
| Object Storage Fundamentals | S3-compatible kavramı |
| RustFS Self-Hosted | Kurulum, replication |
| Pre-signed URLs | Direct upload/download |
| Lifecycle Policies | Soft delete, version retention |
| ClamAV Virus Scanning | Upload sonrası async scan |
| Elasticsearch Fundamentals | Index, shard, mapping |
| Indexing Strategy | Event-driven indexing |
| Payment Adapter Pattern | Çoklu sağlayıcı |
| Bank Virtual POS | Tenant-specific routing |
| Payment State Machine | Lifecycle + idempotency |
| Notification Adapter | Email/SMS/Push |
| Template Rendering | MJML + i18n |

### 2.6. Compliance & Security

| Doküman | Konu |
|---|---|
| KVKK + GDPR Foundations | Legal basis vs consent |
| Audit Log Design | Append-only schema |
| Audit Immutability | DB-level write revoke |
| Retention + Anonymization + DSAR | Temporal workflow |
| Vault Secret Management | Secret + KMS lifecycle |
| Envelope Encryption (PDR) | Per-tenant DEK |
| CORS/CSP/Security Headers | XSS/CSRF azaltma |
| Rate Limiting | Kong + uygulama |
| Threat Modeling | STRIDE per use case |
| Data Residency | Per-installation region |

### 2.7. Observability & Testing

| Doküman | Konu |
|---|---|
| Three Pillars (logs, metrics, traces) | Sinyal sorumlulukları |
| Prometheus + Thanos | Uzun süreli metrik |
| Grafana Dashboards | Per-service templates |
| OTel + Tempo Tracing | Manuel business span |
| Loki + Promtail | Log shipping + LogQL |
| Correlation ID Propagation | Async job, Kafka header |
| Sampling Strategy | Tail-based, error 100% |
| Test Pyramid | Unit/Integration/Contract/E2E dağılımı |
| Testcontainers | Real PostgreSQL/Kafka test |
| Contract Testing (Pact) | Producer/consumer schema |
| Playwright E2E | RBAC UI testleri |
| k6 Load Testing | 08:30 attendance peak |

### 2.8. Infra & DevOps

| Doküman | Konu |
|---|---|
| Kubernetes Fundamentals | Pod, deployment, service, ingress |
| K3s Lightweight K8s | Self-host kurulum |
| Helm Charts | Templating, values, releases |
| Rancher Multi-Cluster | Müşteri cluster yönetimi |
| Traefik Ingress | TLS, routing |
| Kong API Gateway | Rate limit, plugin |
| ModSecurity WAF | OWASP CRS |
| cert-manager TLS | Let's Encrypt + internal CA |
| Velero Backup | K8s state backup |
| Ubuntu Server Hardening | UFW, fail2ban, unattended-upgrades |
| Network Policy + mTLS | Pod-to-pod izolasyon |

### 2.9. IaC & CI/CD

| Doküman | Konu |
|---|---|
| Terraform Basics | Provider, variable, module |
| Ansible Basics | Playbook, inventory, idempotent |
| Customer Onboarding Pipeline | 4 katman akışı |
| License Management | JWT signed .lic |
| GitLab CE Self-Hosted | Repo + CI + registry |
| GitLab CI Pipelines | Stages, runners |
| Trivy Image Scanning | CVE gate |
| ArgoCD GitOps | App-of-apps pattern |
| Helm Versioning | Chart vs app version |
| Rollback Strategy | ArgoCD revert + DB compensation |

### 2.10. Workflow & Local Dev

| Doküman | Konu |
|---|---|
| Temporal Fundamentals | Workflow, activity, signal |
| Saga with Temporal | Compensation logic |
| DSAR Workflow Implementation | Multi-step privacy workflow |
| Background Jobs | Scheduled workflow |
| Tilt Multi-Service Dev | Hot reload + local K8s |
| Test Data Management | Seed script + anonymized fixtures |

## Seviye 3 — Sürekli: Mastery

Bu seviye **doc okumakla değil**, **production'da hata çözmekle, code review yapmakla, mimari karar tartışmakla** kazanılır. Yine de aşağıdaki içerikler yol gösterir:

- [Engineering Notes — Problem/Solution stories](../engineering-notes/product-problems-and-solution-decisions)
- Architecture Decision Records (ADR) — `engineering-notes/adr/` altında her büyük karar
- Post-mortem'ler ve incident analizleri
- Blog post taslakları

## "Ben sadece şunu öğrenmek istiyorum" — kısayollar

| Sen kimsin / ne arıyorsun | Önerilen yol |
|---|---|
| Backend developer onboarding | Seviye 0 + Seviye 1 + Seviye 2.3 + Seviye 2.1 |
| Frontend developer onboarding | Seviye 0 + Seviye 1.1 + Seviye 1.2 + Seviye 2.4 |
| DevOps engineer | Seviye 0 + Seviye 2.8 + Seviye 2.9 + Seviye 2.2 |
| Security review yapacaksın | Seviye 0 + Seviye 1.2 + Seviye 2.6 |
| Yeni microservice yazacaksın | Seviye 0 + Seviye 1.4 + Seviye 2.3 + Seviye 2.1 + Seviye 2.7 |
| Production incident debug | Seviye 2.7 + Seviye 2.8 + Sözlük |
| Sadece "ne kullanıyoruz" merak ediyorum | [Teknoloji Kararları](./technology-stack-decisions) tek sayfa yeter |

## Doc okurken disiplin

- Her doc'un sonundaki **"Daha derine inmek için"** linklerini takip et.
- Bilmediğin terimi gördüğünde önce **sözlüğe** bak.
- Anlamadığın yer kalırsa **engineering-notes**'a yaz — başkası da aynı yerde takılmış olabilir.
- Yeni öğrendiğini takıma aktar — doc'un eksiği varsa **sen güncelle**.

## Diğer konularla ilişkisi

- [Vizyon ve Hedefler](./vision-and-goals) — neden böyle öğreniyoruz
- [Teknoloji Kararları](./technology-stack-decisions) — yığın özeti
- [Genel Mimari](./overall-architecture) — kuş bakışı

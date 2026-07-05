---
title: Teknoloji Kararları — Tek Sayfa Özet
description: Lumix'te kullanılan tüm teknolojiler, neden seçildikleri ve hangi sorunu çözdüklerinin master listesi.
sidebar_position: 2
---

## Bu sayfa ne anlatıyor?

Lumix yığınındaki **her teknoloji kararının tek sayfada özetini** bulursun. Detaylı doc'lar her teknoloji için ayrı sayfada; burası **hızlı referans + neden seçildiği**. Yeni gelen geliştirici 15 dakikada tüm yığını gözden geçirebilmeli.

## Karar formatı

Her satır şu yapıda:

> **Konu** → **Seçim** — *bir cümle ile sebep* — [detay doc linki]

## 1. Mimari paradigma

| Konu | Karar | Sebep |
|---|---|---|
| Servis topolojisi | **Microservices** | Müşteri başına ayrı kurulum; modüllerin bağımsız ölçeklenmesi; takım büyüdükçe sınır netliği |
| Sync iletişim | **gRPC + Protobuf** | Yüksek performans, sıkı schema, code-gen ile DTO senkronizasyonu |
| Async iletişim | **Apache Kafka** | Tek event broker, replay, partition, sektör standardı |
| Schema yönetimi | **Apicurio Registry + Protobuf** | Açık kaynak, Confluent API uyumlu, RHEL gerektirmez |
| Distributed transaction | **Saga pattern (Temporal)** | Multi-servis akışlarda atomic olmayan ama auditable işlem |
| Atomic write + event | **Outbox Pattern** | DB write + event publish atomicity garantisi |
| Domain modelleme | **Domain-Driven Design (DDD)** | Karmaşık iş kurallarını domain'e gömmek |
| Servis iç yapısı | **Hexagonal Architecture** | Adapter (REST/Kafka/gRPC) ile Core ayrımı, validation katmanlama |
| Shared library | **Yok** | Microservice bağımsızlığı bozulmasın; duplicate kod kabul |

## 2. Backend stack

| Konu | Karar | Sebep |
|---|---|---|
| Dil | **Java 25 LTS** | Virtual threads, modern Java (sequenced collections, pattern matching, structured concurrency stable), ~2030'a kadar destek |
| Framework | **Spring Boot 4.x** | Java 25 resmî desteği Boot 4.0+ ile gelir (Spring Framework 7 tabanı); Java + Spring takım bilgisi, geniş ekosistem |
| Güvenlik | **Spring Security** | JWT + filter chain, geniş tooling |
| ORM | **Spring Data JPA + Hibernate** | Standart Spring veri katmanı |
| Migration | **Flyway** | Versiyonlu schema değişimi, expand/contract pattern |
| Validation | **Jakarta Bean Validation (`@Valid`)** | Adapter seviyesinde input validation |
| Test framework | **JUnit 5 + AssertJ + Mockito** | Sektör standardı |
| Integration test | **Testcontainers** | Gerçek PostgreSQL/Kafka/Redis container'da test |
| Contract test | **Pact** | Producer/consumer schema uyumluluğu |

## 3. Veritabanı

| Konu | Karar | Sebep |
|---|---|---|
| RDBMS | **PostgreSQL 17** | Açık kaynak, RLS, JSONB, geniş ekosistem |
| Connection pool | **HikariCP** (app) + **PgBouncer** (transaction mode) | Microservice'te bağlantı çoğalmasını PgBouncer'la kontrol |
| Schema migration | **Flyway** | Versiyonlu, sıralı, idempotent |
| Multi-tenancy izolasyon | **Müşteri başına DB** + **tenant_id ile RLS** | İki katmanlı izolasyon (installation + tenant) |
| Tenant filtering | **Row-Level Security (RLS) policy** | App seviyesi filtrelemenin altında DB seviyesi savunma |
| Index stratejisi | **Composite index, tenant_id-first** | Çoğu query tenant scope ile başlar |
| Replica | **Başlangıçta yok**, ileride streaming replication + Patroni | Erken optimizasyon yapma; replica gerektiğinde kural-bazlı routing |
| Backup | **pg_basebackup + WAL archiving + PITR** | RPO 15dk / RTO 2h hedefi |
| Restore drill | **Zorunlu**, scripted | "Backup var" yetmiyor, "restore çalışıyor" lazım |

## 4. Cache & State

| Konu | Karar | Sebep |
|---|---|---|
| Cache & state engine | **Redis 7** | In-memory, pub/sub, data structures, distributed locks |
| Topology | **Redis Sentinel** (master + 2 replica + 3 sentinel) | HA failover, sharding gereksiz boyutta |
| Auth Redis cluster | **AOF persistence + noeviction** | Token kaybolmaz |
| Cache Redis cluster | **Persistence kapalı + allkeys-lfu** | Bellek dolunca en az kullanılanı at |
| Distributed lock | **Redisson** | Spring entegrasyonu hazır, RedLock implementation |
| Rate limiting | **Redis + token bucket** | Atomic INCR + EXPIRE |

## 5. Mesajlaşma & Event

| Konu | Karar | Sebep |
|---|---|---|
| Async broker | **Kafka** | Tek broker, replay, partition, exactly-once semantics |
| Schema format | **Protobuf** | gRPC ile aynı; tek schema dili |
| Schema registry | **Apicurio** | OSS, Protobuf native, self-host |
| Compatibility mode | **BACKWARD** | Consumer önce upgrade, en yaygın |
| Outbox implementation | **Transactional outbox tablo + Kafka Connect Debezium veya custom relay** | Atomic write + publish |
| DLQ stratejisi | **Topic per service + retry topic + DLQ topic** | Retry + poison message izolasyonu |
| Consumer idempotency | **Event ID kontrol tablosu** | At-least-once delivery toleransı |

## 6. Real-time iletişim

| Konu | Karar | Sebep |
|---|---|---|
| Web real-time | **WebSocket + STOMP** | Spring built-in, abonelik semantiği |
| Multi-pod fan-out | **Redis Pub/Sub backplane** | Kafka'nın STOMP relay desteği yok; RabbitMQ ek broker yükü olur |
| User-pod mapping | **Redis Hash** (`user:pod:{userId}`) | `convertAndSendToUser` cross-pod routing |
| Reconnect strategy | **Client exponential backoff + missed events fetch endpoint** | Pod restart toleransı |

## 7. Frontend Web

| Konu | Karar | Sebep |
|---|---|---|
| Framework | **React 18** | Ekosistem, takım bilgisi |
| Rendering | **Client-Side Rendering (CSR)** | SaaS admin paneli; SEO gereksinimi yok |
| State management | **Redux Toolkit** | Tek state library, RTK Query ile birleşik |
| Server state | **RTK Query** | Redux içine entegre, ekstra lib yok |
| Routing | **React Router v6** | Standart React routing |
| URL ID görünürlüğü | **Config-driven (Redux root state)** | `smart navigation` ile merkezi karar |
| Form | **React Hook Form + Zod** | Performant, schema-based validation |
| UI kit | **Mantine** veya **Ant Design** (karar bekliyor — implementasyonda netleşir) | Kurumsal panel için zengin component |
| Mimari | **Feature-Sliced Design (FSD)** | Layer + slice + segment ile modülerlik |
| i18n | **react-i18next** | Çok dilli destek |
| Build tool | **Vite** | Hızlı dev server, modern bundling |
| E2E test | **Playwright** | Browser-level RBAC + form testleri |

## 8. Frontend Mobile

| Konu | Karar | Sebep |
|---|---|---|
| Framework | **React Native** | Web ekibi business logic'i paylaşabilir |
| Distribution | **Apple App Store + Google Play** | Standart kanallar |
| Push notification | **Provider-agnostic adapter** | FCM / OneSignal değiştirilebilir |
| State | **Redux Toolkit** (web ile aynı) | Kod paylaşımı |

## 9. Admin paneller

| Panel | Kullanıcı | İçerik |
|---|---|---|
| **Customer Admin Panel** | Müşteri organizasyonunun yöneticisi | Tenant, kullanıcı, rol, ödeme, ayar yönetimi |
| **Internal Admin Panel** | Lumix ekibi | Müşteri (Installation) lifecycle, lisans, destek, system health |
| **Rancher Manager UI** | DevOps | Multi-cluster K8s yönetimi |

## 10. Kimlik & Yetki

| Konu | Karar | Sebep |
|---|---|---|
| Token model | **Tam stateful** (access + refresh + session, hepsi Redis'te) | Tam revoke kontrolü, security önceliği |
| Access token format | **JWT (RS256)** | İmza doğrulama lokal, ama Redis kontrol mecburi |
| Refresh token storage | **httpOnly Secure cookie** | XSS koruması |
| Refresh hash | **SHA-512** | At-rest güvenlik |
| Rotation | **Her refresh'te token rotate** | Replay detection |
| Logout-all | **First-class özellik** | Cihaz/oturum kontrolü |
| IdP entegrasyonu | **Keycloak (opsiyonel)** | Custom login default, müşteri isterse Keycloak aktifleşir |
| RBAC | **Hibrit** (`role_permission` + `user_permission` + `common_permission`) | Role explosion engellemek |
| ABAC | **Tenant, ownership, department, scope** attribute'ları | Context-sensitive policy |
| Organizational scope | **Installation → Tenant → School/Class/Student** hiyerarşisi | Bölge müdürü gibi cross-tenant rolleri destekler |
| Permission revoke | **Permission değişince token revoke + force refresh** | Cache invalidation netliği |

## 11. Storage & Dosya

| Konu | Karar | Sebep |
|---|---|---|
| Object storage | **RustFS** (S3-compatible) | Self-host, Rust-based, S3 API |
| SDK | **AWS S3 SDK (adapter)** | İleride MinIO/R2'ye geçiş kolay |
| Upload model | **Pre-signed URL (PUT/multipart)** | App server bandwidth'i yememesi |
| Antivirus | **ClamAV** | Open source, GPL |
| AV trigger | **Kafka consumer: upload-completed → scan** | Async, scalable |
| Lifecycle | **Auto-expire pending upload, soft-delete window, version retention** | Maliyet + governance |

## 12. Search

| Konu | Karar | Sebep |
|---|---|---|
| Engine | **Elasticsearch** | Full-text, aggregation, geniş ekosistem |
| Indexing | **Event-driven** (Kafka consumer → ES) | Async, source-of-truth PostgreSQL |
| Query API | **Spring Data Elasticsearch + native query** | Karmaşık sorgu için raw query |

## 13. Ödeme

| Konu | Karar | Sebep |
|---|---|---|
| Provider | **Adapter pattern** | Her müşterinin farklı banka sanal POS'u olabilir |
| Routing | **Tenant config: provider id + credentials** | Müşteri başına farklı POS |
| State machine | **Pending → Authorized → Captured → Refunded / Failed** | Net lifecycle, audit edilebilir |
| Idempotency | **Idempotency-Key header + DB constraint** | Provider callback duplicate'i tolere et |
| Callback signature | **Provider'ın imza doğrulaması zorunlu** | Spoofing koruması |

## 14. Bildirim

| Konu | Karar | Sebep |
|---|---|---|
| Provider | **Adapter pattern** (Email, SMS, Push) | Her kurum farklı sağlayıcı kullanabilir |
| Email rendering | **MJML → HTML** | Cross-client uyum |
| Template | **Dile göre template + variable substitution** | i18n + dinamik içerik |
| Delivery | **Kafka event → notification consumer → provider** | Async, retry, DLQ |

## 15. Workflow Orchestration

| Konu | Karar | Sebep |
|---|---|---|
| Engine | **Temporal.io** | Saga, retry, compensation, durable workflow |
| Kullanım alanı | **DSAR workflow, ödeme saga, multi-step onboarding** | Çok-adımlı + uzun süreli |
| Scheduled jobs | **Temporal scheduled workflows** | Quartz/ShedLock yerine tek araç |

## 16. Observability

| Pillar | Tool | Storage |
|---|---|---|
| Metrik | **Prometheus** + **Thanos** (uzun vade) | TSDB |
| Log | **Loki** + **Promtail** (shipping) | Object storage compatible |
| Trace | **OpenTelemetry Collector** + **Tempo** | Object storage compatible |
| Dashboard | **Grafana** | Tek UI tüm sinyaller |
| Correlation | **`correlation-id` + `tenant-id`** her sinyalde | Async job'lara propagate |
| Sampling | **Tail-based** (başarılı %1, hatalı %100) | Trace maliyet kontrolü |

## 17. Güvenlik (cross-cutting)

| Konu | Karar | Sebep |
|---|---|---|
| Secret manager | **HashiCorp Vault** + **External Secrets Operator** | K8s + multi-customer |
| Field encryption | **Envelope encryption** (Vault Transit) | DB compromise ≠ data compromise |
| PDR/sağlık verisi | **Per-tenant DEK** + tenant-scoped key | KVKK özel kategori |
| TLS cert | **cert-manager + Let's Encrypt** (external) + **internal CA** (mTLS) | Otomatik renewal |
| WAF | **ModSecurity** (Kong plugin) + OWASP CRS | Layer 7 koruma |
| Rate limit | **Kong + uygulama seviyesi** | Tüm endpoint'ler default rate-limited |
| CORS | **Strict allowlist** (origin, methods, credentials) | XSS/CSRF azaltma |
| Audit log | **Append-only + ayrı DB user (revoke UPDATE/DELETE)** | Immutability |

## 18. Container & Orchestration

| Konu | Karar | Sebep |
|---|---|---|
| Container | **OCI image (Docker build)** | Standart |
| Image registry | **GitLab built-in container registry** | CI/CD ile entegre |
| Image scan | **Trivy** (GitLab CI'da) | CVE taraması, image push öncesi gate |
| Orchestrator | **Kubernetes (K3s)** | Self-host VPS için hafif K8s |
| OS | **Ubuntu Server 24.04 LTS** | VPS uyumu, 10 yıl destek |
| Multi-cluster | **Rancher Manager** | Müşteri başına cluster, merkezi yönetim |
| Ingress | **Traefik** | K8s-native, otomatik service discovery |
| API Gateway | **Kong Gateway OSS** | Plugin ecosystem (rate limit, auth, transform) |
| Service Mesh | **Yok başlangıçta** | İleride mTLS gereği büyürse Istio |

## 19. CI/CD

| Konu | Karar | Sebep |
|---|---|---|
| CI | **GitLab CE self-hosted + GitLab CI** | Repo + CI + registry tek araç |
| CD | **ArgoCD (GitOps)** | Git = source of truth, declarative |
| GitOps multi-cluster | **ArgoCD ApplicationSet** veya **Fleet (Rancher)** | Aynı manifest çoklu cluster |
| Helm | **Chart per service + umbrella chart** | Versiyonlu deploy |
| Image scan | **Trivy** her build'de | CI gate |
| Secret in pipeline | **Vault entegrasyonu** | Pipeline secret rotation destekli |

## 20. Infrastructure as Code

| Katman | Araç | Sorumluluk |
|---|---|---|
| VPS provisioning | **Terraform** (sağlayıcı API'si varsa) | VPS sipariş, network, DNS |
| OS config | **Ansible** | Firewall, K3s install, agent register |
| K8s deploy | **Helm + ArgoCD** | Application deploy |
| Customer seed | **Ansible playbook** | Keycloak realm, DB tenant init, Kafka topic, Vault seed |

## 21. Lokal geliştirme

| Konu | Karar | Sebep |
|---|---|---|
| Dev orchestrator | **Tilt** | 10 microservice'i tek komutla ayağa kaldırma, hot reload |
| Container runtime | **Docker Desktop** veya **Podman** | OCI uyumlu |
| Local K8s | **k3d** (prod K3s parity) | Tilt ile entegre; kind yalnızca upstream uyum testi için opsiyonel |
| Test data | **Faker.js / Java Faker + seed script** | Anonymized prod copy DEĞİL (KVKK) |

## 22. Compliance & Privacy

| Konu | Karar | Sebep |
|---|---|---|
| Yasal çerçeve | **KVKK + GDPR** | Türkiye + AB müşterileri |
| Legal basis | **Per-purpose matrix** (consent ≠ tek yol) | Doğru hukuki dayanak |
| DSAR workflow | **Temporal'da workflow** | Multi-step, auditable |
| Retention | **Per-purpose retention policy** | Manuel değil tabloda |
| Anonymization | **Geri dönüşsüz** (Vault key destroy + DB anonymize) | Right to be forgotten |
| Data residency | **Per-installation region pinning** | Türkiye verisi Türkiye'de |

## 23. Yardımcı standartlar

| Konu | Karar |
|---|---|
| API versioning | **URL path** (`/api/v1/...`, `/api/v2/...`) |
| Time zone | **Backend UTC store + tenant timezone display** |
| Date format | **ISO 8601** her yerde |
| Para birimi | **Decimal (BigDecimal/Money type)**, ISO 4217 currency code |
| ID format | **UUID v7** (time-ordered) yeni entity'ler için |
| Log format | **Structured JSON** (her log line key-value) |
| Error format | **RFC 7807 Problem Details** |

## 24. Lisanslama

| Konu | Karar | Sebep |
|---|---|---|
| Format | **JWT (RS256) signed license file** | Offline doğrulanabilir |
| İçerik | `customer_id`, `tenants_max`, `modules_enabled`, `valid_until`, `features` | Feature flag + quota |
| Online renewal | **Opsiyonel** (online müşteriler için periyodik renew) | Kısa expiry = ekstra güvenlik |
| Offline mode | **Tamamen lisans dosyasına dayalı** | İnternet olmadan çalışan kurumlar |

---

## Karar gerekçesi formatı

Her teknoloji için ayrı detay sayfasında **şu soruların cevabı** olmalı:

1. **Bu nedir?** — Sıfırdan öğrenmek isteyene
2. **Hangi problemi çözüyor?** — Olmasaydı ne acı çekerdik
3. **Nasıl çalışıyor?** — Mekanizma + diyagram
4. **Biz nasıl kullanıyoruz?** — Spesifik proje kararı
5. **Neden bu seçildi?** — Eleneneler + trade-off
6. **Pratik örnek** — Gerçek kod/config
7. **Dikkat edilecek tuzaklar** — Anti-pattern listesi

Bu standart `_TEMPLATE.md` dosyasında detaylı.

## Diğer konularla ilişkisi

- [Vizyon ve Hedefler](./01-vision-and-goals.md) — neden bu kararları aldık
- [Genel Mimari](./03-overall-architecture.md) — bu teknolojiler nasıl bir araya geliyor
- [Öğrenme Yolu](./04-learning-path.md) — hangi sırada öğrenmeli

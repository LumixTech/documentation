---
title: Sözlük (Glossary)
description: Lumix dokümantasyonunda kullanılan tüm teknik terimlerin standart tanımları.
sidebar_position: 1
---

## Bu sayfa ne anlatıyor?

Lumix dökümantasyonunda kullanılan **tüm teknik terimlerin** tek noktadan tanımlanması. Aynı kavramın farklı yerlerde farklı isimle anılması veya farklı tanımla kullanılması engellenir. Yeni terim eklenirken önce buraya, sonra ilgili doc'a yazılır.

## Nasıl kullanılır?

- Bilmediğin terimi gördüğünde **önce buraya bak**.
- Yeni doc yazarken: kullandığın teknik terim burada yoksa **önce ekle**, sonra doc'unu yaz.
- Bir terim güncellenirken: tüm doc'larda tutarlılığa dikkat (CI ileride bunu kontrol edebilir).

---

## A

**ABAC (Attribute-Based Access Control)**
Yetkilendirme kararının kullanıcının/kaynağın özelliklerine (attribute) göre verildiği model. Örnek: "Hüseyin sadece kendi şubesindeki sınıfların yoklamasını alabilir."

**Access Token**
Kısa ömürlü, API isteklerinde kullanılan token. Lumix'te JWT (RS256). Stateless gibi görünür ama Redis'te de durumu tutulur (fully stateful model).

**Adapter**
Hexagonal Architecture'da dış dünya ile iletişim noktası. REST controller, Kafka consumer, gRPC server hep adapter'dır. Core onları görmez.

**Aggregate**
DDD'de **birlikte hareket eden, tutarlılığı bir arada korunan domain nesneleri grubu**. Dış dünya aggregate ile sadece **aggregate root** üzerinden konuşur.

**Aggregate Root**
Aggregate'in dışa açılan tek kapısı. State değişimi sadece buradan yapılır.

**Anonymization**
Kişiyi tanımlanabilir kılmaktan **geri dönüşsüz** şekilde çıkaran işlem. Pseudonymization ile karıştırılmamalı (o geri dönüştürülebilir).

**Ansible**
Agentless, SSH üzerinden config management aracı. Lumix'te OS-level kurulum ve customer seed için kullanılır.

**Apicurio**
Açık kaynak Schema Registry. Lumix'te Protobuf schema'ları için kullanılır. Confluent Schema Registry API uyumlu.

**API Gateway**
Dış istekleri uygulamalara yönlendiren, yetki/rate-limit/transform yapan katman. Lumix'te **Kong Gateway OSS** rolü oynar.

**ArgoCD**
Kubernetes için GitOps deployment aracı. Git'teki manifest'leri cluster'a uygular.

**Audit Log**
"Kim, ne zaman, ne yaptı" sorusunu cevaplayan **append-only**, immutable kayıt yapısı. Application log'tan farklı; compliance ve incident analizi için.

**Avro**
Schema-based binary serialization formatı. Kafka'da çok yaygındı. Lumix Avro yerine **Protobuf** seçti (gRPC ile birleşik schema dili olsun diye).

## B

**BACKWARD Compatibility**
Schema Registry compatibility modu: yeni schema versiyonu eski mesajları okuyabilmeli. Consumer önce upgrade, producer sonra. Default seçim.

**Backfill**
Yeni schema'ya geçişte mevcut verilerin batch olarak yeni şekle taşınması.

**Backward-compatible Schema Change**
Mevcut consumer'ları bozmadan yapılabilen değişiklik. Yeni alan eklemek, NULL kabul etmek vs.

**Bounded Context**
DDD'de bir modelin/dilin geçerli olduğu sınır. Lumix'te **microservice ≈ bounded context**.

**Broker Relay**
Spring STOMP'un Spring'in yerine harici bir broker'a (RabbitMQ, ActiveMQ) mesaj relay etmesi. Lumix Kafka tek broker kararı verince Redis Pub/Sub backplane'e geçti.

## C

**Cache-Aside**
Cache pattern: app önce cache'e bakar, miss olursa DB'den okur ve cache'e yazar. Spring `@Cacheable` bu pattern'i uygular.

**cert-manager**
Kubernetes'te TLS sertifika lifecycle yöneten controller. Let's Encrypt + internal CA destekli.

**ClamAV**
Açık kaynak antivirus. Lumix'te yüklenen dosyaların taraması için kullanılır.

**Choreography**
Servislerin birbirinin event'lerine reaksiyon vererek koordine olduğu pattern. Central orchestrator yok. Lumix'te çoğu integration event-driven flow böyle.

**Command**
"Bunu yap" diyen mesaj. Reddedilebilir. Domain event'in karşıtı.

**Common Permission**
Tüm rollerde paylaşılan baseline permission seti.

**Compaction**
Kafka topic'te aynı key için sadece en son mesajı tutma. State recovery için yararlı.

**Compensation Event**
Saga'da bir adım başarısız olunca önceki adımları geri almak için gönderilen event. Örn. `OrderCancelled`, `PaymentReversed`.

**Connection Pool**
DB bağlantılarını yeniden kullanmak için tutulan havuz. Lumix'te **HikariCP** (uygulama) + **PgBouncer** (server-side multiplexing).

**Contract Test**
Producer ve consumer arasındaki schema sözleşmesini doğrulayan test. Lumix'te **Pact** kullanılır.

**Correlation ID**
Bir isteğin/akışın tüm sinyallerine (log, metric, trace) bağlanan stabil tanımlayıcı. Lumix'te `X-Correlation-Id`.

**CQRS (Command Query Responsibility Segregation)**
Read modeli (query) ile write modelinin (command) ayrı tasarlandığı pattern. Lumix'te "hard CQRS" yok ama prensipler uygulanır (read replica, projection).

**CSR (Client-Side Rendering)**
Browser'da render edilen frontend. Lumix web frontend CSR (React + Vite).

**Custom Resource Definition (CRD)**
Kubernetes'i kendi tipinle genişleten mekanizma. ArgoCD `Application`, cert-manager `Certificate` CRD'lerle çalışır.

## D

**DEK (Data Encryption Key)**
Veriyi doğrudan şifreleyen anahtar. Envelope encryption'da KEK ile şifreli tutulur.

**Defense in Depth**
Tek güvenlik katmanına güvenmemek, birden fazla katmanın aynı şeyi koruması. Örn. application filter + RLS + audit.

**Device Session**
Bir kullanıcının belirli cihaz/client'tan oluşturduğu session kaydı. Logout-all gibi feature'lar bunu kullanır.

**DLQ (Dead Letter Queue)**
Tüketicinin işleyemediği (retry tükenmiş) mesajların gönderildiği topic. Lumix'te servis başına DLQ topic var.

**DDD (Domain-Driven Design)**
Yazılımı iş alanı kavramları etrafında modelleme yaklaşımı. Aggregate, value object, bounded context, domain event temel kavramlar.

**Domain Event**
Bounded context içinde olan, iş anlamı taşıyan olay. `OrderPlaced` gibi. Internal model artifact'i; doğrudan Kafka'ya yayınlanmaz.

**DSAR (Data Subject Access Request)**
Kişinin kendisi hakkındaki veriye erişim/silme/düzeltme talebi. Lumix'te Temporal workflow ile orchestrate edilir.

**Dual-write / Dual-read**
Schema geçişinde geçici olarak iki yere yazıp/okumak. Expand/contract pattern'ın parçası.

## E

**E2E Test (End-to-End)**
Browser veya dış sınırdan tüm sistemi exercise eden test. Lumix'te **Playwright**.

**Elasticsearch**
Açık kaynak full-text search engine. Lumix search ve log analytics için kullanır.

**Envelope Encryption**
Veri DEK ile şifrelenir, DEK ise KEK ile şifrelenir. KEK Vault'ta. DB compromise edilse bile veri açılamaz (KEK yoksa). Lumix PDR/sağlık verisi için kullanır.

**Event-Driven Architecture (EDA)**
Servislerin event'lerle iletişim kurduğu mimari. Lumix Kafka üzerinden EDA uygular.

**Eventual Consistency**
Sistem zamanla tutarlı hale gelir (anında değil). Async event'lerin doğal sonucu.

**Expand and Contract**
Sıfır kesintili schema değişim pattern'i. Önce yeni yapıyı ekle (expand), traffic geçtikten sonra eskiyi sil (contract).

**External Secrets Operator**
Vault gibi external secret manager'lardaki sırları Kubernetes Secret olarak senkronize eden controller.

## F

**Feature-Sliced Design (FSD)**
Frontend mimarisi metodolojisi: `app`, `pages`, `widgets`, `features`, `entities`, `shared` katmanları.

**Flyway**
Java ekosisteminde versiyonlu DB migration aracı. Lumix tüm schema değişimlerini Flyway ile yapar.

**Fully Stateful Auth**
Access + refresh + session — hepsinin Redis'te tutulduğu, tam revoke kontrolü olan auth modeli. Lumix'in seçimi.

## G

**GitOps**
Git'in cluster state'inin source-of-truth olduğu deployment modeli. Lumix ArgoCD ile uygular.

**Grafana**
Çoklu data source destekli dashboard aracı. Lumix observability stack'inde tek UI.

**gRPC**
HTTP/2 üzeri high-performance RPC framework. Protobuf ile çalışır. Lumix inter-service sync iletişimde kullanır.

## H

**HashiCorp Vault**
Açık kaynak secret manager + KMS. Lumix tüm sırları ve envelope encryption KEK'lerini burada tutar.

**Helm**
Kubernetes paket yöneticisi. Lumix her servis için Helm chart kullanır.

**Hexagonal Architecture (Ports & Adapters)**
İş mantığını dış dünyadan izole eden mimari. Adapter (REST/Kafka/gRPC) ile Core (domain + use case + port) ayrılır.

**HikariCP**
Java için yüksek performanslı connection pool. Spring Boot default.

**httpOnly Cookie**
JavaScript'ten erişilemez cookie. Lumix refresh token (ve opsiyonel access token) bu şekilde tutar — XSS koruması.

## I

**Idempotency**
Aynı işlemi birden fazla kez yapsan da sonuç değişmemesi. At-least-once delivery sistemlerinde zorunlu.

**Idempotency Key**
Client'ın aynı isteği tekrar gönderse bile sunucunun tek işlem yapmasını sağlayan header. Lumix payment endpoint'lerinde zorunlu.

**Ingress**
Kubernetes'te dış HTTP/HTTPS trafiğini cluster'a alan obje. Lumix **Traefik** kullanır.

**Installation**
Bir müşteri kurumunun tam Lumix kurulumu (kendi K8s, kendi DB, kendi Kafka). Örn. "Ömer Okulları".

**Integration Event**
Bounded context dışına yayınlanan, kararlı public sözleşme. Lumix'te Kafka topic'leri integration event taşır.

**Invariant**
DDD'de aggregate'in her zaman doğru olması gereken kural. Örn. "Sınıf kapasitesi aşılamaz".

## J

**Java 25**
Lumix backend dili (LTS, Eylül 2025). Virtual threads (Project Loom) ve structured concurrency stable; modern Java.

**JPA (Jakarta Persistence API)**
Java'da ORM standardı. Hibernate implementasyonu kullanılır.

**JWT (JSON Web Token)**
Imzalı, decode edilebilen token formatı. Lumix access token JWT (RS256). Stateless gibi durur ama Redis'te status tutulur.

**jti (JWT ID)**
JWT'nin unique identifier'ı. Revocation tracking için kullanılır.

## K

**Kafka**
Apache'in dağıtık event streaming platform'u. Lumix tek async broker.

**Karapace**
Apicurio gibi açık kaynak Schema Registry alternatifi. Lumix Apicurio'yu seçti.

**KEK (Key Encryption Key)**
DEK'i şifreleyen ana anahtar. Vault'ta. Envelope encryption'ın temeli.

**Keycloak**
Açık kaynak IAM/SSO çözümü. Lumix custom login default, müşteri istediğinde Keycloak aktif.

**Kong Gateway**
Açık kaynak API gateway. Plugin tabanlı (rate-limit, auth, transform).

**K3s**
Lightweight Kubernetes distribution. Self-host VPS'lere uygun. Lumix bunu kullanır.

**Kubernetes**
Container orchestration platform. Lumix tüm production deployment'larını K8s üzerinde çalıştırır.

**KVKK**
Kişisel Verilerin Korunması Kanunu (Türkiye). Lumix uyumluluk gereksinimi.

## L

**Legal Basis**
KVKK/GDPR'da bir veri işleme işleminin yasal dayanağı. "Sözleşmenin ifası", "meşru menfaat", "açık rıza" gibi. Lumix tek consent flag kullanmaz, purpose-based matrix.

**LGTM Stack**
Loki + Grafana + Tempo + Mimir (veya Prometheus + Thanos). Lumix observability yığını.

**Loki**
Grafana Labs'ın log aggregation aracı. Lumix log toplamada kullanır.

## M

**MDC (Mapped Diagnostic Context)**
Log framework'lerinde per-thread context. Lumix `correlation-id`, `tenant-id` MDC'de tutar.

**Microservice**
Bağımsız deploy edilebilen, kendi DB'sine sahip servis. Lumix mikroservice mimarisi kullanır.

**MJML**
Email HTML için cross-client uyumlu markup. Lumix email template engine.

**Modular Monolith**
Tek deployable içinde net sınırlı modüller. Lumix önce bunu düşündü, sonra microservice'e geçti.

**ModSecurity**
Web Application Firewall (WAF) modülü. Lumix Kong plugin olarak kullanır.

**mTLS (mutual TLS)**
İki tarafın da sertifika sunduğu TLS. Service-to-service trafiği şifrelemek için. Lumix ileride mesh ile gelirse.

**Multi-tenancy**
Tek sistemin birden fazla bağımsız müşteri/birime hizmet etmesi. Lumix iki seviyede uygular: installation + tenant.

## O

**OAuth 2.0 / OIDC**
Federated identity protokolleri. Keycloak entegrasyonunda kullanılır.

**OpenTelemetry (OTel)**
Standard observability framework. Trace + metric + log için vendor-neutral.

**Orchestration**
Bir merkezi koordinatörün diğer servislere komut verdiği pattern. Saga'da Temporal bu rolü oynar.

**Outbox Pattern**
DB write ile event publish'i atomic yapan pattern. Aynı transaction'da outbox tablosuna event yazılır, ayrı bir relay process Kafka'ya gönderir.

## P

**Pact**
Consumer-driven contract testing aracı. Lumix gRPC/event sözleşmelerini doğrular.

**PgBouncer**
PostgreSQL connection pooler (server-side). Transaction mode'da connection multiplexing.

**Permission**
Atomic yetki tanımı. Örn. `attendance:write`, `payment:refund`.

**PITR (Point-in-Time Recovery)**
WAL replay ile DB'yi belirli bir ana geri getirme. Lumix backup stratejisinin temeli.

**Playwright**
Microsoft'un E2E browser test aracı. Lumix RBAC UI testleri.

**Postgres / PostgreSQL**
Lumix'in tek RDBMS'i. Version 17.

**Pre-signed URL**
Object storage'a doğrudan upload/download yetkisi veren süresi sınırlı imzalı URL.

**Prometheus**
Metric collection ve TSDB. Lumix observability stack'inde metric tabanı.

**Promtail**
Loki'ye log gönderen agent.

**Protobuf (Protocol Buffers)**
Google'ın binary serialization formatı. gRPC + Lumix Kafka event schema'sı için kullanılır.

## Q

**Quartz**
Java'da scheduling library. Lumix Temporal'ı tercih etti, Quartz yerine.

## R

**RabbitMQ**
Mesaj broker. Lumix kullanmıyor (Kafka tek broker, WebSocket için Redis Pub/Sub).

**Rancher Manager**
SUSE'nin multi-cluster K8s yönetim aracı. Lumix müşteri cluster'larını buradan yönetir.

**Rate Limiting**
İstek hızını sınırlama. Lumix Kong + uygulama seviyesinde uygular.

**RBAC (Role-Based Access Control)**
Yetkinin rol üzerinden atandığı model. Lumix RBAC + ABAC hibriti.

**React Native**
React mantığıyla mobile native app. Lumix iOS + Android için kullanır.

**Read Replica**
Primary'den replicate olan, read-only PostgreSQL node'u. Lumix başlangıçta yok, gerekirse Patroni ile streaming replication.

**Redis**
In-memory data store + cache + pub/sub + lock. Lumix iki ayrı Sentinel cluster çalıştırır (auth + cache).

**Redis Sentinel**
Redis HA topology. Master + replica + sentinel quorum ile failover.

**Refresh Token**
Yeni access token üretmek için kullanılan, uzun ömürlü token. Lumix httpOnly cookie'de, SHA-512 hash'li Redis'te.

**Replication Lag**
Primary ile replica arasında veri farkının kapanma gecikmesi.

**Retry**
Geçici hataları tolere etmek için işlemi tekrar etmek. Exponential backoff + jitter standart.

**RLS (Row-Level Security)**
PostgreSQL'in satır seviyesinde policy uygulama özelliği. Lumix tenant izolasyonu için.

**Rotation**
Token, secret veya key'in periyodik değiştirilmesi.

**RPO (Recovery Point Objective)**
Maksimum kabul edilebilir veri kaybı (zaman birimi). Lumix hedefi 15 dakika.

**RTO (Recovery Time Objective)**
Maksimum kabul edilebilir kurtarma süresi. Lumix hedefi 2 saat.

**RustFS**
Rust ile yazılmış S3-compatible object storage. Lumix self-host kullanır.

**RTK Query**
Redux Toolkit'in server state cache + invalidation API'si. Lumix frontend server state için kullanır.

## S

**Saga**
Multi-service distributed transaction pattern. Her adım kendi DB'sinde commit, hata olursa compensation event'leri ile geri al. Lumix Temporal'da implement eder.

**Schema Registry**
Event schema'larını merkezi tutan ve compatibility kontrol eden servis. Lumix **Apicurio**.

**Scope**
Bir kullanıcının tenant içinde görebileceği veri kapsamı. School / class / student seviyelerinde.

**ScopeResolver**
Bir kullanıcının effective scope'unu hesaplayan komponent.

**Service Mesh**
Service-to-service trafiği yöneten infrastructure katmanı (Istio, Linkerd). Lumix kullanmıyor (başlangıçta).

**Session**
Server-side authenticated context. Lumix Redis'te `session:{id}`.

**SHA-512**
Lumix refresh token at-rest hash algoritması.

**Snapshot**
DDD'de aggregate'in belirli bir andaki durumunun kopyalanıp saklanması (Order'a o anki adres bilgisinin gömülmesi gibi).

**Smart Navigation**
Lumix frontend'inde URL ID görünürlük kararının merkezi olarak uygulandığı component.

**STOMP (Simple Text-Oriented Messaging Protocol)**
WebSocket üzerine bindirilen mesajlaşma protokolü. Subscription, send, message semantiği ekler.

## T

**Talos Linux**
K8s-only, immutable Linux distro. Lumix VPS senaryosunda Talos yerine **Ubuntu** seçti.

**Tempo**
Grafana Labs'ın trace storage'ı. Lumix OpenTelemetry trace'lerini burada tutar.

**Temporal**
Workflow orchestration platform. Saga, retry, compensation, scheduled job için tek araç.

**Tenant**
Installation içindeki bağımsız operasyonel birim. Örn. "Ömer Okulları → Kadıköy Şubesi". `tenant_id` UUID v7.

**Terraform**
HashiCorp'un IaC aracı. Lumix VPS provisioning + sağlayıcı resource'ları için kullanır.

**Testcontainers**
Test'lerde gerçek container (PostgreSQL, Kafka, Redis) ayağa kaldıran kütüphane.

**Thanos**
Prometheus uzun süreli storage çözümü. Object storage backed.

**Tilt**
Local development orchestrator. K8s + hot reload + log toplama. Lumix dev'ler bunu kullanır.

**Traefik**
K8s-native ingress controller. Lumix tüm cluster'larında ingress.

**Trivy**
Container image vulnerability scanner. Lumix GitLab CI gate.

## U

**Ubuntu Server 24.04 LTS**
Lumix VPS işletim sistemi. 2034'e kadar destek.

**UUID v7**
Time-ordered UUID. Lumix yeni entity'ler için bunu kullanır.

## V

**Velero**
Kubernetes cluster backup tool. etcd + PV snapshot.

**Vault (HashiCorp)**
Lumix secret + KMS.

**Vite**
Lumix frontend build tool. Hızlı dev server, modern bundling.

## W

**WAF (Web Application Firewall)**
HTTP isteklerinde Layer 7 koruma (OWASP CRS). Lumix ModSecurity Kong plugin ile.

**WAL (Write-Ahead Log)**
PostgreSQL'in tüm değişiklikleri yazdığı log. PITR'ın temeli.

**WebSocket**
Long-lived, bidirectional TCP-üzeri protokol. Lumix real-time için STOMP ile birleştirip kullanır.

**Write Primary**
PostgreSQL'in authoritative write node'u. Read replica'nın karşıtı.

---

## Sözlüğe nasıl katkıda bulunulur?

1. Yeni teknik terim kullanacaksan **önce buraya ekle**.
2. Tanımı **kısa ve tek paragraf** tut.
3. Lumix-spesifik kullanımı belirt (varsa).
4. Tanım alfabe sırasına ekle, başlık `## A`, `## B` formatını koru.
5. Doc'larda terimi tutarlı yazımla kullan (bkz. yazım kuralları altta).

## Yazım kuralları (terim yazımı)

| Doğru | Yanlış |
|---|---|
| `tenant_id` | `tenantId`, `tenant-id` (kavram olarak `tenant-id` da geçer, kolon olarak `tenant_id`) |
| `role_permission` | `role-permission`, `RolePermission` |
| `audit log` | `auditlog`, `Audit-Log` |
| `RLS policy` | `rls policy`, `Rls Policy` |
| `DSAR workflow` | `dsar workflow` |

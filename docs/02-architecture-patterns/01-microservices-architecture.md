---
title: Microservices Architecture
description: Lumix'in microservice mimari kararı, modüler monolit ile farkı, 10 servisin sınırları ve kabul edilen trade-off'lar.
sidebar_position: 1
---

## Bu sayfa ne anlatıyor?

Bu sayfa Lumix'in **neden microservice** mimarisini seçtiğini, **alternatifi olan modüler monolit** ile arasındaki farkları, **10 domain servisinin** nasıl sınırlandığını ve bu kararın getirdiği **operasyonel maliyeti** anlatıyor. Yeni gelen geliştirici buradan çıkınca "her servisin neden ayrı olduğunu ve birleştirmenin neden istenmediğini" söyleyebilmeli. Hem giriş seviyesi hem mimari karar referansıdır.

## 1. Bu nedir? (Sıfırdan)

**Microservice mimarisi**, bir sistemi tek bir büyük uygulama (monolith) yerine, **bağımsız deploy edilebilen, kendi veritabanına sahip, sözleşme (API + event) üzerinden konuşan küçük servislere** bölme yaklaşımıdır.

**Günlük hayattan analoji:**
Büyük bir restoranı düşün. İki seçeneğin var:

- **Seçenek 1 — Tek mutfak (monolith):** Bütün şefler aynı mutfakta, aynı malzemeleri ve aynı ocağı kullanır. Hızlı koordinasyon, ama bir şef hata yaparsa diğerleri de etkilenir. Mutfağı tadilata kapatırsan tüm restoran kapanır.
- **Seçenek 2 — Bağımsız istasyonlar (microservice):** Her istasyon (sıcak, soğuk, tatlı, içecek) kendi ocağı, kendi soğutucusu, kendi şefi olan ayrı bir birim. Aralarında garson (API çağrısı) veya pano (event/Kafka) ile haberleşirler. Soğuk istasyonun ocağı arızalanırsa diğerleri çalışmaya devam eder.

Microservice ikinci modeldir. Her servis **kendi kararlarını kendi verir**, **kendi ritminde release eder**, ve dışarıya açık olan **yalnızca sözleşmedir** (API + event şeması).

**Microservice'i tanımlayan beş temel özellik:**

1. **Bağımsız deploy:** Bir servisi yeni versiyonuyla deploy etmek diğer servisleri etkilemez.
2. **DB-per-service:** Her servisin kendi veritabanı vardır; başka bir servis bu DB'ye doğrudan bağlanmaz.
3. **Sözleşmeli iletişim:** İletişim sadece açıkça tanımlanmış API'lar veya event'ler üzerinden olur (Lumix'te gRPC + Protobuf ve Kafka + Protobuf).
4. **Bounded context:** Her servis tek bir iş kabiliyetinin sahibidir; aynı kavram başka servislerde farklı anlama gelebilir.
5. **Bağımsız teknoloji seçimi (teorik):** Her servis kendi dilini, framework'ünü, DB tipini seçebilir. (Lumix'te pratikte hepsi Spring Boot + Java 25 + PostgreSQL — homojen yığın tercih edildi.)

## 2. Hangi problemi çözüyor?

Monolith başlangıçta hızlıdır ama büyüdükçe şu **somut acılar** ortaya çıkar:

**Acı 1 — Deploy korkusu.**
Tek bir küçük bug fix için tüm sistemin yeniden deploy edilmesi gerekir. 2 milyon satır kodu olan bir monolith'i Cuma 17:00'de deploy etmek isteyen bir takım yoktur. Sonuç: deploy haftalığa düşer, batch büyür, risk büyür.

**Acı 2 — Tek bir bug bütün sistemi düşürür.**
Yoklama servisinde memory leak varsa, ödeme servisi de aynı JVM içinde olduğu için onunla beraber çöker. "Pazartesi ödemeler çalışmıyor" hatası, sebebi "yoklama tarafında bir geliştirici yeni eklediği reporting fonksiyonunda OutOfMemory üretti" olabilir.

**Acı 3 — Ölçeklendirme havuç–lahana.**
Sınav notlarının açıklandığı an `assessment` modülü 1000x trafik alır. Monolith'te tüm uygulamayı 10x replica ile ölçeklemek zorundasın — `notification`, `audit`, `counseling` da boş yere replike olur. CPU/RAM israfı.

**Acı 4 — Modül sınırı yıkılır.**
"Geçici çözüm" diye `assessment` modülü doğrudan `academic` modülünün entity'sine `JOIN` atar. 6 ay sonra bu join 50 yere yayılmıştır; artık iki modülü ayrı release edemezsin, ayrı yazamazsın, ayrı test edemezsin. Bu "distributed monolith"in tersi: **modüler olmayan monolith**.

**Acı 5 — Takımlar birbirini bekler.**
Frontend takımı, finance takımı, identity takımı aynı repo + aynı CI pipeline'da çalışınca; her commit diğerlerinin testlerini çalıştırmak zorunda kalır. Build süreleri 30dk-1saat. Code review kuyruğu uzar.

**Acı 6 — Müşteri başına farklı versiyon imkansız.**
Lumix'te bazı müşteriler "biz finance modülünü kullanmayacağız" der. Monolith'te onu kapatamazsın, sadece UI'da gizleyebilirsin — kod, DB tabloları, bağımlılıkları hepsi orada durur.

Microservice bu altı acıyı şu şekilde çözer:

| Acı | Microservice çözümü |
|---|---|
| Deploy korkusu | Her servis bağımsız deploy edilir, sadece o servisin testleri çalışır |
| Tek bug = sistem çöker | Servis A çöktüğünde B, C, D çalışmaya devam eder (graceful degradation) |
| Ölçeklendirme havuç–lahana | Sadece yüksek yük alan servis (assessment) ölçeklenir |
| Modül sınırı yıkılır | DB ayrı + ağ sınırı = teknik olarak join imkansız, sözleşme zorunlu |
| Takımlar birbirini bekler | Her takım kendi servisinin sahibi, kendi CI'ı, kendi release ritmi |
| Müşteri başına farklı versiyon | Modülü kapamak = ilgili servisin Helm chart'ını deploy etmemek |

## 3. Nasıl çözüyor? (Çalışma prensibi)

Microservice mimarisi şu temel ilkeler üzerinden çalışır:

### 3.1. Bounded Context = Servis Sınırı

Her servis, **Domain-Driven Design**'ın bounded context kavramına denk gelir. Bir bounded context içinde:
- Kavramlar **tek anlamlıdır** (`user` identity'de bir şey, `student` academic'te başka bir şey)
- **Ubiquitous language** geçerlidir (kod ile iş dili aynı kelime)
- Dış dünyaya açılan **public contract** vardır (gRPC API + Kafka topic)

Lumix'te servis = bounded context kuralı katıdır. Detay için bakınız: [Domain-Driven Design](./domain-driven-design).

### 3.2. DB-per-service

Her servisin **kendi PostgreSQL veritabanı** vardır. Bu basit kuralın sonuçları çok derindir:

- Servisler arası **doğrudan SQL join yapılamaz** → veriyi başka servisten almak için **gRPC çağrısı** veya **event subscription** lazım.
- Her servis kendi schema'sını **bağımsız değiştirebilir** (Flyway migration).
- Şifreleme/encryption stratejisi servis bazlı (counseling-service'te envelope encryption, finance-service'te yok).

### 3.3. Sözleşmeli iletişim — iki kanal

Lumix'te servisler iki kanal üzerinden konuşur:

```
┌──────────────────────────────────────────────────────────────┐
│                    Sync Channel: gRPC                         │
│                                                               │
│   [academic-svc] ───── GetClass(class_id) ────► [org-svc]    │
│                ◄──── ClassDto (Protobuf) ──────              │
│                                                               │
│   Kullanım: "şu anda cevabı lazım" sorgular                  │
│   Trade-off: çağıran taraf çağrılana bağımlı, latency artar  │
└──────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────┐
│                  Async Channel: Kafka                         │
│                                                               │
│   [academic-svc] ──► topic:academic.attendance.marked.v1 ──► │
│                                                               │
│              ┌─────────┬─────────┬──────────┐                │
│              ▼         ▼         ▼          ▼                 │
│        [notification][performance][audit][search]            │
│                                                               │
│   Kullanım: "olan oldu, bilgilendirme" — side effect         │
│   Trade-off: eventual consistency, idempotency zorunlu       │
└──────────────────────────────────────────────────────────────┘
```

**Senkron kanal (gRPC):**
- Hızlı, sıkı schema, code-gen
- İki taraf eş zamanlı erişilebilir olmalı
- Hata anında devre kesici (circuit breaker) lazım

**Asenkron kanal (Kafka):**
- Loose coupling — publisher subscriber'ı tanımaz
- At-least-once delivery → consumer idempotent olmalı
- Schema değişimi için **BACKWARD compatibility** zorunlu

Detaylar: [gRPC Service Communication](../03-backend/03-grpc-service-communication), [Event-Driven Architecture](./event-driven-architecture).

### 3.4. Bir feature talebinin yolculuğu

"Öğretmen yoklama girince velinin telefonuna SMS gitsin" feature'ı üç servisi etkiler:

```
1. Frontend ─POST─► academic-service
2. academic-service:
   - DB INSERT attendance
   - DB INSERT outbox_event (AttendanceMarkedV1)
   - COMMIT
3. Outbox Relay ──► Kafka topic: academic.attendance.marked.v1
4. notification-service consumer:
   - Event'i al
   - organization-service'e gRPC: GetParentPhone(student_id)
   - SMS provider'a gönder
   - notification_logs INSERT
```

Bu akışta:
- `academic-service` veliye SMS gittiğini bilmez (loose coupling)
- `notification-service` yoklamanın nasıl alındığını bilmez (sözleşmeli iletişim)
- İki servis bağımsız deploy edilebilir

### 3.5. Bağımsız deploy mantığı

Her servisin kendi Helm chart'ı, kendi GitLab CI pipeline'ı, kendi semantic version'ı vardır:

```
academic-service     v2.3.1  ── canary 5% → 50% → 100%
notification-service v1.8.4  ── stable
identity-service     v4.0.0  ── ana versiyon değişikliği, dikkatli rollout
```

Bir servis deploy edildiğinde diğerleri etkilenmez **ama** breaking change yapılırsa diğerleri bozulur — bunu önlemek için **schema compatibility kuralları** (BACKWARD) ve **contract testing** (Pact) zorunludur.

## 4. Biz projemizde nasıl kullanıyoruz?

### 4.1. Servis listesi (10 ana domain + 2 cross-cutting)

| # | Servis | Sorumluluk | Tipik yük |
|---|---|---|---|
| 1 | `identity-service` | Auth, user, RBAC, session | Sürekli yüksek (her istek validate) |
| 2 | `organization-service` | Installation, tenant, sınıf hiyerarşisi | Orta — okuma ağırlıklı |
| 3 | `academic-service` | Müfredat, yoklama, ödev | Peak saatte yüksek (sabah 08:00 yoklama) |
| 4 | `assessment-service` | Sınav, not, karne | Dönem sonu peak |
| 5 | `counseling-service` | PDR — KVKK özel kategori | Düşük ama yüksek hassasiyet |
| 6 | `performance-service` | Performans, gözlem, hedef | Orta, batch-ağırlıklı |
| 7 | `communication-service` | Mesajlaşma, duyuru | Sürekli yüksek (WebSocket fan-out) |
| 8 | `finance-service` | Fatura, ödeme, iade | Ay başı + saga workflows |
| 9 | `file-service` | Dosya metadata + RustFS adapter | Upload/download'a göre değişken |
| 10 | `audit-service` | Tüm kritik event consumer, append-only | Sürekli (her aksiyon log'lanır) |
| + | `compliance-service` | DSAR, retention, anonymization | Düşük (workflow tetikli) |
| + | `notification-service` | Email/SMS/Push provider adapter | Event-tetikli, burst |

Detay: [Domain Servisleri](../01-tenancy-and-domain-model/02-domain-services-overview).

### 4.2. Lumix'te microservice kuralları

**Kural 1 — DB izolasyonu mutlak.** Bir servis başka servisin DB'sine **asla** bağlanmaz. Bağlanmak istiyorsa: gRPC çağrısı veya event subscription. CI'da bu kural otomatik kontrol edilir (linter, network policy).

**Kural 2 — Shared library yok.** İki servisin aynı kod parçasına ihtiyacı olursa, **duplicate** edilir. Shared `common-lib` veya `lumix-core` paketi yok. Sebep: shared lib = gizli coupling. Detay: [Teknoloji Kararları — Mimari Paradigma](../00-overview/02-technology-stack-decisions).

**Kural 3 — Tek dil, tek framework.** Hepsi Java 25 + Spring Boot 3.6. Polyglot serbestliğinden vazgeçtik. Sebep: operasyonel basitlik, CI/CD homojen, ekip eğitimi tek yığın.

**Kural 4 — Sync iletişim sadece gerçekten gerekiyorsa.** Çağrı zinciri (servis A → B → C → D) **sync olarak yapılmaz** — performans + cascading failure riski. Mümkünse event-driven.

**Kural 5 — Her servis kendi Helm chart'ı + ApplicationSet.** ArgoCD'nin ApplicationSet özelliği ile aynı manifest çoklu müşteri cluster'ına deploy olur.

**Kural 6 — Her servis kendi observability sinyallerini üretir.** Prometheus scrape endpoint, structured log, OTel trace. `correlation-id` ve `tenant-id` MDC'de propagate edilir.

### 4.3. Servis sayısı seçimi

"Neden 10? Neden 5 değil, neden 20 değil?" sorusunun cevabı:

- **Daha az olsaydı (örn. 4 servis):** identity + academic+assessment+performance birleşik + finance + audit. Sorun: KVKK özel kategori (PDR) ile sıradan veri aynı DB'de olur, izolasyon zayıflar. Birleşik servis çok büyür, ekip boyutu büyüdüğünde tekrar bölmek zorunda kalırız.
- **Daha çok olsaydı (örn. 20 servis):** organization üç parçaya, academic üç parçaya... Sorun: ekip boyutuna göre çok fazla operasyonel overhead. Çoğu zaman birlikte değişen kavramlar ayrı servise düşerse cross-service çağrı patlar.

10 sayısı, **iş kabiliyetlerini doğal sınırlarında** bölmenin sonucu — kasten seçilmiş bir sayı değil.

### 4.4. Bir microservice'in iç yapısı

Her servis aynı şablonu izler:

```
identity-service/
├── src/main/java/com/lumix/identity/
│   ├── adapter/
│   │   ├── in/
│   │   │   ├── rest/          # HTTP controller'lar
│   │   │   ├── grpc/          # gRPC service implementation
│   │   │   └── kafka/         # Kafka consumer'lar
│   │   └── out/
│   │       ├── persistence/   # JPA repository implementations
│   │       ├── grpc/          # diğer servislere gRPC client
│   │       └── kafka/         # Kafka producer'lar
│   ├── application/
│   │   ├── port/in/           # use case interface'leri
│   │   ├── port/out/          # dış bağımlılık interface'leri
│   │   └── service/           # use case implementation'ları
│   └── domain/
│       ├── model/             # aggregate, entity, value object
│       └── event/             # domain event'ler
├── src/main/resources/
│   ├── application.yml
│   ├── application-dev.yml
│   ├── application-prod.yml
│   ├── db/migration/          # Flyway scripts
│   └── proto/                 # .proto schema'ları
├── helm/                       # service-specific Helm chart
├── Dockerfile
└── pom.xml
```

Bu yapı **Hexagonal Architecture**'a denk düşer. Detay: [Hexagonal Architecture](./hexagonal-architecture).

## 5. Neden bu seçildi? (Alternatifler ve trade-off)

### 5.1. Düşünülen alternatifler

**Alternatif 1 — Modular Monolith**
Tek deployable, içinde net sınırlı modüller (Spring Modulith ile). Avantajları:
- Daha az operasyonel karmaşıklık (tek deploy unit'i, tek DB)
- Daha az ağ overhead'i (in-process call)
- Distributed transaction problemi yok

Niye elendi:
- Lumix **multi-installation** (her müşteri kendi cluster'ı) modelinde, müşteri başına farklı modül kapatma talebi var. Monolith'te modül kapatmak için derin refactor gerekir.
- Ekip büyüdükçe (3 ekipten 8 ekibe) tek codebase'te bağımsız geliştirme yapamaz hale geliriz.
- Yoklama peak'inde sadece academic'i scale etmek mümkün değil.
- KVKK özel kategori veri (PDR) için ayrı izolasyon istenirken aynı uygulama içinde tutmak güven sorunu yaratır.

**Alternatif 2 — Çok daha büyük servisler (3-4 servis)**
"Identity + organization + academic + audit". Yani 4 büyük servis, her biri içinde birçok modül.

Niye elendi:
- Servis sınırı belirsiz → distributed monolith riski yüksek
- Her servis çok büyük olunca ekip içinde de coupling yüksek

**Alternatif 3 — Çok daha küçük servisler (nano-services)**
"User-create-service", "user-update-service", "user-query-service" gibi her use case bir servis.

Niye elendi:
- Operasyonel overhead patlar (50+ servis)
- Cross-service çağrı zinciri uzar, latency birikir
- Ekip boyutumuz bunu kaldırmaz

### 5.2. Kabul edilen trade-off'lar

Microservice "ücretsiz" değil. Şunları kabul ettik:

| Trade-off | Maliyet | Mitigation |
|---|---|---|
| **Distributed transaction yok** | Atomicity garantisi zayıflar | Saga pattern (Temporal) + Outbox pattern |
| **Network latency** | Her gRPC çağrısı milisaniyeler ekler | gRPC + Protobuf seçildi (REST'e göre 2-5x hızlı) |
| **Operational overhead** | 12 servis = 12 monitoring + 12 deploy pipeline | Helm + ArgoCD GitOps + ortak observability stack |
| **Eventual consistency** | Veri her yerde aynı anda güncel olmayabilir | Domain dilinde açıkça "eventual" alanları belirt |
| **Cross-service test zor** | Integration test karmaşıklaşır | Testcontainers (gerçek deps) + Pact (contract test) |
| **Cascading failure riski** | A çöker → B'nin A'ya bağımlı endpoint'i çöker | Circuit breaker (Resilience4j), timeout, retry |
| **Cross-service debugging zor** | "Hangi servis kırdı?" sorusu zor | Distributed tracing (OpenTelemetry + Tempo) + correlation-id |
| **Schema evolution dikkat ister** | Producer schema'sı değişirse consumer'lar kırılabilir | Apicurio Registry + BACKWARD compatibility + Pact |

### 5.3. Ne zaman bu kararı tekrar gözden geçirmeli?

- Eğer ekibimiz **kalıcı olarak 5 kişi altında** kalırsa: 12 servisin operasyonel yükü orantısız olabilir, modular monolith'e dönmek mantıklı olabilir.
- Eğer **müşteri başına farklılaştırma talebi düşerse** ve tüm müşteriler aynı modülleri kullanıyorsa, izole deploy faydası azalır.
- Eğer **eventual consistency müşteriyi yoruyorsa** ve "yoklama veliye 5dk sonra ulaşıyor" şikayeti çoğalırsa: bazı sınırları yeniden çizmek gerekebilir.

Karar **revize edilebilir** ama **default microservice**.

## 6. Pratik örnek

### 6.1. Bir servis için minimal Spring Boot ana sınıfı

```java
// academic-service/src/main/java/com/lumix/academic/AcademicServiceApplication.java
package com.lumix.academic;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableAsync
@EnableScheduling
public class AcademicServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(AcademicServiceApplication.class, args);
    }
}
```

### 6.2. application.yml — servis identification

```yaml
spring:
  application:
    name: academic-service
  profiles:
    active: ${SPRING_PROFILES_ACTIVE:dev}

  datasource:
    url: jdbc:postgresql://${DB_HOST}:5432/academic_db
    username: ${DB_USER}
    password: ${DB_PASSWORD}

  kafka:
    bootstrap-servers: ${KAFKA_BROKERS}
    properties:
      schema.registry.url: ${SCHEMA_REGISTRY_URL}

server:
  port: 8080

grpc:
  server:
    port: 9090

management:
  endpoints:
    web:
      exposure:
        include: health, info, prometheus
  metrics:
    tags:
      service: academic-service

logging:
  pattern:
    level: "%X{correlationId:-} %X{tenantId:-} %5p"
```

### 6.3. Helm chart yapısı

```yaml
# academic-service/helm/values.yaml
replicaCount: 2

image:
  repository: registry.lumix.io/academic-service
  tag: "2.3.1"
  pullPolicy: IfNotPresent

resources:
  requests:
    memory: "512Mi"
    cpu: "200m"
  limits:
    memory: "1Gi"
    cpu: "1000m"

autoscaling:
  enabled: true
  minReplicas: 2
  maxReplicas: 10
  targetCPUUtilizationPercentage: 70

env:
  - name: SPRING_PROFILES_ACTIVE
    value: prod
  - name: DB_HOST
    valueFrom:
      secretKeyRef:
        name: academic-db-credentials
        key: host
```

### 6.4. Cross-service gRPC çağrı

```java
// academic-service'in organization-service'i çağırması
@Service
@RequiredArgsConstructor
public class ClassValidationService {

    @GrpcClient("organization-service")
    private OrganizationServiceGrpc.OrganizationServiceBlockingStub orgStub;

    public ClassInfo getClass(UUID classId) {
        try {
            GetClassRequest request = GetClassRequest.newBuilder()
                .setClassId(classId.toString())
                .build();

            GetClassResponse response = orgStub
                .withDeadlineAfter(500, TimeUnit.MILLISECONDS)
                .getClass(request);

            return ClassInfo.fromProto(response.getClassInfo());
        } catch (StatusRuntimeException ex) {
            if (ex.getStatus().getCode() == Status.Code.NOT_FOUND) {
                throw new ClassNotFoundException(classId);
            }
            throw new CrossServiceException("organization-service çağrısı başarısız", ex);
        }
    }
}
```

### 6.5. Outbox event ile loose coupling

```java
@Service
@RequiredArgsConstructor
@Transactional
public class MarkAttendanceUseCase {

    private final AttendanceRepository attendanceRepo;
    private final OutboxEventRepository outboxRepo;

    public void execute(MarkAttendanceCommand cmd) {
        // 1. Domain operation
        Attendance attendance = Attendance.mark(cmd.classId(), cmd.date(), cmd.studentMarks());
        attendanceRepo.save(attendance);

        // 2. Outbox event (SAME TRANSACTION)
        OutboxEvent event = OutboxEvent.create(
            "academic.attendance.marked.v1",
            attendance.getId().toString(),
            AttendanceMarkedV1.from(attendance).toByteArray()
        );
        outboxRepo.save(event);

        // 3. COMMIT — atomicity garantili
        // Outbox Relay (background) Kafka'ya gönderecek
    }
}
```

## 7. Dikkat edilecek tuzaklar

**Tuzak 1 — Distributed Monolith.**
Servisleri ayırırsın ama aralarında sync çağrı zinciri kurarsın: A → B → C → D. Tek bir servis çökerse zincir kırılır. Aslında monolith yapısının dağıtık versiyonunu yaratmış olursun — tüm dezavantajlar, hiçbir avantaj.
**Önleme:** Sync zincir 2 hop'u geçmesin. Geçmek zorundaysa event-driven flow'a dönüştür.

**Tuzak 2 — Shared Database.**
"Geçici olarak aynı DB'yi paylaşalım, sonra bölünür" der ve bölünmez. İki servis aynı tabloya yazınca artık bağımsız değiller.
**Önleme:** Day-1'den itibaren DB-per-service. Network seviyesinde block et (DB user sadece kendi DB'sine ACL'li).

**Tuzak 3 — Shared Library Bağımlılığı.**
"Common DTO library", "common utils library" gibi paketler oluşturulur ve her servise dependency olur. Common lib v1.5 çıktığında 10 servis aynı anda upgrade edilmek zorunda kalır. Bağımsız deploy ölür.
**Önleme:** Lumix'te kabul edilen kural: **shared library yok**. Duplicate kabul edildi.

**Tuzak 4 — Synchronous Communication Aşırı Kullanımı.**
Her şey gRPC ile çağrılır. Bir kullanıcı isteğinin arkasında 7 sync çağrı vardır. Bir servis 200ms gecikse, frontend 1.5 saniye bekler. Tek nokta sıkıntısı.
**Önleme:** Sync sadece "şu anda cevap lazım" senaryolarda. Diğer her şey async event.

**Tuzak 5 — Transactionality Hatası.**
"DB'ye yaz + Kafka'ya event publish et" iki ayrı işlemdir. Birincisi başarılı ikincisi başarısız olursa veri tutarsızlığı doğar.
**Önleme:** Outbox pattern. Detay: [Outbox Pattern](./outbox-pattern).

**Tuzak 6 — Test Stratejisini Yanlış Kurmak.**
"Tüm 12 servisi ayağa kaldırıp end-to-end test yapalım" denir. Test 45 dakika sürer, flaky olur, CI'da çoğu zaman fail eder.
**Önleme:** Test piramidi: unit test (çok), integration test (Testcontainers ile servis bazında), contract test (Pact), E2E test (sadece kritik happy path).

**Tuzak 7 — Versioning'i Atlamak.**
Kafka topic'i `academic.attendance.marked` olarak isimlendirilir. Schema değişince consumer'lar kırılır.
**Önleme:** Topic ismine versiyon koy: `academic.attendance.marked.v1`. Breaking change → yeni `v2` topic, eski paralel çalışır (expand/contract).

**Tuzak 8 — Tek Bir Servisin Veri Sahibi Olmaması.**
Aynı kavramı iki servis de "biz sahibiyiz" der. `user` hem identity'de hem organization'da yazılır. Hangisi master?
**Önleme:** Her veri için **tek bir sahip servis**. Diğerleri ya event ile haberdar olur, ya da gRPC ile sorgular.

**Tuzak 9 — Monitoring + Tracing Eksikliği.**
12 servis var ama distributed trace yok. Bir bug'ı debug etmek için 12 farklı log'a bakman gerekiyor.
**Önleme:** Day-1'de OpenTelemetry + Tempo. `correlation-id` her yerde.

**Tuzak 10 — Erken Microservice.**
"Bir gün büyürüz" diye 3 kişilik ekip 8 servis kurar. Her gün 8 deploy, 8 monitoring, 8 alert kanalı. Geliştirme yavaşlar.
**Önleme:** Ekip boyutu ve karmaşıklık seviyesi microservice'i hak ediyor mu? Lumix'te ediyor (multi-installation + KVKK + ekip ölçeği). Küçük SaaS için modular monolith genelde daha doğru.

## 8. Diğer konularla ilişkisi

- [Domain-Driven Design](./domain-driven-design) — servis sınırlarını nasıl çiziyoruz?
- [Hexagonal Architecture](./hexagonal-architecture) — her servisin iç yapısı
- [Event-Driven Architecture](./event-driven-architecture) — async iletişim modeli
- [Saga Pattern](./saga-pattern) — distributed transaction çözümü
- [Outbox Pattern](./outbox-pattern) — atomic write + event publish
- [Domain Servisleri — 10 Microservice](../01-tenancy-and-domain-model/02-domain-services-overview) — Lumix servislerinin tek tek listesi
- [gRPC Service Communication](../03-backend/03-grpc-service-communication) — sync iletişim detayı
- [Genel Mimari](../00-overview/03-overall-architecture) — kuş bakışı resim

## 9. Daha derine inmek için

**Resmi ve referans kaynaklar:**
- "Building Microservices" — Sam Newman (2nd Edition, 2021) — sektör standardı kitap
- "Microservices Patterns" — Chris Richardson — Saga, Outbox, CQRS detayları
- "Implementing Domain-Driven Design" — Vaughn Vernon — bounded context
- microservices.io — Chris Richardson'ın pattern catalog'u
- martinfowler.com/microservices — Martin Fowler'ın makaleleri

**Spring ekosistemi:**
- Spring Cloud — service discovery, config server (Lumix'te Kubernetes-native discovery kullandığımız için Spring Cloud Discovery yok)
- Spring Cloud OpenFeign — declarative REST client (Lumix gRPC tercih ediyor)
- Spring Boot Actuator — health/metric endpoints

**Search keywords (İngilizce):**
- "microservices vs modular monolith"
- "database per service pattern"
- "bounded context microservices"
- "distributed monolith antipattern"
- "service granularity microservices"
- "microservices size sweet spot"

## 10. Sözlük

- **Bounded Context** — DDD'de bir modelin/dilin geçerli olduğu sınır. Lumix'te microservice ≈ bounded context.
- **DB-per-service** — Her microservice'in kendi PostgreSQL DB'sine sahip olması. Cross-service join yasak.
- **Distributed Monolith** — Servisler ayrılmış ama aralarında sıkı coupling olan anti-pattern.
- **Modular Monolith** — Tek deployable içinde net sınırlı modüller (Spring Modulith). Microservice alternatifi.
- **Integration Event** — Bounded context dışına yayınlanan public sözleşme. Lumix'te Kafka topic'leri.
- **Domain Event** — Bounded context içinde olan, iş anlamı taşıyan olay. Internal, doğrudan Kafka'ya gitmez.
- **Outbox Pattern** — DB write ile event publish'i atomic yapan pattern. Detay sayfası var.
- **Saga** — Multi-service distributed transaction pattern.
- **Bağımsız Deploy** — Bir servisi diğerlerinden ayrı release edebilme yeteneği.
- **Eventual Consistency** — Sistem zamanla tutarlı hale gelir, anında değil. Async event'lerin doğal sonucu.
- **Cascading Failure** — Bir servisin çökmesinin diğerlerini de çökertmesi. Circuit breaker ile önlenir.
- **Polyglot Persistence** — Her servisin farklı tip DB seçebilmesi. Lumix bunu kullanmıyor (hep PostgreSQL).

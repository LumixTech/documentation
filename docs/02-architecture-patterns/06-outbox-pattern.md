---
title: Outbox Pattern
description: Dual-write problemi, transactional outbox tablosu, relay process, Debezium vs custom relay ve Lumix'te outbox implementasyonu.
sidebar_position: 6
---

## Bu sayfa ne anlatıyor?

Bu sayfa **dual-write problemini** — yani "DB'ye yaz + event yayınla aynı anda nasıl güvenli olur" sorusunu — ve **Outbox Pattern**'in nasıl bu problemi çözdüğünü anlatıyor. Lumix her servisin yazma yolunda outbox kullanır; bu sayfa o kararın gerekçesini, tablo şemasını, relay süreci tasarımını ve Debezium vs custom relay seçimini detaylandırıyor. Sonunda okuyan biri kendi servisine outbox eklemek için ihtiyacı olan her şeyi bulmuş olmalı.

## 1. Bu nedir? (Sıfırdan)

### Dual-write problemi

Microservice'lerde sürekli karşına çıkan basit görünüp tehlikeli olan senaryo:

```java
@Transactional
public void markAttendance(...) {
    Attendance a = ...;
    attendanceRepo.save(a);                   // ← DB write
    kafkaTemplate.send("attendance.marked", event);  // ← Kafka publish
}
```

Burada **iki ayrı sistem**e yazıyorsun: PostgreSQL + Kafka. İkisinden biri başarısız olursa veri tutarsızlığı doğar:

| Senaryo | Sonuç |
|---|---|
| ✓ DB başarılı, ✓ Kafka başarılı | İyi durum (tipik) |
| ✗ DB başarısız | Hiçbir şey olmadı (`@Transactional` rollback yaptı, Kafka'ya hiç gitmedi varsayımı) |
| ✓ DB başarılı, ✗ Kafka başarısız | **Sessiz veri kaybı**: yoklama alındı ama veliye haber yok |
| ✓ DB başarılı, ✓ Kafka başarılı ama transaction sonrası işlemci crash | DB commit, Kafka send arasında race condition |

Bu **dual-write problem** çözülemez — çünkü iki ayrı sistem arasında **distributed transaction** yok ([Saga Pattern](./saga-pattern) sayfasında detayı var).

**Çözüm:** Tek bir sisteme yaz (DB), oradan Kafka'ya aktarımı **ayrı bir süreç** yapsın.

### Outbox Pattern'in fikri

Outbox = "giden kutusu" demek (gmail'in sent folder'ı gibi).

```
┌─────────────────────────────────────────────────────────────┐
│                  Application Transaction                     │
│                                                              │
│  BEGIN TX                                                    │
│    INSERT INTO attendances (...)                             │
│    INSERT INTO outbox_events (event_type, payload, ...)     │
│  COMMIT                                                      │
│                                                              │
└────────────────────────────┬─────────────────────────────────┘
                             │
                             │ (sadece DB)
                             ▼
                  ┌─────────────────────┐
                  │   outbox_events     │
                  │   (sırada bekleyen) │
                  └──────────┬──────────┘
                             │
                             │ (background relay process)
                             ▼
                  ┌─────────────────────┐
                  │       Kafka         │
                  │   (topic publish)   │
                  └─────────────────────┘
```

Anahtar fikir: **DB write + event "outbox tablosuna yazılması"** aynı transaction'da olur. Bu **ACID atomic**. Kafka publish ise **ayrı bir relay process** tarafından yapılır — outbox'tan oku, Kafka'ya gönder, outbox'ı işaretle.

**Günlük hayattan analoji:**
Bir mektup gönderiyorsun:
- **Naive (dual write):** mektubu zarfa koy + posta kutusuna kendin at + posta servisinin işlediğini bekle. Eğer sen evden çıkamazsan veya kutu yoksa, mektup kayıp.
- **Outbox:** mektubu yaz, evinin "giden mektuplar" sepetine koy (atomic — yazma + sepete atma birlikte). Sonra postacı (relay) ne zaman gelirse alıp götürür. Sen mektubu yazdığını biliyorsun, sepette kanıtı var, postacı işini yapar.

## 2. Hangi problemi çözüyor?

Outbox olmadan tipik production sorunları:

**Acı 1 — Sessiz veri kaybı.**
Yoklama DB'ye yazıldı, Kafka publish anında broker'a bağlanma sorunu oldu. Kullanıcıya 200 OK döndü. Veliye SMS gitmedi. Audit kaydı atılmadı. Search index güncellenmedi. Sebebini saatler sonra fark edersin.

**Acı 2 — "Asenkron published, sonradan crash."**
```java
@Transactional
public void doStuff() {
    repo.save(...);
    // commit ediliyor
    // ↓ commit anında JVM crash olabilir!
}
// transaction sonrası listener Kafka'ya publish'i tetikler ama late
```
Spring `@TransactionalEventListener(AFTER_COMMIT)` ile yazılan kodlar bu boşluğu yakalar — ama bu sırada crash olursa event kaybolur.

**Acı 3 — Kafka uzun süre down.**
Kafka cluster bakımda 30dk. Bu süre boyunca tüm event'ler kayıp. Müşteri hizmetlerine "Yoklama aldım ama veliye SMS gitmedi" şikayetleri patlar.

**Acı 4 — Tutarlılık testi imkansız.**
Üretimde anlaşılmaz tutarsızlıklar oluyor ama tekrar üretemiyorsun (network glitch nadir).

**Acı 5 — Retry stratejisi belirsiz.**
Publish başarısız oldu — kodun içinde retry mı yapmalı? Kaç sefer? Backoff nasıl? Event'i nerede tutuyorsun retry için?

**Acı 6 — Idempotency garantisi yok.**
Publish başarısız sandık, retry yaptık, aslında ilk gönderim başarılıydı — duplicate event. Consumer side'ta da idempotency yoksa: duplicate işlem.

Outbox bu acıları şöyle çözer:

| Acı | Outbox çözümü |
|---|---|
| Sessiz veri kaybı | DB + outbox aynı transaction → atomic |
| Asenkron publish crash | Outbox kalıcı — relay sonra publish'i tamamlar |
| Kafka uzun süre down | Event'ler outbox'ta birikir, Kafka geri gelince akar |
| Tutarlılık test imkansız | Outbox state inspect edilebilir |
| Retry stratejisi belirsiz | Relay retry policy + attempt counter |
| Idempotency yok | Event ID üretilir, consumer side idempotency mümkün |

## 3. Nasıl çözüyor? (Çalışma prensibi)

### 3.1. Outbox tablosu

Her servisin kendi DB'sinde `outbox_events` (veya `outbox_records`) tablosu vardır:

```sql
CREATE TABLE outbox_events (
    id             UUID         PRIMARY KEY,            -- outbox kaydı id
    event_id       UUID         NOT NULL UNIQUE,        -- event'in iş id'si (idempotency için)
    aggregate_type VARCHAR(100) NOT NULL,               -- 'Attendance', 'Invoice'
    aggregate_id   VARCHAR(100) NOT NULL,               -- aggregate id (partition key)
    event_type     VARCHAR(200) NOT NULL,               -- 'academic.attendance.marked.v1'
    topic          VARCHAR(200) NOT NULL,               -- Kafka topic ismi
    payload        BYTEA        NOT NULL,               -- Protobuf encoded event
    headers        JSONB,                                -- correlation-id, tenant-id, schema-id vs.
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    published_at   TIMESTAMPTZ,                          -- relay tarafından set edilir
    attempt_count  INT          NOT NULL DEFAULT 0,
    last_error     TEXT,
    tenant_id      UUID         NOT NULL                 -- multi-tenancy
);

CREATE INDEX idx_outbox_unpublished
  ON outbox_events (created_at)
  WHERE published_at IS NULL;
```

Index sadece **henüz publish edilmemiş** kayıtlar üzerinde — büyük tabloda hızlı tarama için.

### 3.2. Yazma yolunda outbox

Use case service her yazma operasyonunda:

```java
@Service
@Transactional
public class MarkAttendanceService implements MarkAttendanceUseCase {

    private final AttendanceRepository attendanceRepo;
    private final OutboxEventPublisher outboxPublisher;  // outbound port
    private final Clock clock;

    @Override
    public AttendanceId execute(MarkAttendanceCommand cmd) {
        Attendance attendance = ...; // domain logic
        attendance.submit(clock);

        attendanceRepo.save(attendance);

        // Domain event'leri outbox'a yaz (AYNI TX)
        for (DomainEvent event : attendance.domainEvents()) {
            outboxPublisher.publish(event);
        }
        attendance.clearDomainEvents();

        return attendance.id();
        // @Transactional sonu: COMMIT — atomic
    }
}
```

`OutboxEventPublisher` outbound port'unun implementation'ı outbox tablosuna INSERT yapar:

```java
@Component
@RequiredArgsConstructor
public class JpaOutboxEventPublisher implements OutboxEventPublisher {

    private final OutboxRepository outboxRepo;
    private final DomainEventToProtoMapper mapper;

    @Override
    public void publish(DomainEvent event) {
        ProtoEventEnvelope envelope = mapper.toEnvelope(event);
        OutboxRecord record = OutboxRecord.builder()
            .id(UUID.randomUUID())
            .eventId(UUID.randomUUID())
            .aggregateType(event.aggregateType())
            .aggregateId(event.aggregateId().toString())
            .eventType(envelope.eventType())
            .topic(envelope.topic())
            .payload(envelope.protoBytes())
            .headers(buildHeaders())
            .tenantId(event.tenantId())
            .build();
        outboxRepo.save(record);  // INSERT outbox_events
    }

    private Map<String, String> buildHeaders() {
        return Map.of(
            "correlation-id", MDC.get("correlation-id"),
            "tenant-id", MDC.get("tenant-id"),
            "producer-service", "academic-service"
        );
    }
}
```

### 3.3. Relay süreçleri — iki yaklaşım

**Yaklaşım A: Polling Publisher (custom relay)**

Background scheduler her N saniyede outbox'ı sorgular, henüz publish edilmemiş kayıtları Kafka'ya gönderir:

```java
@Component
@RequiredArgsConstructor
@Slf4j
public class OutboxPollingRelay {

    private final OutboxRepository outboxRepo;
    private final KafkaTemplate<String, byte[]> kafkaTemplate;

    @Scheduled(fixedDelay = 500)  // her 500ms
    public void relay() {
        List<OutboxRecord> batch = outboxRepo.findUnpublishedBatch(100);  // FOR UPDATE SKIP LOCKED
        for (OutboxRecord r : batch) {
            try {
                ProducerRecord<String, byte[]> pr = new ProducerRecord<>(
                    r.topic(), null, r.aggregateId(), r.payload()
                );
                r.headers().forEach((k, v) -> pr.headers().add(k, v.getBytes()));

                kafkaTemplate.send(pr).get(5, TimeUnit.SECONDS);
                outboxRepo.markPublished(r.id(), Instant.now());
            } catch (Exception ex) {
                log.error("Relay failed: {}", r.id(), ex);
                outboxRepo.incrementAttempt(r.id(), ex.getMessage());
            }
        }
    }
}
```

`FOR UPDATE SKIP LOCKED` ile birden çok pod paralel çalışabilir; bir pod kilitleyince diğeri başka satıra geçer.

Avantajları:
- Basit kurulum
- Servis koduyla aynı dilde
- Tek deploy birimi

Dezavantajları:
- Polling overhead (her 500ms DB sorgusu)
- Latency = polling interval
- DB log table'ı büyürse performans kaybı

**Yaklaşım B: CDC (Change Data Capture) ile Debezium**

Debezium PostgreSQL'in **logical replication slot**'unu (WAL) okur, INSERT'leri yakalar, Kafka'ya yayınlar.

```
PostgreSQL WAL ──► Debezium connector (Kafka Connect) ──► Kafka topic
```

Outbox tablosuna her INSERT olduğunda Debezium yakalar ve Kafka'ya forward eder.

Avantajları:
- Düşük latency (WAL real-time)
- Polling yok, DB overhead sıfır
- Battle-tested

Dezavantajları:
- Kafka Connect operasyonel
- Debezium config karmaşık
- WAL slot'unu yönetmek gerekir (slot'lar dolarsa DB disk dolar)
- Logical replication yapılandırması (postgresql.conf'ta `wal_level=logical`)

**Lumix tercihi (şimdilik):**
- Başlangıç: **custom polling relay** — basit, kontrol kolay
- Ölçek büyüdüğünde: **Debezium**'a geçilebilir
- Hibrit kabul: kritik düşük-latency topic'ler için Debezium, geri kalan polling

Karar referans: [Teknoloji Kararları](../00-overview/02-technology-stack-decisions#5-mesajlaşma--event) — "Outbox implementation: Transactional outbox tablo + Kafka Connect Debezium veya custom relay"

### 3.4. Outbox akış diyagramı

```
[App Pod]                  [PostgreSQL]           [Relay (sidecar veya ayrı)]   [Kafka]
   │                            │                          │                      │
   │ BEGIN TX                   │                          │                      │
   │ ────────────────────────►  │                          │                      │
   │                            │                          │                      │
   │ INSERT attendance          │                          │                      │
   │ ────────────────────────►  │                          │                      │
   │                            │                          │                      │
   │ INSERT outbox_events       │                          │                      │
   │ ────────────────────────►  │                          │                      │
   │                            │                          │                      │
   │ COMMIT                     │                          │                      │
   │ ────────────────────────►  │                          │                      │
   │ ◄────────── OK             │                          │                      │
   │                            │                          │                      │
   │ Return 200 to caller       │                          │                      │
                                │                          │                      │
                                │  Polling: SELECT...FOR UPDATE SKIP LOCKED       │
                                │  ◄───────────────────────│                      │
                                │                          │                      │
                                │  Returns batch           │                      │
                                │  ────────────────────►   │                      │
                                │                          │ Produce             │
                                │                          │ ───────────────►    │
                                │                          │ ◄── ack             │
                                │                          │                      │
                                │  UPDATE outbox SET       │                      │
                                │  published_at = now()    │                      │
                                │  ◄───────────────────────│                      │
```

### 3.5. Cleanup — outbox şişer

Outbox sonsuza kadar büyüyemez. İki strateji:

**Strateji A: Soft retention.**
Published kayıtları 7 gün tut, sonra DELETE.

```sql
DELETE FROM outbox_events
WHERE published_at IS NOT NULL
  AND published_at < NOW() - INTERVAL '7 days';
```

7 gün audit + debugging marjı bırakır.

**Strateji B: Move to archive.**
Published kayıtları başka bir tabloya/storage'a taşı (Parquet, S3).

Lumix tercih: **Strateji A**, gerekirse archive job (low priority).

## 4. Biz projemizde nasıl kullanıyoruz?

### 4.1. Lumix'te outbox zorunluluğu

**Kural:** Bir servis Kafka'ya event publish ediyorsa, **outbox kullanmak ZORUNLUDUR**. İstisna yok.

İstisnalar yine de var mı? Olabilir:
- Saf okuma servisi (event publish etmiyor) → outbox gerekmez
- Out-of-band telemetry (metric, log) → outbox değil, doğrudan agent

Production-critical her domain event outbox'tan geçer.

### 4.2. Tablo şeması (standartlaştırılmış)

Her servisin Flyway migration'ında:

```sql
-- V001__create_outbox_events.sql
CREATE TABLE outbox_events (
    id             UUID         PRIMARY KEY,
    event_id       UUID         NOT NULL UNIQUE,
    aggregate_type VARCHAR(100) NOT NULL,
    aggregate_id   VARCHAR(100) NOT NULL,
    event_type     VARCHAR(200) NOT NULL,
    topic          VARCHAR(200) NOT NULL,
    payload        BYTEA        NOT NULL,
    headers        JSONB,
    tenant_id      UUID         NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    published_at   TIMESTAMPTZ,
    attempt_count  INT          NOT NULL DEFAULT 0,
    last_error     TEXT
);

CREATE INDEX idx_outbox_unpublished
  ON outbox_events (created_at)
  WHERE published_at IS NULL;

CREATE INDEX idx_outbox_tenant
  ON outbox_events (tenant_id, created_at);

-- RLS — outbox kayıtları da tenant-scoped
ALTER TABLE outbox_events ENABLE ROW LEVEL SECURITY;
CREATE POLICY outbox_tenant_policy ON outbox_events
  USING (tenant_id::text = current_setting('app.tenant_id', true));
```

### 4.3. Relay deployment

Lumix'te relay genelde **servis pod'unun içinde** çalışır (ayrı deployment değil):
- Servis = `academic-service` pod'u, içinde Spring Boot uygulaması + outbox scheduler
- Avantaj: tek deploy unit'i, basit operasyon
- Dezavantaj: scheduler ölçeklenmesi pod replica ile sınırlı

Daha büyük ölçek için:
- Relay'i ayrı deployment'a çıkar (`academic-outbox-relay`)
- Daha sonra Debezium'a geç

### 4.4. Outbox metric'leri

Her servis şu metric'leri Prometheus'a yayınlar:

```
outbox_unpublished_count{service="academic-service",tenant_id="..."}
outbox_relay_duration_seconds{service="academic-service"}
outbox_publish_success_total{service="academic-service"}
outbox_publish_failure_total{service="academic-service"}
outbox_oldest_unpublished_age_seconds{service="academic-service"}
```

Alert:
- `outbox_oldest_unpublished_age_seconds > 60` → Kafka publish gecikiyor
- `outbox_unpublished_count > 10000` → backlog büyüyor
- `outbox_publish_failure_total{} rate increase` → relay sürekli hata

### 4.5. Outbox'ı consumer ile koordine et

Consumer side'ta idempotency için `event_id` kullanılır:

```sql
CREATE TABLE processed_events (
    event_id   UUID         PRIMARY KEY,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
```

Consumer her event'ten önce:

```java
if (processedEventRepo.existsByEventId(eventId)) {
    log.debug("Already processed, skip: {}", eventId);
    return;
}
// ... process event
processedEventRepo.save(new ProcessedEvent(eventId, Instant.now()));
```

Detay: [Event-Driven Architecture — Idempotency](./event-driven-architecture#34-at-least-once-delivery-ve-idempotency).

## 5. Neden bu seçildi? (Alternatifler ve trade-off)

### 5.1. Alternatifler

**Alternatif 1 — `@TransactionalEventListener` (Spring native)**
Spring Boot'un built-in feature'ı. Transaction commit sonrası listener tetiklenir.

```java
@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
public void handle(AttendanceMarkedEvent event) {
    kafkaTemplate.send(...);
}
```

Niye yetmez:
- Commit ile listener tetikleme arası kısa pencere — JVM crash veri kaybı
- Listener içinde Kafka publish başarısız olursa retry yok
- Atomic değil

**Alternatif 2 — Kafka Transactions (exactly-once semantics)**
Kafka'nın transactional producer + read_committed consumer + transactional consumer.

Niye yetmez:
- "DB + Kafka" arası exactly-once değil, sadece "Kafka topic A → Kafka topic B" arası
- DB transaction ile Kafka transaction birleştirilemez

**Alternatif 3 — Distributed Transaction (XA)**
JTA + XA capable broker.

Niye elendi:
- Operasyonel kabus
- Kafka XA destekli değil
- Modern cloud-native dünyada anti-pattern

**Alternatif 4 — Dual-write + idempotent consumer + reconciliation job**
DB write + Kafka publish (best-effort), tutarsızlığı periyodik reconciliation job ile düzelt.

Niye elendi:
- Reconciliation karmaşık ve geç
- Bazı event'lerin reconciliation'ı imkansız (örn. SMS gönderildi mi?)
- İlk başta kabul edilebilir görünür, sonradan kaos olur

**Alternatif 5 — Event Sourcing**
Her şey event log; state event'lerden türetilir.

Niye elendi (genel olarak):
- Domain karmaşıklığı artar
- Query side için projection gerekir
- Lumix'te bazı yerlerde mantıklı (audit) ama default tasarım değil

### 5.2. Custom Relay vs Debezium

| Kriter | Custom Polling | Debezium CDC |
|---|---|---|
| Kurulum | Basit, kod içinde | Kafka Connect cluster gerekir |
| Latency | ~500ms (polling interval) | ~1-10ms (WAL real-time) |
| Operasyonel yük | Düşük | Orta-yüksek (slot, WAL, Connect) |
| Scaling | Pod replica ile | Connect worker scaling |
| DB load | Polling overhead | WAL okuma |
| Tek başına deploy | App ile birlikte | Ayrı cluster (Kafka Connect) |
| Olgunluk | Custom code | Battle-tested |

**Lumix başlangıç tercihi:** Custom polling — sade, hızlı başlangıç. Latency hedefi 1 saniye altı için yeterli.

**Ne zaman Debezium'a geçeriz?**
- Outbox throughput çok yüksek olur (binlerce event/saniye)
- Latency hedefi 100ms altına iner
- Polling DB'ye yük olur

### 5.3. Kabul edilen trade-off'lar

| Trade-off | Maliyet | Mitigation |
|---|---|---|
| Eklenen DB latency | Her transaction outbox INSERT yapar | Index optimize, partition |
| Outbox table şişer | Disk + index yükü | Cleanup job + retention policy |
| Polling overhead | DB her 500ms sorgulanır | Index üzerinde minimal scan, SKIP LOCKED |
| Eventual delivery | Publish hemen olmaz, ~500ms gecikme | Consumer side ready için kabul edilir |
| Double serialization | Domain event → Proto bytes → DB → Kafka | Performance trade-off, kabul |
| Outbox bir başka SPOF | Outbox tablosu corrupt olursa? | DB backup + replication |

## 6. Pratik örnek

### 6.1. Outbox entity ve repository

```java
// adapter/out/persistence/OutboxEntity.java
@Entity
@Table(name = "outbox_events")
public class OutboxEntity {

    @Id
    private UUID id;

    @Column(name = "event_id", nullable = false, unique = true)
    private UUID eventId;

    @Column(name = "aggregate_type", nullable = false)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false)
    private String aggregateId;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Column(nullable = false)
    private String topic;

    @Column(nullable = false)
    private byte[] payload;

    @Type(JsonBinaryType.class)
    @Column(columnDefinition = "jsonb")
    private Map<String, String> headers;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount = 0;

    @Column(name = "last_error")
    private String lastError;

    // getter/setter omitted
}
```

### 6.2. Outbound port + adapter

```java
// application/port/out/OutboxEventPublisher.java
public interface OutboxEventPublisher {
    void publish(DomainEvent event);
}

// adapter/out/persistence/JpaOutboxEventPublisher.java
@Component
@RequiredArgsConstructor
public class JpaOutboxEventPublisher implements OutboxEventPublisher {

    private final OutboxRepository outboxRepo;
    private final DomainEventToProtoMapper protoMapper;
    private final EventTopicResolver topicResolver;

    @Override
    public void publish(DomainEvent event) {
        ProtoEnvelope envelope = protoMapper.toProto(event);
        OutboxEntity entity = new OutboxEntity();
        entity.setId(UUID.randomUUID());
        entity.setEventId(envelope.eventId());
        entity.setAggregateType(event.aggregateType());
        entity.setAggregateId(event.aggregateId().toString());
        entity.setEventType(envelope.eventType());
        entity.setTopic(topicResolver.resolveTopic(event));
        entity.setPayload(envelope.protoBytes());
        entity.setHeaders(currentHeaders());
        entity.setTenantId(event.tenantId());
        entity.setCreatedAt(Instant.now());

        outboxRepo.save(entity);
    }

    private Map<String, String> currentHeaders() {
        Map<String, String> h = new HashMap<>();
        Optional.ofNullable(MDC.get("correlation-id")).ifPresent(v -> h.put("correlation-id", v));
        Optional.ofNullable(MDC.get("tenant-id")).ifPresent(v -> h.put("tenant-id", v));
        h.put("producer-service", "academic-service");
        return h;
    }
}
```

### 6.3. Repository — SKIP LOCKED ile batch okuma

```java
public interface OutboxRepository extends JpaRepository<OutboxEntity, UUID> {

    @Query(value = """
        SELECT * FROM outbox_events
        WHERE published_at IS NULL
        ORDER BY created_at
        LIMIT :batchSize
        FOR UPDATE SKIP LOCKED
        """, nativeQuery = true)
    List<OutboxEntity> findUnpublishedBatch(@Param("batchSize") int batchSize);

    @Modifying
    @Query("UPDATE OutboxEntity o SET o.publishedAt = :now WHERE o.id = :id")
    int markPublished(@Param("id") UUID id, @Param("now") Instant now);

    @Modifying
    @Query("UPDATE OutboxEntity o SET o.attemptCount = o.attemptCount + 1, o.lastError = :err WHERE o.id = :id")
    int incrementAttempt(@Param("id") UUID id, @Param("err") String err);
}
```

### 6.4. Relay scheduler

```java
@Component
@RequiredArgsConstructor
@Slf4j
public class OutboxKafkaRelay {

    private final OutboxRepository outboxRepo;
    private final KafkaTemplate<String, byte[]> kafkaTemplate;
    private final MeterRegistry meterRegistry;

    @Value("${outbox.relay.batch-size:100}")
    private int batchSize;

    @Scheduled(fixedDelayString = "${outbox.relay.interval-ms:500}")
    @Transactional
    public void relay() {
        Timer.Sample sample = Timer.start(meterRegistry);
        List<OutboxEntity> batch = outboxRepo.findUnpublishedBatch(batchSize);

        if (batch.isEmpty()) {
            sample.stop(meterRegistry.timer("outbox.relay.duration"));
            return;
        }

        log.debug("Relaying {} outbox events", batch.size());

        for (OutboxEntity ev : batch) {
            try {
                ProducerRecord<String, byte[]> record = new ProducerRecord<>(
                    ev.getTopic(),
                    null,
                    ev.getAggregateId(),
                    ev.getPayload()
                );
                if (ev.getHeaders() != null) {
                    ev.getHeaders().forEach((k, v) ->
                        record.headers().add(k, v.getBytes(StandardCharsets.UTF_8))
                    );
                }
                record.headers().add("event-id", ev.getEventId().toString().getBytes());

                SendResult<String, byte[]> result = kafkaTemplate.send(record).get(5, TimeUnit.SECONDS);
                outboxRepo.markPublished(ev.getId(), Instant.now());
                meterRegistry.counter("outbox.publish.success",
                    "topic", ev.getTopic()).increment();
            } catch (Exception ex) {
                log.error("Outbox publish failed for {}: {}", ev.getId(), ex.getMessage());
                outboxRepo.incrementAttempt(ev.getId(), ex.getMessage());
                meterRegistry.counter("outbox.publish.failure",
                    "topic", ev.getTopic(),
                    "error", ex.getClass().getSimpleName()
                ).increment();
            }
        }
        sample.stop(meterRegistry.timer("outbox.relay.duration"));
    }
}
```

### 6.5. Cleanup job

```java
@Component
@RequiredArgsConstructor
@Slf4j
public class OutboxCleanupJob {

    private final OutboxRepository outboxRepo;

    @Value("${outbox.cleanup.retention-days:7}")
    private int retentionDays;

    @Scheduled(cron = "0 0 3 * * *")  // her gün 03:00
    @Transactional
    public void cleanup() {
        Instant cutoff = Instant.now().minus(Duration.ofDays(retentionDays));
        int deleted = outboxRepo.deletePublishedOlderThan(cutoff);
        log.info("Outbox cleanup: {} kayıt silindi (retention {} gün)", deleted, retentionDays);
    }
}

// Repository:
@Modifying
@Query("DELETE FROM OutboxEntity o WHERE o.publishedAt IS NOT NULL AND o.publishedAt < :cutoff")
int deletePublishedOlderThan(@Param("cutoff") Instant cutoff);
```

### 6.6. Test — outbox integration test (Testcontainers)

```java
@SpringBootTest
@Testcontainers
@AutoConfigureTestDatabase(replace = Replace.NONE)
class OutboxIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17");

    @Autowired MarkAttendanceUseCase markAttendanceUseCase;
    @Autowired OutboxRepository outboxRepo;

    @Test
    void shouldWriteOutboxEventInSameTransaction() {
        MarkAttendanceCommand cmd = sampleCommand();
        AttendanceId id = markAttendanceUseCase.execute(cmd);

        List<OutboxEntity> outbox = outboxRepo.findAll();
        assertThat(outbox).hasSize(1);
        OutboxEntity ev = outbox.get(0);
        assertThat(ev.getTopic()).isEqualTo("academic.attendance.marked.v1");
        assertThat(ev.getPublishedAt()).isNull();
        assertThat(ev.getAggregateId()).isEqualTo(id.value().toString());
    }

    @Test
    void shouldNotWriteOutboxWhenDomainFails() {
        MarkAttendanceCommand badCmd = sampleCommandWithFutureDate();  // invariant violation

        assertThatThrownBy(() -> markAttendanceUseCase.execute(badCmd))
            .isInstanceOf(IllegalArgumentException.class);

        // Hem attendance hem outbox boş olmalı
        assertThat(outboxRepo.findAll()).isEmpty();
    }
}
```

## 7. Dikkat edilecek tuzaklar

**Tuzak 1 — Outbox INSERT'i ayrı transaction'da yapmak.**
"İlk önce attendance commit, sonra outbox tx başlat" — atomicity öldü.
**Önleme:** Aynı `@Transactional` blok. Repository implementation içinde otomatik aynı transaction.

**Tuzak 2 — Polling interval çok kısa.**
Her 50ms polling → DB CPU patladı.
**Önleme:** 500ms–1s aralık dengeli. Bench yap, alert ile izle.

**Tuzak 3 — SKIP LOCKED'i unutmak.**
Birden çok pod aynı outbox satırını okur, duplicate publish olur.
**Önleme:** `FOR UPDATE SKIP LOCKED` zorunlu. Native query'de açık kullan.

**Tuzak 4 — Cleanup'ı atlamak.**
6 ay sonra outbox table 100M satır, index 5GB, query yavaşladı.
**Önleme:** Cleanup job day-1. Retention policy yazılı.

**Tuzak 5 — Headers'ı eksik göndermek.**
correlation-id, tenant-id outbox'a yazılmadı. Consumer tarafında context kayıp.
**Önleme:** Headers JSONB kolonunda standart key listesi zorunlu.

**Tuzak 6 — Event_id'yi tekrar üretmek.**
Relay her retry'da yeni event_id atar — consumer idempotency yokeder.
**Önleme:** Event_id outbox INSERT'inde set edilir, relay sadece okur.

**Tuzak 7 — Outbox sadece "happy path" düşünmek.**
Service yazma başarısızsa outbox INSERT de olmamalı — `@Transactional` zaten halleder, ama dikkat.
**Önleme:** Aynı transaction kuralı kesin. Outbox publish başarısız olursa attempt_count artar; alert.

**Tuzak 8 — Sıralamayı garanti varsaymak.**
Outbox'tan publish edilen event'ler her zaman yaratılış sırasında değildir (parallel relay).
**Önleme:** Aynı `aggregate_id` için sıra Kafka partition key garantisiyle korunur. Cross-aggregate sıra varsayma.

**Tuzak 9 — Outbox'ı sadece Kafka için kullanmak.**
Outbox sadece event publish'i değil, dışarı bağımlı her side-effect için kullanılabilir (webhook çağrısı, dosya gönderme).
**Önleme:** Generic outbox pattern — payload + target adapter.

**Tuzak 10 — Outbox'ı bypass etmek.**
"Bu küçük event için outbox kullanmayalım, direkt Kafka publish edelim" — bir gün race condition seni ısırır.
**Önleme:** Outbox kullanımı zorunlu. CI'da pattern check (kod'da `kafkaTemplate.send(...)` direkt çağrısı yasak).

**Tuzak 11 — Outbox metric'lerini izlememek.**
Outbox stuck oldu, kimse fark etmedi. Backlog büyüdü, müşteri şikayet etti.
**Önleme:** `outbox_oldest_unpublished_age_seconds` alert. `outbox_unpublished_count` dashboard.

**Tuzak 12 — Schema değişikliğinde outbox'ı düşünmemek.**
Event schema değişti, payload format eskidi. Relay eski payload'ları publish ediyor, consumer kırılıyor.
**Önleme:** Schema versioning + backward compatibility.

## 8. Diğer konularla ilişkisi

- [Event-Driven Architecture](./event-driven-architecture) — outbox'ın çözdüğü problem EDA'nın temel sorunu
- [Microservices Architecture](./microservices-architecture) — DB-per-service ile outbox tek tablo per service
- [Saga Pattern](./saga-pattern) — saga'nın event yayını outbox'tan geçer
- [Hexagonal Architecture](./hexagonal-architecture) — `OutboxEventPublisher` outbound port örneği
- [Domain-Driven Design](./domain-driven-design) — domain event'lerin outbox'a yazılması

## 9. Daha derine inmek için

**Kaynak makaleler:**
- microservices.io/patterns/data/transactional-outbox.html — Chris Richardson
- "Reliable Microservices Data Exchange with the Outbox Pattern" — Debezium blog
- Vaughn Vernon, "Implementing Domain-Driven Design" — Chapter on event publishing
- Confluent blog — "Outbox Pattern with Kafka Connect"

**Debezium:**
- debezium.io/documentation/reference/transformations/outbox-event-router.html
- Strimzi (Kubernetes-native Kafka Connect)

**Search keywords (İngilizce):**
- "transactional outbox pattern"
- "dual write problem microservices"
- "debezium outbox event router"
- "change data capture postgres kafka"
- "polling publisher pattern"
- "for update skip locked postgres"

## 10. Sözlük

- **CDC (Change Data Capture)** — DB değişikliklerini stream olarak yakalayan teknik.
- **Debezium** — Açık kaynak CDC platform, Kafka Connect üzerinde çalışır.
- **Dual-write Problem** — İki ayrı sisteme atomic yazmanın imkansızlığı.
- **Eventual Consistency** — Sistem zamanla tutarlı hale gelir. Outbox bunu kabul eder.
- **Idempotency Key** — Aynı isteğin iki kez işlenmemesini sağlayan anahtar (Lumix'te event_id).
- **Kafka Connect** — Kafka ile dış sistem (DB, S3, ES) arası entegrasyon framework'ü.
- **Logical Replication Slot** — PostgreSQL'in WAL'ı dış consumer'a yayımladığı kanal.
- **Outbox Pattern** — Atomic write + event publish için DB tablosuna event yazıp ayrı relay ile yayınlama.
- **Polling Publisher** — Outbox tablosunu periyodik sorgulayıp publish eden relay.
- **Relay** — Outbox tablosundan Kafka'ya event taşıyan süreç.
- **SKIP LOCKED** — PostgreSQL'in row-level kilitlenmiş satırları atlama özelliği. Outbox'ta paralel relay için.
- **WAL (Write-Ahead Log)** — PostgreSQL'in tüm değişiklikleri yazdığı log. CDC'nin kaynağı.

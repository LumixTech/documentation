---
title: Event-Driven Architecture (EDA)
description: EDA prensipleri, domain event vs integration event ayrımı, choreography vs orchestration, Lumix'te Kafka'nın rolü.
sidebar_position: 4
---

## Bu sayfa ne anlatıyor?

Bu sayfa **Event-Driven Architecture (EDA)** kavramını sıfırdan açıklıyor: event nedir, sync iletişimden farkı ne, **domain event** ile **integration event** arasındaki kritik fark, **choreography** ile **orchestration** trade-off'ları ve Lumix'te Kafka'nın bu mimaride oynadığı rol. Sayfayı bitiren biri yeni bir feature için "sync mi async mi", "event mi command mi", "kim publish eder, kim consume eder" kararlarını verebilmeli.

## 1. Bu nedir? (Sıfırdan)

**Event-Driven Architecture**, servislerin **birbirini doğrudan çağırmak yerine** olan biteni **event** olarak yayınladığı ve ilgilenen servislerin bu event'leri **abone olup tepki verdiği** mimari yaklaşımdır.

**Event nedir?** Geçmişte olmuş bir olayın **immutable** kaydıdır. İsmi past tense'tir:
- `OrderPlaced` (sipariş verildi)
- `AttendanceMarked` (yoklama alındı)
- `PaymentCaptured` (ödeme tahsil edildi)
- `UserRegistered` (kullanıcı kaydoldu)

Event != command:
- **Command (emir):** "Şunu yap" → `MarkAttendance`, `SendEmail`, `CapturePayment`. Belli bir alıcısı var, reddedilebilir.
- **Event (haber):** "Şu oldu" → `AttendanceMarked`, `EmailSent`, `PaymentCaptured`. Alıcı(lar) bilinmez, sadece broadcast.

**Günlük hayattan analoji:**

Sync iletişim = **telefonla konuşmak.**
- Karşıdakini ararsın, eş zamanlı konuşursun
- Karşı taraf yoksa veya meşgulse beklersin
- Konuşurken konuşacak şeyini bilirsin (kim, ne sorulacak)

Event-driven = **gazete ilanı vermek.**
- "Bugün yoklama alındı" diye gazete köşesine yazarsın
- Gazete bir köşede durur
- Veliler ilgileniyorsa okur, müdür ilgileniyorsa okur, denetçi ilgileniyorsa okur
- Sen kimin okuduğunu bilmezsin, ilgilenmezsin
- Veliler 5 dakika sonra okur, müdür 1 saat sonra okur — herkes kendi zamanında

Lumix'te **Kafka** o gazete'dir. Her topic bir köşe. Producer event'i yazar, **consumer'lar** kendi tempolarında okur.

### EDA'nın 4 temel özelliği

1. **Loose coupling:** Publisher subscriber'ı tanımaz, subscriber publisher'a bağımlı değildir.
2. **Asynchronous:** Publisher event'i yayınlar ve devam eder; subscriber kendi zamanında işler.
3. **Many-to-many:** Bir event'i sıfır, bir, veya N subscriber consume edebilir.
4. **Immutable & time-ordered:** Event'ler değişmez, zaman sırasında saklanır (Kafka offset).

### EDA terimleri

- **Producer (publisher):** Event yayınlayan servis
- **Consumer (subscriber):** Event'i dinleyip işleyen servis
- **Topic:** Event'lerin yayınlandığı kanal (Kafka'da partition'lara bölünür)
- **Event payload:** Event'in içeriği (Lumix'te Protobuf encoded)
- **Schema:** Event'in yapısal şablonu (Lumix'te Apicurio Registry'de versiyonlanır)
- **Offset:** Bir consumer'ın topic'te nerede olduğunu gösteren pozisyon
- **Consumer group:** Aynı event'i paylaşan consumer'lar (workload distribution)
- **At-least-once delivery:** Event en az bir kere ulaşır (duplicate olabilir → idempotency lazım)
- **Exactly-once semantics:** Event tam olarak bir kere işlenir (özel yapılandırma + transactional producer/consumer)

## 2. Hangi problemi çözüyor?

EDA olmadan, sync request/response ile büyüyen bir sistemde şu acılar baş gösterir:

**Acı 1 — Cascading Failure (zincirleme arıza).**
academic-service yoklama aldığında:
1. notification-service'i sync çağırır (veliye SMS için)
2. notification-service finance-service'i sync çağırır (veli bilgisi için)
3. finance-service identity-service'i sync çağırır (yetki için)

Eğer identity-service yavaşlarsa veya çökerse: finance bekler → notification bekler → academic bekler → kullanıcı timeout görür. **4 servisi de etkileyen tek arıza noktası.**

**Acı 2 — Tight Coupling (sıkı bağımlılık).**
academic-service'in kodunda `notificationServiceClient.send(...)` çağrısı var. Yarın notification-service başka bir servisle değiştirilirse, academic-service'in kodu değişmek zorunda. Yeni bir tüketici eklenirse (örn. statistics-service de bu olayı dinlesin) — academic-service tekrar değişmek zorunda.

**Acı 3 — Synchronous Latency Toplaşır.**
Bir kullanıcı isteği için 5 sync çağrı yapılır:
- A: 50ms
- B: 80ms
- C: 30ms
- D: 100ms
- E: 60ms
Toplam: 320ms (üstüne kullanıcının kendi network latency'si). Frontend yavaş hisseder.

**Acı 4 — Side Effect Sorumlulukları Karışır.**
"Yoklama alındığında veliye SMS, müdüre dashboard güncellemesi, search index güncellemesi, audit kaydı" — bunların hepsi academic-service'in kodunda mı durmalı? Servis sorumluluğu erir.

**Acı 5 — Yeni Tüketici Eklemek = Yazılım Değişikliği.**
"Bu olayı şimdi de analytics ekibi dinlemek istiyor." Sync dünyada academic-service'in kodu değişir, deploy gerekir, koordinasyon yapılır.

**Acı 6 — Eventual Consistency Gerekleri.**
Bir öğrenci silindiğinde 10 farklı servis veriyi temizlemeli. Sync orchestrasyon: 10 servisi sırayla çağır, herhangi biri çökerse rollback nasıl? Distributed transaction kabusu.

EDA bu acıları şöyle çözer:

| Acı | EDA çözümü |
|---|---|
| Cascading failure | Publisher subscriber'ları bilmez; biri yavaşsa diğerleri etkilenmez |
| Tight coupling | Sözleşme yalnızca event schema; tüketici listesi değişebilir |
| Sync latency toplaşır | Side effect'ler async, kullanıcı isteği hızlı döner |
| Side effect kaosu | Her side effect kendi servisinde, kendi event'ini consume eder |
| Yeni tüketici = kod değişikliği | Yeni servis topic'e subscribe olur, publisher haberi olmaz |
| Distributed transaction kabusu | Saga pattern + compensation event |

## 3. Nasıl çözüyor? (Çalışma prensibi)

### 3.1. Bir event'in yolculuğu

```
[academic-service]                                   [Kafka]                              [consumer'lar]
       │                                                │
       │  1. Aggregate operation                        │
       │  attendance.submit()                           │
       │                                                │
       │  2. Domain event yaratılır                     │
       │  AttendanceMarkedEvent                         │
       │                                                │
       │  3. Outbox table'a INSERT (same TX)            │
       │  ─────────────────────────────────────────►    │
       │                                                │
       │  4. Outbox Relay (background)                  │
       │  outbox → Kafka publish                        │
       │  ─────────────────────────────────────────►    │  topic:
       │                                                │  academic.attendance.marked.v1
       │                                                │     │
       │                                                │     ├──► notification-service
       │                                                │     │     veliye SMS
       │                                                │     │
       │                                                │     ├──► performance-service
       │                                                │     │     devamsızlık aggregate'i güncelle
       │                                                │     │
       │                                                │     ├──► audit-service
       │                                                │     │     audit_logs INSERT
       │                                                │     │
       │                                                │     └──► elasticsearch-indexer
       │                                                │           ES index güncelle
```

### 3.2. Domain Event vs Integration Event

Bu **en önemli ayrım**:

| Özellik | Domain Event | Integration Event |
|---|---|---|
| Kapsam | Bounded context içi | Bounded context dışı, public |
| Sahip | Aggregate yaratır | Servis yayınlar |
| Şema değişebilir mi? | Evet, ana servisle birlikte | Hayır, BACKWARD compatible olmalı |
| Format | Domain object (Java class) | Protobuf message |
| Channel | Aynı transaction içi, in-memory | Kafka topic |
| Versiyonlanır mı? | Hayır (refactor edilir) | Evet (`.v1`, `.v2`) |
| Tüketici sayısı | 1 (servisin kendisi) | N (her servis dinleyebilir) |

**Lumix akışı:**

```
1. Aggregate domain event yaratır (in-memory):
   AttendanceMarkedEvent (Java class, domain.event paketinde)

2. Use case service domain event'i alır.

3. Outbound port (OutboxEventPublisher) domain event'i:
   a. Schema mapping yapar → AttendanceMarkedV1 (Protobuf, integration event)
   b. Outbox table'a yazar

4. Outbox Relay → Kafka topic'e publish eder.

5. Diğer servisler Kafka'dan integration event'i alır.
   Bu servisler `AttendanceMarkedEvent` (domain class) görmez,
   sadece `AttendanceMarkedV1` (Protobuf) görür.
```

Bu ayrım **schema evolution**'ı sağlar:
- Domain event içinde rahatça refactor edebilirsin
- Integration event public sözleşme — değiştirirken BACKWARD compatibility zorunlu

### 3.3. Choreography vs Orchestration

EDA'da iki temel koordinasyon stili var:

**Choreography (koreografi):**
Servisler birbirini doğrudan koordine etmez. Her servis kendi event'lerine reaksiyon verir, yeni event üretir. Merkezi bir şef yok — dansçılar birbirine bakarak hareket eder.

```
identity ──UserCreated──► organization ──TenantSetup──► finance
                              │
                              └────► notification ──WelcomeEmailSent──►
```

Avantaj:
- Loose coupling — yeni dansçı eklemek kolay
- Tek nokta arıza yok

Dezavantaj:
- Akışı takip etmek zor (görsellik düşük)
- Global state'i bilen kimse yok
- Compensation karmaşık (kim neyi geri alır?)

**Orchestration (orkestrasyon):**
Merkezi bir koordinatör (orchestrator) tüm adımları yönetir, hangi servise hangi command'ı göndereceğini bilir.

```
[Orchestrator: Enrollment Saga]
   │
   ├──► finance: CreateInvoice
   │     ◄── InvoiceCreated
   ├──► payment: InitiatePayment
   │     ◄── PaymentCaptured
   └──► organization: ConfirmEnrollment
```

Avantaj:
- Akış görsel, debug edilebilir
- Compensation merkezi
- State machine tek yerde

Dezavantaj:
- Tek noktada karmaşıklık
- Orchestrator yeni servis eklendiğinde değişir

**Lumix tercihi:**
- **Çoğu integration:** choreography (Kafka event'lerle)
- **Karmaşık iş süreçleri (saga):** orchestration (Temporal workflow)

Örnek:
- "Yoklama alındı → notification + performance + audit + search" → choreography
- "Ödeme saga: invoice → payment authorize → capture → enrollment confirm" → orchestration (Temporal)

Detay: [Saga Pattern](./05-saga-pattern.md).

### 3.4. At-least-once Delivery ve Idempotency

Kafka'da default delivery garantisi **at-least-once**'tir. Yani aynı event birden çok kez teslim edilebilir. Sebepleri:
- Consumer commit'i başarısız oldu, rebalancing'den sonra tekrar okudu
- Producer retry yaptı
- Network glitch

Bu durumda consumer **idempotent** olmalı: aynı event'i 2 kere alsa da sonuç değişmemeli.

**Idempotency stratejileri:**

1. **Event ID kontrol tablosu:**
   ```sql
   CREATE TABLE processed_events (
       event_id UUID PRIMARY KEY,
       processed_at TIMESTAMP
   );
   ```
   Her event'i işlemeden önce: `SELECT 1 FROM processed_events WHERE event_id = ?`. Varsa skip et.

2. **Natural idempotency:**
   Operation kendisi idempotent. `UPDATE x SET status = 'PAID' WHERE id = ?` aynı id için 10 kez çalıştırılsa da sonuç değişmez.

3. **Conditional update:**
   `UPDATE x SET status = 'PAID', updated_at = NOW() WHERE id = ? AND status != 'PAID'` — only-if-different.

### 3.5. Event Schema Evolution

Integration event'ler public sözleşmedir. Schema değiştirmek dikkatli yapılmalı.

Lumix'te **BACKWARD compatibility** zorunlu:
- Yeni alan ekle = OK (eski consumer ignore eder)
- Alan sil = ÖNCE eski'yi `deprecated` işaretle, sonra yeni topic versiyonu (`v2`)
- Required'i optional yapmak = OK
- Optional'i required yapmak = breaking
- Field type değiştirme = breaking

Schema registry (**Apicurio**) bu kuralları **enforce** eder — uyumsuz schema register edilemez.

Breaking change zorunluysa: yeni topic versiyonu (`academic.attendance.marked.v2`), her iki versiyonu paralel yayınla, consumer'lar v2'ye geçtikten sonra v1'i kapat.

## 4. Biz projemizde nasıl kullanıyoruz?

### 4.1. Lumix EDA stack'i

| Bileşen | Karar | Açıklama |
|---|---|---|
| Broker | **Apache Kafka** | Tek async broker, replay, partitioning |
| Schema format | **Protobuf** | gRPC ile aynı schema dili |
| Schema registry | **Apicurio Registry** | OSS, Confluent API uyumlu |
| Compatibility | **BACKWARD** | Consumer önce upgrade |
| Publish guarantee | **Outbox Pattern** | DB write + event publish atomic |
| Delivery | **At-least-once** + consumer idempotency | |
| Coordination | **Choreography** (çoğu) + **Orchestration** (saga, Temporal) | |

### 4.2. Topic naming convention

```
{service}.{aggregate}.{event}.v{n}

academic.attendance.marked.v1
academic.attendance.revised.v1
identity.user.created.v1
identity.user.permission_changed.v1
finance.payment.captured.v1
finance.payment.failed.v1
counseling.session.created.v1
```

Kural:
- `service` — yayınlayan servis ismi
- `aggregate` — domain aggregate
- `event` — past tense action
- `v1`, `v2`, ... — schema major version
- Tüm lowercase + snake_case
- Periyot (`.`) separator

### 4.3. Partition stratejisi

Lumix'te partition key olarak genelde **tenant_id** kullanılır:
- Aynı tenant'ın event'leri **sıralı işlenir** (Kafka aynı key → aynı partition garantisi)
- Tenant'lar arası paralelizm korunur
- Hot tenant'lar uniform dağılır mı diye dikkat (büyük tenant'lar küçüklere göre fazla yük)

Bazı topic'lerde aggregate_id (örn. attendance_id) daha doğru — aggregate başına sıra yeter.

### 4.4. Event payload format

Protobuf encoded, schema registry referansıyla:

```proto
// academic_attendance_marked_v1.proto
syntax = "proto3";

package com.lumix.academic.events.v1;

option java_package = "com.lumix.proto.academic.events.v1";
option java_multiple_files = true;

import "google/protobuf/timestamp.proto";

message AttendanceMarkedV1 {
  string event_id = 1;
  string attendance_id = 2;
  string class_id = 3;
  string tenant_id = 4;
  string date = 5;  // ISO 8601, YYYY-MM-DD
  google.protobuf.Timestamp submitted_at = 6;
  string submitted_by_user_id = 7;
  repeated StudentMark marks = 8;

  message StudentMark {
    string student_id = 1;
    PresenceStatus presence = 2;
  }

  enum PresenceStatus {
    PRESENCE_STATUS_UNSPECIFIED = 0;
    PRESENT = 1;
    ABSENT = 2;
    LATE = 3;
    EXCUSED = 4;
  }
}
```

### 4.5. Cross-cutting metadata — header'larda

Her Kafka mesajının header'larında:

```
correlation-id    → UUID (tracing için)
tenant-id         → UUID (multi-tenancy için)
event-id          → UUID (idempotency için)
schema-id         → int (Apicurio'da kayıtlı schema ref)
producer-service  → string ("academic-service")
producer-version  → string ("2.3.1")
```

Bu sayede consumer:
- Trace'i propagate edebilir
- Tenant context'i set edebilir
- Idempotency check yapabilir

### 4.6. DLQ (Dead Letter Queue) stratejisi

Bir event işlenemezse:

1. **Retry** (Spring `@RetryableTopic` veya custom): 3 deneme, exponential backoff
2. Hala başarısızsa → **DLQ topic'e** gönder: `academic.attendance.marked.v1.DLQ`
3. DLQ'da manuel inceleme + replay tool

Detay: [DLQ ve Outbox](./06-outbox-pattern.md).

### 4.7. Hangi servis hangi event'leri üretir/tüketir?

Detaylı tablo: [Domain Servisleri — Cross-service iletişim](../01-tenancy-and-domain-model/02-domain-services-overview.md#4-cross-service-iletişim-haritası).

Özet:

```
academic    ──produces──► attendance.marked, attendance.revised, homework.assigned
identity    ──produces──► user.created, user.role_changed, user.permission_changed
finance     ──produces──► invoice.created, payment.captured, payment.failed
counseling  ──produces──► session.created (PII redacted)
file        ──produces──► upload.completed, scan.clean, scan.infected
compliance  ──produces──► dsar.requested, anonymization.requested

notification ──consumes──► academic.*, finance.*, communication.*
audit       ──consumes──► * (her şey)
search-index ──consumes──► academic.*, finance.*, organization.*
performance ──consumes──► academic.attendance.*, assessment.grade.*
```

## 5. Neden bu seçildi? (Alternatifler ve trade-off)

### 5.1. Sync mi async mi?

İlk karar: "Hangisi default olsun?"

**Lumix kuralı:**
- **Sync (gRPC)** — sadece "şu anda cevap lazım" senaryolarda (query, authorization check)
- **Async (Kafka)** — side effect, cross-service notification, eventual consistency tolere edilen her şey

Sebep: cascading failure ve latency birikimini engellemek.

### 5.2. Kafka mı RabbitMQ mı?

**RabbitMQ avantajları:**
- Daha kolay routing (exchange, binding, queue)
- Daha az operasyonel karmaşıklık (single node OK)
- Per-message acknowledgment

**Kafka avantajları (Lumix tercih):**
- **Replay yeteneği** — event'leri geçmişe gidip yeniden okuyabilirsin
- **Yüksek throughput** — partition tabanlı paralelizm
- **Durability** — disk'te kalıcı log
- **Stream processing** ekosistemi (Kafka Streams, ksqlDB) — ileride lazım olursa
- **Compaction** — log compaction state recovery için ideal

Lumix'in kararı: **Kafka** — replay + yüksek throughput + sektör standardı.

### 5.3. Schema registry seçimi

| Aday | Karar |
|---|---|
| Confluent Schema Registry | Lisans + closed source endişesi |
| Apicurio Registry | **Seçildi** — Apache 2.0, Confluent API uyumlu, Red Hat destekli |
| Karapace | OSS, daha az olgun |
| Custom | Reinventing the wheel |

### 5.4. Avro mı Protobuf mu?

| Aday | Karar |
|---|---|
| Avro | Kafka dünyasında çok yaygın, ama Lumix'in gRPC için zaten Protobuf'ı var |
| Protobuf | **Seçildi** — gRPC + Kafka tek schema dili, code-gen birleşik |
| JSON Schema | Schema kontrolü zayıf, payload büyük |

### 5.5. Kabul edilen trade-off'lar

| Trade-off | Maliyet | Mitigation |
|---|---|---|
| Eventual consistency | Veri farklı servislerde farklı zamanlarda güncellenir | Domain dilinde açık ifade, frontend optimistic update |
| At-least-once duplicate | Aynı event iki kez işlenebilir | Idempotent consumer (event ID check) |
| Schema evolution dikkat | Breaking change all consumers'ı kırar | Apicurio + BACKWARD + topic versioning |
| Debug zorluğu | Akışı izlemek zor | OpenTelemetry distributed tracing |
| Operasyonel yük | Kafka cluster bakımı | Apicurio + Kafka tek package, monitoring entegre |
| Saga karmaşıklığı | Multi-step iş süreçleri | Temporal orchestration |

## 6. Pratik örnek

### 6.1. Producer — outbox publisher

```java
// adapter/out/kafka/KafkaOutboxRelay.java
@Component
@RequiredArgsConstructor
@Slf4j
public class KafkaOutboxRelay {

    private final OutboxRepository outboxRepo;
    private final KafkaTemplate<String, byte[]> kafkaTemplate;

    @Scheduled(fixedDelay = 500)
    @Transactional
    public void relay() {
        List<OutboxRecord> batch = outboxRepo.findUnpublishedBatch(100);
        for (OutboxRecord record : batch) {
            try {
                ProducerRecord<String, byte[]> kafkaRecord = new ProducerRecord<>(
                    record.topic(),
                    null, // partition
                    record.aggregateId(), // key
                    record.payload()  // Protobuf bytes
                );

                kafkaRecord.headers().add("correlation-id", record.correlationId().getBytes());
                kafkaRecord.headers().add("tenant-id", record.tenantId().getBytes());
                kafkaRecord.headers().add("event-id", record.eventId().getBytes());
                kafkaRecord.headers().add("producer-service", "academic-service".getBytes());

                kafkaTemplate.send(kafkaRecord).get(5, TimeUnit.SECONDS);
                outboxRepo.markPublished(record.id());
            } catch (Exception ex) {
                log.error("Outbox relay başarısız: {}", record.id(), ex);
                outboxRepo.incrementAttempt(record.id());
            }
        }
    }
}
```

### 6.2. Consumer — idempotent

```java
// adapter/in/kafka/AttendanceMarkedConsumer.java
@Component
@RequiredArgsConstructor
@Slf4j
public class AttendanceMarkedConsumer {

    private final ProcessedEventRepository processedRepo;
    private final NotifyParentUseCase notifyParentUseCase;

    @KafkaListener(
        topics = "academic.attendance.marked.v1",
        groupId = "notification-service",
        containerFactory = "protobufKafkaListenerContainerFactory"
    )
    @Transactional
    public void handle(
        @Payload AttendanceMarkedV1 event,
        @Header("event-id") String eventId,
        @Header("tenant-id") String tenantId,
        @Header("correlation-id") String correlationId
    ) {
        // Idempotency check
        if (processedRepo.existsByEventId(UUID.fromString(eventId))) {
            log.debug("Event zaten işlenmiş, atlanıyor: {}", eventId);
            return;
        }

        // MDC for logging/tracing
        MDC.put("correlation-id", correlationId);
        MDC.put("tenant-id", tenantId);
        MDC.put("event-id", eventId);

        try {
            for (var mark : event.getMarksList()) {
                if (mark.getPresence() == PresenceStatus.ABSENT) {
                    notifyParentUseCase.execute(new NotifyParentCommand(
                        StudentId.of(mark.getStudentId()),
                        "Çocuğunuz bugün okula gelmedi."
                    ));
                }
            }

            processedRepo.markProcessed(UUID.fromString(eventId), Instant.now());
        } finally {
            MDC.clear();
        }
    }
}
```

### 6.3. Kafka producer config (Spring Boot)

```yaml
spring:
  kafka:
    bootstrap-servers: kafka-broker-0:9092,kafka-broker-1:9092,kafka-broker-2:9092
    properties:
      schema.registry.url: http://apicurio:8080/apis/registry/v2
      apicurio.registry.url: http://apicurio:8080/apis/registry/v2
      apicurio.registry.serde.id-strategy: io.apicurio.registry.serde.strategy.TopicIdStrategy
    producer:
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: io.apicurio.registry.serde.protobuf.ProtobufKafkaSerializer
      acks: all                    # tüm replikalar onayla
      enable-idempotence: true     # producer idempotency
      compression-type: snappy
      retries: 10
      properties:
        max.in.flight.requests.per.connection: 5
    consumer:
      key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      value-deserializer: io.apicurio.registry.serde.protobuf.ProtobufKafkaDeserializer
      enable-auto-commit: false    # manual commit (transactional)
      isolation-level: read_committed
      properties:
        specific.protobuf.value.type: com.lumix.proto.academic.events.v1.AttendanceMarkedV1
```

### 6.4. Domain event → integration event mapping

```java
// adapter/out/kafka/AttendanceEventMapper.java
@Component
public class AttendanceEventMapper {

    public AttendanceMarkedV1 toProto(AttendanceMarkedEvent domainEvent) {
        return AttendanceMarkedV1.newBuilder()
            .setEventId(UUID.randomUUID().toString())
            .setAttendanceId(domainEvent.attendanceId().value().toString())
            .setClassId(domainEvent.classId().value().toString())
            .setTenantId(domainEvent.tenantId().toString())
            .setDate(domainEvent.date().toString())
            .setSubmittedAt(toTimestamp(domainEvent.submittedAt()))
            .setSubmittedByUserId(domainEvent.submittedBy().toString())
            .addAllMarks(domainEvent.marks().stream()
                .map(this::toProtoMark)
                .toList())
            .build();
    }

    private AttendanceMarkedV1.StudentMark toProtoMark(StudentMark mark) {
        return AttendanceMarkedV1.StudentMark.newBuilder()
            .setStudentId(mark.studentId().value().toString())
            .setPresence(toProtoPresence(mark.presence()))
            .build();
    }

    private AttendanceMarkedV1.PresenceStatus toProtoPresence(PresenceStatus s) {
        return switch (s) {
            case PRESENT -> AttendanceMarkedV1.PresenceStatus.PRESENT;
            case ABSENT  -> AttendanceMarkedV1.PresenceStatus.ABSENT;
            case LATE    -> AttendanceMarkedV1.PresenceStatus.LATE;
            case EXCUSED -> AttendanceMarkedV1.PresenceStatus.EXCUSED;
        };
    }

    private Timestamp toTimestamp(LocalDateTime t) {
        Instant i = t.toInstant(ZoneOffset.UTC);
        return Timestamp.newBuilder()
            .setSeconds(i.getEpochSecond())
            .setNanos(i.getNano())
            .build();
    }
}
```

### 6.5. Retry + DLQ konfigürasyonu

```java
@Configuration
public class KafkaErrorHandlerConfig {

    @Bean
    public CommonErrorHandler kafkaErrorHandler(KafkaTemplate<Object, Object> template) {
        var recoverer = new DeadLetterPublishingRecoverer(
            template,
            (record, ex) -> new TopicPartition(record.topic() + ".DLQ", record.partition())
        );

        var handler = new DefaultErrorHandler(
            recoverer,
            new ExponentialBackOff(1000L, 2.0) {{
                setMaxInterval(30_000L);
                setMaxElapsedTime(120_000L);
            }}
        );

        // Non-retryable exception'lar — direkt DLQ'ya
        handler.addNotRetryableExceptions(
            DeserializationException.class,
            MessageConversionException.class
        );

        return handler;
    }
}
```

## 7. Dikkat edilecek tuzaklar

**Tuzak 1 — Event vs Command karıştırmak.**
Topic ismi `SendNotification` — bu komut, event değil. EDA prensibini bozar.
**Önleme:** Topic ve event isimleri past tense. Command'lar gRPC ile gönderilir, event'ler Kafka'da.

**Tuzak 2 — Event'in çok detay taşıması.**
`UserCreated` event'i içine kullanıcının tüm session geçmişini koymak. Event şişer, schema kırılganlaşır.
**Önleme:** Event sadece minimal context içerir. Detay lazımsa: consumer gRPC ile sorgular.

**Tuzak 3 — Event'in çok az detay taşıması.**
`UserCreated` sadece `userId`. Consumer her seferinde gRPC çağrısı yapar.
**Önleme:** "Üzerine eyleme geçilebilir minimum context" prensibi. Tipik consumer'ın ihtiyacı kadar bilgi koy.

**Tuzak 4 — Event yokken yaratmak.**
"Belki bir gün lazım olur" diye 50 event yayınla. Kimse dinlemiyor ama hepsi disk yiyor.
**Önleme:** Gerçek bir consumer ihtiyacı doğmadan event yayınlama. YAGNI.

**Tuzak 5 — Sync ile Async'i karıştırmak.**
Endpoint sync döner ama içinde Kafka publish yapar — publish başarısız olursa user bilmez.
**Önleme:** Outbox pattern. Sync response sadece "kabul edildi" der, side effect arka planda.

**Tuzak 6 — Schema değişimini ciddiye almamak.**
"Bu alanı kaldıralım, eski tüketici zaten bakmıyor olabilir." Sonuç: production'da consumer crash.
**Önleme:** Schema Registry + BACKWARD compatibility + Pact contract test.

**Tuzak 7 — Idempotency'yi atlamak.**
"At-least-once ama bizde duplicate olmayacak, garanti." Bir gün olur — duplicate user, duplicate payment.
**Önleme:** Her consumer idempotent. Event ID kontrol tablosu.

**Tuzak 8 — DLQ'yu izlememek.**
DLQ topic'ler dolar ama kimse bakmaz. Sessizce data kaybediyorsundur.
**Önleme:** Grafana alert: DLQ message count > 0 → uyarı.

**Tuzak 9 — Partition key seçimi yanlış.**
Random partition key seçilir; aynı entity için event'ler sıra dışı işlenir.
**Önleme:** Aynı aggregate'in event'leri aynı partition'a düşmeli (key = aggregate_id veya tenant_id).

**Tuzak 10 — Distributed monolith (event ile).**
A → B'ye event yollar, B sync olarak A'ya tekrar gRPC çağırır. Akış halen sıkı bağımlı.
**Önleme:** Async + event = loose coupling. Eğer hala sync zincir kuruyorsan EDA değil.

**Tuzak 11 — Choreography'yi her şeye uygulamak.**
Karmaşık çok adımlı iş süreci (saga) choreography ile yapılır. Compensation kayboluyor, akış görünmez.
**Önleme:** Karmaşık saga'lar orchestration (Temporal). Basit fan-out choreography.

**Tuzak 12 — Tenant izolasyonunu unutmak.**
Event payload'unda `tenant_id` yok. Consumer cross-tenant veri sızdırır.
**Önleme:** Her event'te `tenant_id` zorunlu. Consumer kontrol etmeden işlem yapmaz.

## 8. Diğer konularla ilişkisi

- [Microservices Architecture](./01-microservices-architecture.md) — EDA microservice iletişimin omurgası
- [Domain-Driven Design](./02-domain-driven-design.md) — domain event vs integration event ayrımı
- [Outbox Pattern](./06-outbox-pattern.md) — atomic write + event publish
- [Saga Pattern](./05-saga-pattern.md) — distributed transaction = event orchestration
- [Hexagonal Architecture](./03-hexagonal-architecture.md) — Kafka consumer = inbound adapter, publisher = outbound adapter
- [gRPC Service Communication](../03-backend/03-grpc-service-communication.md) — sync iletişim, EDA'nın tamamlayıcısı

## 9. Daha derine inmek için

**Kitaplar:**
- "Designing Event-Driven Systems" — Ben Stopford (Confluent)
- "Kafka: The Definitive Guide" — Gwen Shapira, Todd Palino vd.
- "Building Event-Driven Microservices" — Adam Bellemare
- "Enterprise Integration Patterns" — Gregor Hohpe — klasik

**Online:**
- confluent.io/blog — Kafka pattern'leri
- microservices.io — event-driven pattern'ler
- learn.apicurio.io — Apicurio dokümantasyonu

**Spring Kafka:**
- docs.spring.io/spring-kafka
- Spring Cloud Stream (Lumix kullanmıyor, daha düşük seviye Kafka tercih ediyor)

**Search keywords (İngilizce):**
- "event-driven architecture vs request response"
- "domain event vs integration event"
- "choreography vs orchestration saga"
- "kafka exactly once semantics"
- "schema evolution backward compatibility"
- "idempotent consumer pattern"
- "outbox pattern transactional messaging"

## 10. Sözlük

- **At-least-once delivery** — Event en az bir kez teslim edilir; duplicate olabilir.
- **At-most-once delivery** — Event en fazla bir kez teslim edilir; kayıp olabilir.
- **Choreography** — Servislerin merkezi koordinatör olmadan event'lere reaksiyon vererek koordine olması.
- **Compensation Event** — Saga'da başarısız adımı geri almak için yayınlanan event.
- **Consumer Group** — Aynı topic'i paylaşan, partition'ları paylaştıran consumer'lar.
- **Dead Letter Queue (DLQ)** — Tüketilemeyen mesajların gönderildiği topic.
- **Domain Event** — Bounded context içinde olan iş olayı, internal model.
- **Event Sourcing** — Sistemin state'inin event log'undan türetildiği pattern (Lumix bunu kullanmıyor).
- **Exactly-once Semantics** — Event tam olarak bir kez işlenir; transactional producer + idempotent consumer.
- **Idempotency** — Aynı işlemin birden fazla kez yapılmasının sonucu değiştirmemesi.
- **Integration Event** — Bounded context dışına yayınlanan public sözleşme.
- **Orchestration** — Merkezi bir koordinatörün servislere command gönderdiği saga stili.
- **Partition** — Kafka topic'in paralel okunabilir alt parçası.
- **Producer/Consumer** — Event yayınlayan/dinleyen servis.
- **Schema Registry** — Event şemalarının versiyonlu tutulduğu servis. Apicurio.
- **Topic** — Kafka'da event kanalı. Lumix'te `service.aggregate.event.v1` formatı.
